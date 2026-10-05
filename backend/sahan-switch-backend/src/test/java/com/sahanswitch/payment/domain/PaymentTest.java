package com.sahanswitch.payment.domain;

import com.sahanswitch.common.exception.InvalidPaymentStateException;
import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.domain.ParticipantStatus;
import com.sahanswitch.participant.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 6.1: the payment state machine.
 *
 * <pre>
 *   ACCEPTED --markProcessing--> PROCESSING --markCompleted--> COMPLETED
 *      |                             |
 *      +-------markFailed------------+--------> FAILED
 * </pre>
 * Every other jump must be rejected with {@link InvalidPaymentStateException}.
 */
class PaymentTest {

    private Participant sender() {
        return new Participant("A", "Bank A", ParticipantType.BANK, ParticipantStatus.ACTIVE);
    }

    private Participant destination() {
        return new Participant("B", "Wallet B", ParticipantType.MOBILE_WALLET, ParticipantStatus.ACTIVE);
    }

    private Payment newPayment() {
        return Payment.create(sender(), destination(), "acc1", "acc2", new BigDecimal("10.00"), "usd", "key-1");
    }

    private Payment processing() {
        Payment payment = newPayment();
        payment.markProcessing();
        return payment;
    }

    // ------------------------------------------------------------------ creation

    @Test
    void createStartsAcceptedWithReferenceAndUppercaseCurrency() {
        Payment payment = newPayment();

        assertEquals(PaymentStatus.ACCEPTED, payment.getStatus());
        assertEquals("USD", payment.getCurrency());
        assertTrue(payment.getPaymentReference().matches("SHN-[0-9A-F]{12}"));
        assertEquals("B", payment.getDestinationParticipant().getCode());
        assertEquals("A", payment.getSenderParticipant().getCode());
    }

    @Test
    void plainPaymentGetsGeneratedUetrAndEndToEndIdDefaultsToReference() {
        Payment payment = newPayment();

        assertNotNull(payment.getUetr());
        assertEquals(4, payment.getUetr().version());
        assertEquals(payment.getPaymentReference(), payment.getEndToEndId());
        assertNull(payment.getDebtorName());
    }

    @Test
    void suppliedIsoIdentifiersAreKept() {
        UUID uetr = UUID.randomUUID();

        Payment payment = Payment.create(sender(), destination(), "acc1", "acc2",
                BigDecimal.TEN, "USD", "key", "E2E-1", uetr, "Alice", "Bob");

        assertEquals("E2E-1", payment.getEndToEndId());
        assertEquals(uetr, payment.getUetr());
        assertEquals("Alice", payment.getDebtorName());
        assertEquals("Bob", payment.getCreditorName());
    }

    @Test
    void paymentReferencesAreUnique() {
        assertTrue(!newPayment().getPaymentReference().equals(newPayment().getPaymentReference()));
    }

    // ------------------------------------------------------------------ valid transitions

    @Test
    void happyPathAcceptedToProcessingToCompleted() {
        Payment payment = newPayment();

        payment.markProcessing();
        assertEquals(PaymentStatus.PROCESSING, payment.getStatus());

        payment.markCompleted("EXT-1");
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals("EXT-1", payment.getExternalReference());
        assertNull(payment.getFailureReason());
    }

    @Test
    void canFailFromAccepted() {
        Payment payment = newPayment();

        payment.markFailed("rejected");

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals("rejected", payment.getFailureReason());
        assertNull(payment.getExternalReference());
    }

    @Test
    void canFailFromProcessing() {
        Payment payment = processing();

        payment.markFailed("timeout");

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals("timeout", payment.getFailureReason());
    }

    // ------------------------------------------------------------------ invalid transitions

    @Test
    void cannotStartProcessingTwice() {
        Payment payment = processing();
        assertThrows(InvalidPaymentStateException.class, payment::markProcessing);
    }

    @Test
    void cannotCompleteFromAccepted() {
        Payment payment = newPayment();
        assertThrows(InvalidPaymentStateException.class, () -> payment.markCompleted("EXT"));
        assertEquals(PaymentStatus.ACCEPTED, payment.getStatus());
    }

    @Test
    void cannotCompleteTwice() {
        Payment payment = processing();
        payment.markCompleted("EXT");
        assertThrows(InvalidPaymentStateException.class, () -> payment.markCompleted("EXT-2"));
        assertEquals("EXT", payment.getExternalReference());
    }

    @Test
    void completedIsTerminal() {
        Payment payment = processing();
        payment.markCompleted("EXT");

        assertThrows(InvalidPaymentStateException.class, () -> payment.markFailed("late"));
        assertThrows(InvalidPaymentStateException.class, payment::markProcessing);
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertNull(payment.getFailureReason());
    }

    @Test
    void failedIsTerminal() {
        Payment payment = newPayment();
        payment.markFailed("x");

        assertThrows(InvalidPaymentStateException.class, payment::markProcessing);
        assertThrows(InvalidPaymentStateException.class, () -> payment.markCompleted("EXT"));
        assertThrows(InvalidPaymentStateException.class, () -> payment.markFailed("again"));
        assertEquals("x", payment.getFailureReason());
    }

    @Test
    void rejectedTransitionLeavesPaymentUntouched() {
        Payment payment = newPayment();

        assertThrows(InvalidPaymentStateException.class, () -> payment.markCompleted("EXT"));

        assertEquals(PaymentStatus.ACCEPTED, payment.getStatus());
        assertNull(payment.getExternalReference());
    }

    // ------------------------------------------------------------------ failure reason handling

    @Test
    void longFailureReasonIsCappedAndBlankReasonGetsDefault() {
        Payment longReason = newPayment();
        longReason.markFailed("x".repeat(9000));
        assertEquals(4000, longReason.getFailureReason().length());

        Payment noReason = newPayment();
        noReason.markFailed(null);
        assertEquals("Unknown failure", noReason.getFailureReason());

        Payment blankReason = newPayment();
        blankReason.markFailed("   ");
        assertEquals("Unknown failure", blankReason.getFailureReason());
    }
}
