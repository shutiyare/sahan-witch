package com.sahanswitch.security;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.payment.api.PaymentResponse;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentAccessPolicyTest {

    private final CurrentIdentity currentIdentity = mock(CurrentIdentity.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentAccessPolicy policy = new PaymentAccessPolicy(currentIdentity, paymentRepository);

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private void asParticipant() {
        when(currentIdentity.require()).thenReturn(new CurrentIdentity.Identity("BANKA", false, me));
    }

    private void asAdmin() {
        when(currentIdentity.require()).thenReturn(new CurrentIdentity.Identity("admin", true, null));
    }

    private PaymentResponse payment(UUID sender, UUID destination) {
        return new PaymentResponse(UUID.randomUUID(), "PAY-1", sender, destination, "a", "b",
                null, "USD", null, null, null, null, null, null, null, null, null);
    }

    @Test
    void aParticipantMayInitiateAsItself() {
        asParticipant();

        assertDoesNotThrow(() -> policy.assertMayInitiateAs(me));
    }

    @Test
    void aParticipantMayNotInitiateAsSomeoneElse() {
        asParticipant();

        assertThrows(AccessDeniedException.class, () -> policy.assertMayInitiateAs(other));
    }

    @Test
    void anAdminMayInitiateAsAnyone() {
        asAdmin();

        assertDoesNotThrow(() -> policy.assertMayInitiateAs(other));
    }

    @Test
    void aPartyMayViewAndAStrangerGetsNotFound() {
        asParticipant();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.isParticipantInvolved(paymentId, me)).thenReturn(true);

        assertDoesNotThrow(() -> policy.assertMayView(paymentId));

        UUID foreign = UUID.randomUUID();
        when(paymentRepository.isParticipantInvolved(foreign, me)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> policy.assertMayView(foreign));
    }

    @Test
    void viewingALoadedPaymentChecksBothSides() {
        asParticipant();

        assertDoesNotThrow(() -> policy.assertMayView(payment(me, other)));
        assertDoesNotThrow(() -> policy.assertMayView(payment(other, me)));
        assertThrows(ResourceNotFoundException.class, () -> policy.assertMayView(payment(other, UUID.randomUUID())));
    }

    @Test
    void anAdminMayViewEverythingWithoutAnyLookup() {
        asAdmin();

        assertDoesNotThrow(() -> policy.assertMayView(UUID.randomUUID()));
        assertDoesNotThrow(() -> policy.assertMayView(payment(other, other)));
        verifyNoInteractions(paymentRepository);
    }
}
