package com.sahanswitch.audit.api;

import com.sahanswitch.audit.application.PaymentAuditService;
import com.sahanswitch.security.PaymentAccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read-only access to the payment audit trail (Task 5). There is deliberately no write endpoint. */
@RestController
@RequestMapping("/api/v1/payments/{paymentId}/audit")
@Tag(name = "Payment Audit", description = "Immutable history of payment status changes")
public class PaymentAuditController {

    private final PaymentAuditService auditService;
    private final PaymentAccessPolicy accessPolicy;

    public PaymentAuditController(PaymentAuditService auditService, PaymentAccessPolicy accessPolicy) {
        this.auditService = auditService;
        this.accessPolicy = accessPolicy;
    }

    @Operation(summary = "Get the status-change history of a payment, oldest first")
    @GetMapping
    public List<PaymentAuditLogResponse> getHistory(@PathVariable UUID paymentId) {
        // Task 1: only the sender, the receiver or an administrator may read the trail
        accessPolicy.assertMayView(paymentId);
        return auditService.getHistory(paymentId);
    }
}
