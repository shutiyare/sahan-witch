package com.sahanswitch.payment.application;

import com.sahanswitch.common.exception.IdempotencyConflictException;
import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.common.web.CorrelationIdFilter;
import com.sahanswitch.integration.application.PaymentRoutingService;
import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.api.InitiatePaymentRequest;
import com.sahanswitch.payment.api.PaymentResponse;
import com.sahanswitch.outbox.OutboxEventTypes;
import com.sahanswitch.outbox.OutboxProperties;
import com.sahanswitch.outbox.OutboxService;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core payment pipeline of the switch.
 *
 * <ul>
 *   <li><b>Task 3.1</b> - idempotent initiation: validate participants, look the payment up by
 *       (sender, idempotency key), replay or reject on conflict.</li>
 *   <li><b>Task 3.2</b> - end-to-end routing: ACCEPTED -> PROCESSING -> route to destination
 *       -> COMPLETED or FAILED.</li>
 *   <li><b>Task 5.2</b> - a {@code PaymentStatusChangedEvent} is published for every state
 *       change (through {@link PaymentLifecycle}) so the audit trail can never miss one.</li>
 *   <li><b>Task 2 (outbox)</b> - in {@code ASYNC} routing mode the request only ACCEPTS the
 *       payment and writes a {@code PaymentRoutingRequested} row to the outbox in the same
 *       transaction; the actual routing happens later, off the request thread, in
 *       {@link PaymentRoutingOrchestrator}. {@code SYNC} mode keeps the original behaviour.</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final ParticipantRepository participantRepository;
    private final PaymentRoutingService routingService;
    private final TransactionTemplate transactionTemplate;
    private final PaymentLifecycle lifecycle;
    private final OutboxService outboxService;
    private final RoutingMode routingMode;

    public PaymentService(
            PaymentRepository paymentRepository,
            ParticipantRepository participantRepository,
            PaymentRoutingService routingService,
            TransactionTemplate transactionTemplate,
            PaymentLifecycle lifecycle,
            OutboxService outboxService,
            RoutingProperties routingProperties,
            OutboxProperties outboxProperties
    ) {
        this.paymentRepository = paymentRepository;
        this.participantRepository = participantRepository;
        this.routingService = routingService;
        this.transactionTemplate = transactionTemplate;
        this.lifecycle = lifecycle;
        this.outboxService = outboxService;
        this.routingMode = routingProperties.mode();

        // Async routing travels through the outbox; without the relay payments would stay ACCEPTED forever
        if (routingMode == RoutingMode.ASYNC && !outboxProperties.enabled()) {
            throw new IllegalStateException(
                    "sahanswitch.routing.mode=async requires sahanswitch.outbox.enabled=true "
                            + "(routing requests are delivered through the outbox)"
            );
        }
    }

    // =====================================================================================
    // Task 3: idempotent initiation + routing
    // =====================================================================================

    /**
     * Accepts a payment, routes it to the destination participant and records the outcome.
     *
     * <p>Idempotent per (sender participant, idempotency key): a replay with the same details
     * returns the stored payment with {@code created == false} (the controller answers 200);
     * a replay with different details is rejected with {@link IdempotencyConflictException}.
     *
     * <p>This method is deliberately not {@code @Transactional}. If two identical requests race,
     * the loser fails on the unique constraint and its transaction is unusable in PostgreSQL, so
     * the replay lookup has to run in a fresh transaction.
     */
    public PaymentExecutionResult processPayment(InitiatePaymentRequest request, String idempotencyKey) {

        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);

        try {
            return transactionTemplate.execute(status -> executePayment(request, normalizedKey));
        } catch (DataIntegrityViolationException exception) {
            return transactionTemplate.execute(status ->
                    paymentRepository
                            .findBySenderParticipant_IdAndIdempotencyKey(request.senderParticipantId(), normalizedKey)
                            .map(existing -> {
                                logger.info("Concurrent duplicate request resolved as replay for key {}", normalizedKey);
                                return replay(existing, request);
                            })
                            // Not an idempotency race (some other integrity problem): surface the original error
                            .orElseThrow(() -> exception)
            );
        }
    }

    /** The whole pipeline, executed inside one database transaction. */
    private PaymentExecutionResult executePayment(InitiatePaymentRequest request, String idempotencyKey) {

        // --- Task 3.1: both participants must exist and be active -------------------------
        Participant sender = participantRepository
                .findById(request.senderParticipantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Participant not found: " + request.senderParticipantId()
                ));

        if (!sender.isActive()) {
            throw new IllegalStateException(
                    "Payment cannot be initiated by an inactive participant"
            );
        }

        // --- Task 3.1: idempotency lookup (replay / conflict) -----------------------------
        var existing = paymentRepository
                .findBySenderParticipant_IdAndIdempotencyKey(sender.getId(), idempotencyKey);

        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        Participant destination = participantRepository
                .findById(request.destinationParticipantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Destination participant not found: " + request.destinationParticipantId()
                ));

        if (sender.getId().equals(destination.getId())) {
            throw new IllegalArgumentException(
                    "Sender and destination participants must be different"
            );
        }

        if (!destination.isActive()) {
            throw new IllegalStateException(
                    "Payment cannot be sent to an inactive participant"
            );
        }

        // --- create the payment in ACCEPTED ----------------------------------------------
        Payment payment = Payment.create(
                sender,
                destination,
                request.sourceAccount(),
                request.destinationAccount(),
                request.amount(),
                request.currency(),
                idempotencyKey,
                request.endToEndId(),
                request.uetr(),
                request.debtorName(),
                request.creditorName()
        );

        // Flush now so a concurrent duplicate fails fast on the unique constraint, before any
        // routing work is done. It also assigns the id the audit entry needs.
        paymentRepository.saveAndFlush(payment);

        lifecycle.publishStatusChange(payment, null, "Payment accepted");

        // --- Task 2: ASYNC - leave routing to the outbox + RabbitMQ consumer -------------------
        if (routingMode == RoutingMode.ASYNC) {
            requestRouting(payment, destination);
            return new PaymentExecutionResult(PaymentResponse.from(payment), true);
        }

        // --- Task 3.2 (SYNC): ACCEPTED -> PROCESSING -> route -> COMPLETED / FAILED ----------
        lifecycle.transition(payment, payment::markProcessing,
                "Routing to participant " + destination.getCode());

        IntegrationResult result = routingService.routeTo(destination.getCode(), payment);

        lifecycle.applyIntegrationResult(payment, destination.getCode(), result);

        Payment savedPayment = paymentRepository.save(payment);

        return new PaymentExecutionResult(PaymentResponse.from(savedPayment), true);
    }

    /**
     * Task 2: writes the routing request into the outbox, in the SAME transaction as the
     * payment. Either both are committed or neither is, so an accepted payment can never lose its
     * routing request and a rolled-back payment can never leave one behind.
     */
    private void requestRouting(Payment payment, Participant destination) {

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("paymentId", payment.getId());
        message.put("destinationParticipantCode", destination.getCode());
        message.put("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));

        outboxService.append(
                OutboxEventTypes.AGGREGATE_PAYMENT,
                payment.getId(),
                OutboxEventTypes.PAYMENT_ROUTING_REQUESTED,
                message
        );
    }

    private PaymentExecutionResult replay(Payment existing, InitiatePaymentRequest request) {
        validateMatchingDetails(existing, request);
        return new PaymentExecutionResult(PaymentResponse.from(existing), false);
    }

    /**
     * A replay is only a replay if the business details are identical. Compared fields:
     * destination participant, both accounts, amount (by value, so 100.5 equals 100.5000),
     * currency and - only when the caller supplied them - the ISO UETR and EndToEndId.
     * Debtor/creditor names are descriptive and deliberately not compared.
     */
    private void validateMatchingDetails(Payment existing, InitiatePaymentRequest request) {

        boolean sameDestinationParticipant =
                existing.getDestinationParticipant() != null
                        && existing.getDestinationParticipant().getId()
                        .equals(request.destinationParticipantId());

        boolean sameUetr = request.uetr() == null || request.uetr().equals(existing.getUetr());

        boolean sameEndToEndId = request.endToEndId() == null
                || request.endToEndId().equals(existing.getEndToEndId());

        boolean sameRequest = sameDestinationParticipant
                && existing.getSourceAccount().equals(request.sourceAccount())
                && existing.getDestinationAccount().equals(request.destinationAccount())
                && existing.getAmount().compareTo(request.amount()) == 0
                && existing.getCurrency().equals(request.currency())
                && sameUetr
                && sameEndToEndId;

        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency key was already used for a different payment request"
            );
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header is required"
            );
        }

        return idempotencyKey.trim();
    }

    // =====================================================================================
    // Reads
    // =====================================================================================

    @Transactional(readOnly = true)
    public PaymentResponse getById(UUID paymentId) {
        return PaymentResponse.from(findPaymentOrThrow(paymentId));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByReference(String paymentReference) {
        return PaymentResponse.from(
                paymentRepository.findByPaymentReference(paymentReference)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Payment not found: " + paymentReference
                        ))
        );
    }

    // =====================================================================================
    // Manual lifecycle operations (kept for backwards compatibility)
    // =====================================================================================

    @Transactional
    public PaymentResponse startProcessing(UUID paymentId) {

        Payment payment = findPaymentOrThrow(paymentId);

        lifecycle.transition(payment, payment::markProcessing, "Processing started manually");

        return PaymentResponse.from(paymentRepository.save(payment));
    }

    @Transactional
    public PaymentResponse completePayment(UUID paymentId) {

        Payment payment = findPaymentOrThrow(paymentId);

        lifecycle.transition(payment, () -> payment.markCompleted(null), "Completed manually");

        return PaymentResponse.from(paymentRepository.save(payment));
    }

    @Transactional
    public PaymentResponse failPayment(UUID paymentId) {

        Payment payment = findPaymentOrThrow(paymentId);

        String reason = "Marked as failed manually";
        lifecycle.transition(payment, () -> payment.markFailed(reason), reason);

        return PaymentResponse.from(paymentRepository.save(payment));
    }

    private Payment findPaymentOrThrow(UUID paymentId) {

        return paymentRepository
                .findById(paymentId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Payment not found: " + paymentId
                        )
                );
    }
}
