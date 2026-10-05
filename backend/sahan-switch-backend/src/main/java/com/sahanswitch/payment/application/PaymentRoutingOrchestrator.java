package com.sahanswitch.payment.application;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.integration.application.PaymentRoutingService;
import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Task 2: the asynchronous half of the payment pipeline. Called by the RabbitMQ consumer when a
 * {@code PaymentRoutingRequested} message arrives for an ACCEPTED payment.
 *
 * <p>It runs in <b>three phases</b> so no database transaction is held open while the slow
 * participant call (with its retries and back-off) is in flight:
 * <ol>
 *   <li><b>tx 1</b> ACCEPTED -> PROCESSING (committed, audited, announced);</li>
 *   <li><b>no transaction</b> route to the participant (retry + circuit breaker);</li>
 *   <li><b>tx 2</b> PROCESSING -> COMPLETED or FAILED.</li>
 * </ol>
 *
 * <p><b>Idempotent / safe to redeliver.</b> RabbitMQ delivers at least once, so the same request
 * can arrive twice. A payment that is already COMPLETED or FAILED is ignored; two consumers
 * racing for the same payment are separated by the entity's optimistic lock. The one case that
 * can route twice is a crash between phase 1 and phase 3 (the redelivered message finds the
 * payment in PROCESSING and routes again); real connectors must therefore pass the payment
 * reference to the participant as its own idempotency key.
 */
@Service
public class PaymentRoutingOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(PaymentRoutingOrchestrator.class);

    private final PaymentRepository paymentRepository;
    private final PaymentRoutingService routingService;
    private final PaymentLifecycle lifecycle;
    private final TransactionTemplate transactionTemplate;

    public PaymentRoutingOrchestrator(
            PaymentRepository paymentRepository,
            PaymentRoutingService routingService,
            PaymentLifecycle lifecycle,
            TransactionTemplate transactionTemplate
    ) {
        this.paymentRepository = paymentRepository;
        this.routingService = routingService;
        this.lifecycle = lifecycle;
        this.transactionTemplate = transactionTemplate;
    }

    public void routePayment(UUID paymentId) {

        // ---- phase 1: ACCEPTED -> PROCESSING -------------------------------------------
        Payment payment = transactionTemplate.execute(status -> startProcessing(paymentId));

        if (payment == null) {
            return; // nothing to do (already settled, or unknown)
        }

        String destinationCode = payment.getDestinationParticipant().getCode();

        // ---- phase 2: call the participant, outside any transaction ----------------------
        IntegrationResult result = route(destinationCode, payment);

        // ---- phase 3: PROCESSING -> COMPLETED / FAILED ---------------------------------
        transactionTemplate.executeWithoutResult(status -> settle(paymentId, destinationCode, result));
    }

    /** @return the payment (participants loaded) when it must be routed, otherwise null */
    private Payment startProcessing(UUID paymentId) {

        Payment payment = paymentRepository.findWithParticipantsById(paymentId).orElse(null);

        if (payment == null) {
            logger.error("Routing requested for unknown payment {}; ignoring", paymentId);
            return null;
        }

        if (payment.getDestinationParticipant() == null) {
            logger.error("Payment {} has no destination participant; ignoring routing request", paymentId);
            return null;
        }

        PaymentStatus current = payment.getStatus();

        if (current == PaymentStatus.ACCEPTED) {
            lifecycle.transition(
                    payment,
                    payment::markProcessing,
                    "Routing to participant " + payment.getDestinationParticipant().getCode()
            );
            paymentRepository.save(payment);
            return payment;
        }

        if (current == PaymentStatus.PROCESSING) {
            // Redelivery after a crash between phase 1 and phase 3: finish the job
            logger.warn("Payment {} is already PROCESSING (redelivered request); routing again", paymentId);
            return payment;
        }

        logger.info("Payment {} is already {}; ignoring duplicate routing request", paymentId, current);
        return null;
    }

    /**
     * Calls the participant. Situations that make routing impossible but are not worth
     * redelivering (participant deactivated or removed after the payment was accepted) settle
     * the payment as FAILED instead of leaving it stuck in PROCESSING.
     */
    private IntegrationResult route(String destinationCode, Payment payment) {
        try {
            return routingService.routeTo(destinationCode, payment);
        } catch (ResourceNotFoundException | IllegalStateException exception) {
            logger.warn("Payment {} cannot be routed to {}: {}",
                    payment.getId(), destinationCode, exception.getMessage());
            return IntegrationResult.failed("Routing failed: " + exception.getMessage());
        }
    }

    private void settle(UUID paymentId, String destinationCode, IntegrationResult result) {

        Payment payment = paymentRepository.findWithParticipantsById(paymentId).orElse(null);

        if (payment == null || payment.getStatus() != PaymentStatus.PROCESSING) {
            logger.info("Payment {} is no longer PROCESSING; discarding routing result", paymentId);
            return;
        }

        lifecycle.applyIntegrationResult(payment, destinationCode, result);
        paymentRepository.save(payment);
    }
}
