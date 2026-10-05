package com.sahanswitch.payment.application;

import com.sahanswitch.common.exception.IdempotencyConflictException;
import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.integration.application.PaymentRoutingService;
import com.sahanswitch.integration.domain.IntegrationResult;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import com.sahanswitch.outbox.OutboxEventTypes;
import com.sahanswitch.outbox.OutboxProperties;
import com.sahanswitch.outbox.OutboxService;
import com.sahanswitch.payment.api.InitiatePaymentRequest;
import com.sahanswitch.payment.domain.Payment;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.payment.domain.PaymentStatusChangedEvent;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 6.1: {@link PaymentService} - idempotency edge cases, routing outcomes and the
 * status-change events published for the audit trail. Pure unit test: repositories, the
 * router and the event publisher are mocks, and the "transaction" just runs the callback.
 */
class PaymentServiceTest {

    private static final String KEY = "key-1";

    private PaymentRepository paymentRepository;
    private ParticipantRepository participantRepository;
    private PaymentRoutingService routingService;
    private ApplicationEventPublisher eventPublisher;
    private PaymentService service;
    private TransactionTemplate transactionTemplateForTests;
    private OutboxService outboxService;

    private Participant sender;
    private Participant destination;
    private UUID senderId;
    private UUID destinationId;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        participantRepository = mock(ParticipantRepository.class);
        routingService = mock(PaymentRoutingService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        // Runs the callback directly, and pretends a transaction is active (PaymentService
        // refuses to publish audit events outside one).
        TransactionTemplate transactionTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try {
                    return action.doInTransaction(new SimpleTransactionStatus());
                } finally {
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                }
            }
        };

        transactionTemplateForTests = transactionTemplate;
        outboxService = mock(OutboxService.class);

        service = serviceWith(RoutingMode.SYNC, true);

        senderId = UUID.randomUUID();
        destinationId = UUID.randomUUID();
        sender = participant(senderId, "SENDER", true);
        destination = participant(destinationId, "DEST", true);

        when(participantRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(participantRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /** Builds the service in the given routing mode (the events still go to the mocked publisher). */
    private PaymentService serviceWith(RoutingMode mode, boolean outboxEnabled) {
        return new PaymentService(
                paymentRepository,
                participantRepository,
                routingService,
                transactionTemplateForTests,
                new PaymentLifecycle(eventPublisher),
                outboxService,
                new RoutingProperties(mode),
                new OutboxProperties(outboxEnabled, 1000, 50, 5, 5000)
        );
    }

    private Participant participant(UUID id, String code, boolean active) {
        Participant participant = mock(Participant.class);
        when(participant.getId()).thenReturn(id);
        when(participant.getCode()).thenReturn(code);
        when(participant.isActive()).thenReturn(active);
        when(participant.getStatus()).thenReturn(active ? ParticipantStatus.ACTIVE : ParticipantStatus.INACTIVE);
        return participant;
    }

    private InitiatePaymentRequest request(BigDecimal amount) {
        return new InitiatePaymentRequest(senderId, destinationId, "acc1", "acc2", amount, "USD");
    }

    private Payment existingPayment() {
        return Payment.create(sender, destination, "acc1", "acc2", new BigDecimal("100.50"), "USD", KEY);
    }

    private List<PaymentStatusChangedEvent> publishedEvents() {
        ArgumentCaptor<PaymentStatusChangedEvent> captor = ArgumentCaptor.forClass(PaymentStatusChangedEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    // ================================================================ routing outcomes

    @Test
    void successfulRoutingCompletesPaymentWithExternalReference() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.success("EXT-9", "ok"));

        PaymentExecutionResult result = service.processPayment(request(new BigDecimal("100.50")), KEY);

        assertTrue(result.created());
        assertEquals(PaymentStatus.COMPLETED, result.payment().status());
        assertEquals("EXT-9", result.payment().externalReference());
        assertNull(result.payment().failureReason());
        assertEquals(destinationId, result.payment().destinationParticipantId());
        assertEquals(senderId, result.payment().senderParticipantId());
        verify(routingService).routeTo(any(), any(Payment.class));
    }

    @Test
    void failedRoutingMarksPaymentFailedWithReason() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.failed("Insufficient funds"));

        PaymentExecutionResult result = service.processPayment(request(new BigDecimal("100.50")), KEY);

        assertTrue(result.created());
        assertEquals(PaymentStatus.FAILED, result.payment().status());
        assertEquals("Insufficient funds", result.payment().failureReason());
        assertNull(result.payment().externalReference());
    }

    @Test
    void timeoutRoutingMarksPaymentFailedWithTimeoutReason() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.timeout("no answer in 5s"));

        PaymentExecutionResult result = service.processPayment(request(new BigDecimal("100.50")), KEY);

        assertEquals(PaymentStatus.FAILED, result.payment().status());
        assertEquals("Participant timeout: no answer in 5s", result.payment().failureReason());
    }

    @Test
    void failureWithOnlyAMessageStillGetsAReason() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(new IntegrationResult(
                        com.sahanswitch.integration.domain.IntegrationStatus.FAILED, null, null, "only a message"));

        PaymentExecutionResult result = service.processPayment(request(BigDecimal.TEN), KEY);

        assertEquals("only a message", result.payment().failureReason());
    }

    // ================================================================ audit events

    @Test
    void successfulPaymentPublishesOneEventPerTransition() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.success("EXT-9", "ok"));

        service.processPayment(request(new BigDecimal("100.50")), KEY);

        List<PaymentStatusChangedEvent> events = publishedEvents();

        assertEquals(3, events.size());
        assertNull(events.get(0).previousStatus());
        assertEquals(PaymentStatus.ACCEPTED, events.get(0).newStatus());
        assertEquals(PaymentStatus.ACCEPTED, events.get(1).previousStatus());
        assertEquals(PaymentStatus.PROCESSING, events.get(1).newStatus());
        assertEquals(PaymentStatus.PROCESSING, events.get(2).previousStatus());
        assertEquals(PaymentStatus.COMPLETED, events.get(2).newStatus());
        assertNotNull(events.get(2).occurredAt());
        assertTrue(events.get(2).reason().contains("EXT-9"));
    }

    @Test
    void failedPaymentPublishesFailureReasonInLastEvent() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.failed("Account closed"));

        service.processPayment(request(new BigDecimal("100.50")), KEY);

        List<PaymentStatusChangedEvent> events = publishedEvents();
        PaymentStatusChangedEvent last = events.get(events.size() - 1);

        assertEquals(PaymentStatus.FAILED, last.newStatus());
        assertEquals(PaymentStatus.PROCESSING, last.previousStatus());
        assertEquals("Account closed", last.reason());
    }

    @Test
    void replayPublishesNoEvents() {
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY))
                .thenReturn(Optional.of(existingPayment()));

        service.processPayment(request(new BigDecimal("100.50")), KEY);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void manualTransitionsPublishEventsToo() {
        Payment payment = existingPayment();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.startProcessing(paymentId);
        service.failPayment(paymentId);

        List<PaymentStatusChangedEvent> events = publishedEvents();
        assertEquals(2, events.size());
        assertEquals(PaymentStatus.PROCESSING, events.get(0).newStatus());
        assertEquals(PaymentStatus.FAILED, events.get(1).newStatus());
    }

    @Test
    void statusChangesOutsideATransactionAreRefusedSoNoAuditEntryIsLost() {
        Payment payment = existingPayment();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThrows(IllegalStateException.class, () -> service.startProcessing(paymentId));
    }

    // ================================================================ idempotency

    @Test
    void replayWithSameDetailsReturnsExistingWithoutRoutingAgain() {
        Payment existing = existingPayment();
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY)).thenReturn(Optional.of(existing));

        // 100.5000 vs stored 100.50: same value, different scale, must still count as the same request
        PaymentExecutionResult result = service.processPayment(request(new BigDecimal("100.5000")), KEY);

        assertFalse(result.created());
        assertEquals(existing.getPaymentReference(), result.payment().paymentReference());
        verify(routingService, never()).routeTo(any(), any(Payment.class));
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void replayWithDifferentAmountIsRejected() {
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY)).thenReturn(Optional.of(existingPayment()));

        assertThrows(IdempotencyConflictException.class,
                () -> service.processPayment(request(new BigDecimal("999")), KEY));
    }

    @Test
    void replayWithDifferentCurrencyIsRejected() {
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY)).thenReturn(Optional.of(existingPayment()));

        InitiatePaymentRequest other =
                new InitiatePaymentRequest(senderId, destinationId, "acc1", "acc2", new BigDecimal("100.50"), "EUR");

        assertThrows(IdempotencyConflictException.class, () -> service.processPayment(other, KEY));
    }

    @Test
    void replayWithDifferentAccountsIsRejected() {
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY)).thenReturn(Optional.of(existingPayment()));

        InitiatePaymentRequest otherSource =
                new InitiatePaymentRequest(senderId, destinationId, "OTHER", "acc2", new BigDecimal("100.50"), "USD");
        InitiatePaymentRequest otherDestination =
                new InitiatePaymentRequest(senderId, destinationId, "acc1", "OTHER", new BigDecimal("100.50"), "USD");

        assertThrows(IdempotencyConflictException.class, () -> service.processPayment(otherSource, KEY));
        assertThrows(IdempotencyConflictException.class, () -> service.processPayment(otherDestination, KEY));
    }

    @Test
    void replayWithDifferentDestinationParticipantIsRejected() {
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY)).thenReturn(Optional.of(existingPayment()));

        InitiatePaymentRequest otherDestination =
                new InitiatePaymentRequest(senderId, UUID.randomUUID(), "acc1", "acc2", new BigDecimal("100.50"), "USD");

        assertThrows(IdempotencyConflictException.class, () -> service.processPayment(otherDestination, KEY));
    }

    @Test
    void replayWithDifferentUetrIsRejectedButMissingUetrIsAccepted() {
        Payment existing = existingPayment();
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY)).thenReturn(Optional.of(existing));

        InitiatePaymentRequest differentUetr = new InitiatePaymentRequest(senderId, destinationId, "acc1", "acc2",
                new BigDecimal("100.50"), "USD", null, UUID.randomUUID(), null, null);
        assertThrows(IdempotencyConflictException.class, () -> service.processPayment(differentUetr, KEY));

        InitiatePaymentRequest sameUetr = new InitiatePaymentRequest(senderId, destinationId, "acc1", "acc2",
                new BigDecimal("100.50"), "USD", null, existing.getUetr(), null, null);
        assertFalse(service.processPayment(sameUetr, KEY).created());

        // A plain JSON replay carries no UETR at all: that must not count as a mismatch.
        assertFalse(service.processPayment(request(new BigDecimal("100.50")), KEY).created());
    }

    @Test
    void idempotencyKeyIsTrimmedBeforeLookup() {
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY)).thenReturn(Optional.of(existingPayment()));

        assertFalse(service.processPayment(request(new BigDecimal("100.50")), "  " + KEY + "  ").created());
    }

    @Test
    void concurrentDuplicateLosingTheRaceIsResolvedAsReplay() {
        Payment winner = existingPayment();

        // First lookup (inside the failed transaction): nothing yet. After the unique violation: the winner's row is visible.
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_payments_participant_idempotency"));

        PaymentExecutionResult result = service.processPayment(request(new BigDecimal("100.50")), KEY);

        assertFalse(result.created());
        assertEquals(winner.getPaymentReference(), result.payment().paymentReference());
        verify(routingService, never()).routeTo(any(), any(Payment.class));
    }

    @Test
    void concurrentDuplicateWithDifferentDetailsIsAConflict() {
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingPayment()));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_payments_participant_idempotency"));

        assertThrows(IdempotencyConflictException.class,
                () -> service.processPayment(request(new BigDecimal("999")), KEY));
    }

    @Test
    void integrityViolationThatIsNotAnIdempotencyRaceIsPropagated() {
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("something else"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.processPayment(request(new BigDecimal("100.50")), KEY));
    }

    // ================================================================ validation

    @Test
    void unknownParticipantsAreRejected() {
        InitiatePaymentRequest unknownSender =
                new InitiatePaymentRequest(UUID.randomUUID(), destinationId, "a", "b", BigDecimal.ONE, "USD");
        assertThrows(ResourceNotFoundException.class, () -> service.processPayment(unknownSender, KEY));

        InitiatePaymentRequest unknownDestination =
                new InitiatePaymentRequest(senderId, UUID.randomUUID(), "a", "b", BigDecimal.ONE, "USD");
        assertThrows(ResourceNotFoundException.class, () -> service.processPayment(unknownDestination, KEY));
    }

    @Test
    void inactiveSenderOrDestinationIsRejected() {
        when(sender.isActive()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.processPayment(request(BigDecimal.TEN), KEY));

        when(sender.isActive()).thenReturn(true);
        when(destination.isActive()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.processPayment(request(BigDecimal.TEN), KEY));

        verify(paymentRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void senderAndDestinationMustDiffer() {
        InitiatePaymentRequest self =
                new InitiatePaymentRequest(senderId, senderId, "a", "b", BigDecimal.ONE, "USD");

        assertThrows(IllegalArgumentException.class, () -> service.processPayment(self, KEY));
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.processPayment(request(BigDecimal.TEN), "  "));
        assertThrows(IllegalArgumentException.class, () -> service.processPayment(request(BigDecimal.TEN), null));
    }

    @Test
    void isoIdentifiersFromTheRequestAreStoredOnThePayment() {
        UUID uetr = UUID.randomUUID();
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.success("EXT", "ok"));

        InitiatePaymentRequest iso = new InitiatePaymentRequest(senderId, destinationId, "acc1", "acc2",
                new BigDecimal("5"), "USD", "E2E-77", uetr, "Alice", "Bob");

        PaymentExecutionResult result = service.processPayment(iso, KEY);

        assertEquals("E2E-77", result.payment().endToEndId());
        assertEquals(uetr, result.payment().uetr());
        assertEquals("Alice", result.payment().debtorName());
        assertEquals("Bob", result.payment().creditorName());
    }

    // ================================================================ Task 2: asynchronous routing (outbox)

    @Test
    void asyncModeAcceptsThePaymentAndQueuesARoutingRequestWithoutCallingTheParticipant() {
        service = serviceWith(RoutingMode.ASYNC, true);

        PaymentExecutionResult result = service.processPayment(request(new BigDecimal("100.50")), KEY);

        assertTrue(result.created());
        assertEquals(PaymentStatus.ACCEPTED, result.payment().status(), "final status is not known yet");
        verify(routingService, never()).routeTo(any(), any(Payment.class));

        // exactly one routing request is written to the outbox, about this payment
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).append(
                org.mockito.ArgumentMatchers.eq(OutboxEventTypes.AGGREGATE_PAYMENT),
                any(),
                org.mockito.ArgumentMatchers.eq(OutboxEventTypes.PAYMENT_ROUTING_REQUESTED),
                payload.capture());
        assertTrue(payload.getValue().toString().contains("DEST"), "message names the destination participant");

        // only the creation event: PROCESSING/COMPLETED/FAILED are produced later by the consumer
        List<PaymentStatusChangedEvent> events = publishedEvents();
        assertEquals(1, events.size());
        assertNull(events.get(0).previousStatus());
        assertEquals(PaymentStatus.ACCEPTED, events.get(0).newStatus());
    }

    @Test
    void asyncModeReplayDoesNotQueueAnotherRoutingRequest() {
        service = serviceWith(RoutingMode.ASYNC, true);
        Payment existing = existingPayment();
        when(paymentRepository.findBySenderParticipant_IdAndIdempotencyKey(senderId, KEY))
                .thenReturn(Optional.of(existing));

        PaymentExecutionResult result = service.processPayment(request(new BigDecimal("100.50")), KEY);

        assertFalse(result.created());
        verify(outboxService, never()).append(any(), any(), any(), any());
    }

    @Test
    void asyncModeWithoutTheOutboxFailsFastAtStartup() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> serviceWith(RoutingMode.ASYNC, false));

        assertTrue(exception.getMessage().contains("outbox"), exception.getMessage());
    }

    @Test
    void syncModeNeverWritesRoutingRequestsToTheOutbox() {
        when(routingService.routeTo(anyString(), any(Payment.class)))
                .thenReturn(IntegrationResult.success("EXT-1", "ok"));

        service.processPayment(request(new BigDecimal("100.50")), KEY);

        verify(outboxService, never()).append(any(), any(), any(), any());
    }
}
