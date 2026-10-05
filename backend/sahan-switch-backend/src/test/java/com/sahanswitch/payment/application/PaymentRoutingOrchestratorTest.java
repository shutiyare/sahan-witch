package com.sahanswitch.payment.application;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.integration.application.PaymentRoutingService;
import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.domain.PaymentStatusChangedEvent;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRoutingOrchestratorTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentRoutingService routingService = mock(PaymentRoutingService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private PaymentRoutingOrchestrator orchestrator;
    private Payment payment;
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // the lifecycle refuses to publish status changes outside a transaction
        TransactionSynchronizationManager.setActualTransactionActive(true);

        orchestrator = new PaymentRoutingOrchestrator(
                paymentRepository,
                routingService,
                new PaymentLifecycle(eventPublisher),
                new TransactionTemplate(transactionManager)
        );

        Participant sender = participant("SENDER");
        Participant destination = participant("DEST");
        payment = Payment.create(sender, destination, "a1", "a2", new BigDecimal("10.00"), "USD", "key");

        when(paymentRepository.findWithParticipantsById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private Participant participant(String code) {
        Participant participant = mock(Participant.class);
        when(participant.getId()).thenReturn(UUID.randomUUID());
        when(participant.getCode()).thenReturn(code);
        when(participant.isActive()).thenReturn(true);
        return participant;
    }

    private List<PaymentStatusChangedEvent> events() {
        ArgumentCaptor<PaymentStatusChangedEvent> captor = ArgumentCaptor.forClass(PaymentStatusChangedEvent.class);
        verify(eventPublisher, atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void anAcceptedPaymentIsRoutedAndCompleted() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.success("EXT-7", "ok"));

        orchestrator.routePayment(paymentId);

        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals("EXT-7", payment.getExternalReference());
        verify(routingService).routeTo("DEST", payment);

        List<PaymentStatusChangedEvent> events = events();
        assertEquals(2, events.size());
        assertEquals(PaymentStatus.PROCESSING, events.get(0).newStatus());
        assertEquals(PaymentStatus.COMPLETED, events.get(1).newStatus());
    }

    @Test
    void aRejectionByTheParticipantFailsThePaymentWithTheReason() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.failed("Insufficient funds"));

        orchestrator.routePayment(paymentId);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals("Insufficient funds", payment.getFailureReason());
    }

    @Test
    void aTimeoutFailsThePaymentAsATimeout() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.timeout("no answer"));

        orchestrator.routePayment(paymentId);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertTrue(payment.getFailureReason().startsWith("Participant timeout"));
    }

    @Test
    void aDeactivatedDestinationSettlesThePaymentAsFailedInsteadOfLeavingItProcessing() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenThrow(new IllegalStateException("Participant DEST is not active"));

        orchestrator.routePayment(paymentId);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertTrue(payment.getFailureReason().contains("not active"));
    }

    @Test
    void aRemovedDestinationSettlesThePaymentAsFailed() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenThrow(new ResourceNotFoundException("Participant not found"));

        orchestrator.routePayment(paymentId);

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    void aRedeliveredRequestForASettledPaymentIsIgnored() {
        payment.markProcessing();
        payment.markCompleted("EXT-1");

        orchestrator.routePayment(paymentId);

        verify(routingService, never()).routeTo(any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
    }

    @Test
    void aFailedPaymentIsNotRoutedAgainEither() {
        payment.markProcessing();
        payment.markFailed("rejected");

        orchestrator.routePayment(paymentId);

        verify(routingService, never()).routeTo(any(), any());
    }

    @Test
    void aRequestForAnUnknownPaymentIsIgnoredNotRetriedForever() {
        UUID unknown = UUID.randomUUID();
        when(paymentRepository.findWithParticipantsById(unknown)).thenReturn(Optional.empty());

        orchestrator.routePayment(unknown);

        verify(routingService, never()).routeTo(any(), any());
    }

    @Test
    void aPaymentLeftInProcessingByACrashIsRoutedAgainWithoutANewProcessingEvent() {
        payment.markProcessing();
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.success("EXT-2", "ok"));

        orchestrator.routePayment(paymentId);

        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        List<PaymentStatusChangedEvent> events = events();
        assertEquals(1, events.size(), "only the final transition is new");
        assertEquals(PaymentStatus.PROCESSING, events.get(0).previousStatus());
        assertEquals(PaymentStatus.COMPLETED, events.get(0).newStatus());
        verify(routingService, times(1)).routeTo(any(), any());
    }
}
