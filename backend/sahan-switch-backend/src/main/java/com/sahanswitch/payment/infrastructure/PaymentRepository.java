package com.sahanswitch.payment.infrastructure;

import com.sahanswitch.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    Optional<Payment> findByParticipant_IdAndIdempotencyKey(UUID participantId, String idempotencyKey);

    Optional<Payment> findById(UUID id);
}