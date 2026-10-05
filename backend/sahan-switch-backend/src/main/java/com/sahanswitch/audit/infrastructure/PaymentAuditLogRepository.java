package com.sahanswitch.audit.infrastructure;

import com.sahanswitch.audit.domain.PaymentAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentAuditLogRepository extends JpaRepository<PaymentAuditLog, UUID> {

    /** Full history of one payment, oldest entry first. */
    List<PaymentAuditLog> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
