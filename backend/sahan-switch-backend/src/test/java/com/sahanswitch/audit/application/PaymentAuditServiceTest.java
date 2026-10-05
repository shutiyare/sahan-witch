package com.sahanswitch.audit.application;

import com.sahanswitch.audit.api.PaymentAuditLogResponse;
import com.sahanswitch.audit.domain.PaymentAuditLog;
import com.sahanswitch.audit.infrastructure.PaymentAuditLogRepository;
import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.domain.PaymentStatusChangedEvent;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 6 / Task 5.2: the audit listener and the audit history query. */
class PaymentAuditServiceTest {

    private PaymentAuditLogRepository auditLogRepository;
    private PaymentRepository paymentRepository;
    private PaymentAuditService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(PaymentAuditLogRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        service = new PaymentAuditService(auditLogRepository, paymentRepository);
    }

    @Test
    void statusChangeIsStoredAsOneAuditRowWithAllDetails() {
        UUID paymentId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-05T07:00:00Z");

        service.onPaymentStatusChanged(new PaymentStatusChangedEvent(
                paymentId, PaymentStatus.PROCESSING, PaymentStatus.FAILED, "Account closed", "corr-123", at));

        ArgumentCaptor<PaymentAuditLog> captor = ArgumentCaptor.forClass(PaymentAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        PaymentAuditLog saved = captor.getValue();
        assertEquals(paymentId, saved.getPaymentId());
        assertEquals(PaymentStatus.PROCESSING, saved.getPreviousStatus());
        assertEquals(PaymentStatus.FAILED, saved.getNewStatus());
        assertEquals("Account closed", saved.getReason());
        assertEquals("corr-123", saved.getCorrelationId());
        assertEquals(at, saved.getCreatedAt());
    }

    @Test
    void firstEntryOfAPaymentHasNoPreviousStatus() {
        service.onPaymentStatusChanged(new PaymentStatusChangedEvent(
                UUID.randomUUID(), null, PaymentStatus.ACCEPTED, "Payment accepted", null, Instant.now()));

        ArgumentCaptor<PaymentAuditLog> captor = ArgumentCaptor.forClass(PaymentAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        assertNull(captor.getValue().getPreviousStatus());
        assertNull(captor.getValue().getCorrelationId());
    }

    /**
     * The whole point of the design decision: the listener must run BEFORE_COMMIT, inside the
     * payment's own transaction. If someone changes the phase, audit rows could be lost.
     */
    @Test
    void listenerRunsBeforeCommitSoAuditAndPaymentCommitTogether() throws NoSuchMethodException {
        Method listener = PaymentAuditService.class
                .getMethod("onPaymentStatusChanged", PaymentStatusChangedEvent.class);

        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);

        assertEquals(TransactionPhase.BEFORE_COMMIT, annotation.phase());
    }

    @Test
    void historyIsReturnedOldestFirstAsResponses() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.existsById(paymentId)).thenReturn(true);

        PaymentAuditLog first = PaymentAuditLog.from(new PaymentStatusChangedEvent(
                paymentId, null, PaymentStatus.ACCEPTED, "accepted", "c", Instant.parse("2026-10-05T07:00:00Z")));
        PaymentAuditLog second = PaymentAuditLog.from(new PaymentStatusChangedEvent(
                paymentId, PaymentStatus.ACCEPTED, PaymentStatus.PROCESSING, "routing", "c", Instant.parse("2026-10-05T07:00:01Z")));
        when(auditLogRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId)).thenReturn(List.of(first, second));

        List<PaymentAuditLogResponse> history = service.getHistory(paymentId);

        assertEquals(2, history.size());
        assertEquals(PaymentStatus.ACCEPTED, history.get(0).newStatus());
        assertEquals(PaymentStatus.PROCESSING, history.get(1).newStatus());
        assertEquals("routing", history.get(1).reason());
    }

    @Test
    void historyOfUnknownPaymentIsNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.existsById(paymentId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.getHistory(paymentId));
        verify(auditLogRepository, never()).findByPaymentIdOrderByCreatedAtAsc(any());
    }
}
