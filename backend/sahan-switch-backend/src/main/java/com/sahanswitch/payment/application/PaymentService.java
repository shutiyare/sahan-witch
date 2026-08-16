package com.sahanswitch.payment.application;

import com.sahanswitch.common.exception.IdempotencyConflictException;
import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.payment.api.InitiatePaymentRequest;
import com.sahanswitch.payment.api.PaymentResponse;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
public class PaymentService {
    Logger logger = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final ParticipantRepository participantRepository;

    public PaymentService(PaymentRepository paymentRepository, ParticipantRepository participantRepository) {
        this.paymentRepository = paymentRepository;
        this.participantRepository = participantRepository;
    }

    @Transactional
    public PaymentResponse initiatePayment(InitiatePaymentRequest request, String idempotencyKey
    ) {
//      Normalize Idempotency KEY
        String normalizedIdempotencyKey = normalizeIdempotencyKey(
                idempotencyKey
        );
//      Check if Already Exists Participant
        Participant participant = participantRepository
                .findById(request.participantId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Participant not found: "
                                        + request.participantId()
                        )
                );
//Check if Participant is Active.
        if (participant.getStatus() != ParticipantStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Payment cannot be initiated by an inactive participant"
            );
        }

        return paymentRepository
                .findByParticipant_IdAndIdempotencyKey(participant.getId(), normalizedIdempotencyKey)
                .map(existingPayment ->
                        handleExistingPayment(existingPayment, request)
                )
                .orElseGet(() ->
                        createPayment(
                                participant,
                                request,
                                normalizedIdempotencyKey
                        )
                );
    }

    private PaymentResponse handleExistingPayment(Payment existingPayment, InitiatePaymentRequest request
    ) {
        boolean sameRequest = existingPayment.getSourceAccount().equals(request.sourceAccount())
                        && existingPayment.getDestinationAccount().equals(request.destinationAccount())
                        && existingPayment.getAmount().compareTo(request.amount()) == 0
                        && existingPayment.getCurrency().equals(request.currency());

        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency key was already used for a different payment request"
            );
        }

        return toResponse(existingPayment);
    }

    private PaymentResponse createPayment(Participant participant, InitiatePaymentRequest request, String idempotencyKey) {

        String paymentReference = generatePaymentReference();

        Payment payment = new Payment(
                paymentReference,
                idempotencyKey,
                participant,
                request.sourceAccount(),
                request.destinationAccount(),
                request.amount(),
                request.currency().toUpperCase(Locale.ROOT)
        );

        Payment savedPayment = paymentRepository.save(payment);

        return toResponse(savedPayment);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header is required"
            );
        }

        return idempotencyKey.trim();
    }

    private String generatePaymentReference() {
        String randomPart = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);

        return "SHN-" + randomPart;
    }
    @Transactional
    public PaymentResponse startProcessing(UUID paymentId) {

        Payment payment = findPaymentOrThrow(paymentId);

        payment.startProcessing();

        Payment updatedPayment =
                paymentRepository.save(payment);

        return toResponse(updatedPayment);
    }
    @Transactional
    public PaymentResponse completePayment(UUID paymentId) {

        Payment payment = findPaymentOrThrow(paymentId);

        payment.complete();

        Payment updatedPayment = paymentRepository.save(payment);

        return toResponse(updatedPayment);
    }

    @Transactional
    public PaymentResponse failPayment(UUID paymentId) {

        Payment payment = findPaymentOrThrow(paymentId);

        payment.fail();

        Payment updatedPayment =
                paymentRepository.save(payment);

        return toResponse(updatedPayment);
    }

    @Transactional
    private Payment findPaymentOrThrow(UUID paymentId) {

        return paymentRepository
                .findById(paymentId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Payment not found: " + paymentId
                        )
                );
    }

    private PaymentResponse toResponse(Payment payment) {

        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentReference(),
                payment.getParticipant().getId(),
                payment.getSourceAccount(),
                payment.getDestinationAccount(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}