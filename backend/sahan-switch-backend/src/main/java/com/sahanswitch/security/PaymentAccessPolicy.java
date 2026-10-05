package com.sahanswitch.security;

import com.sahanswitch.common.exception.ResourceNotFoundException;
import com.sahanswitch.payment.api.PaymentResponse;
import com.sahanswitch.payment.infrastructure.PaymentRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Task 1: ownership rules for payments (the part URL rules cannot express).
 *
 * <ul>
 *   <li><b>Initiating:</b> a participant may only send as itself - {@code senderParticipantId}
 *       must equal the authenticated participant, otherwise 403. Without this rule any
 *       participant could move money out of any other participant's account.</li>
 *   <li><b>Reading:</b> a participant may only see payments it sent or received. For any other
 *       payment the answer is 404, exactly as if it did not exist, so ids cannot be probed.</li>
 *   <li>Administrators are not restricted.</li>
 * </ul>
 */
@Component
public class PaymentAccessPolicy {

    private final CurrentIdentity currentIdentity;
    private final PaymentRepository paymentRepository;

    public PaymentAccessPolicy(CurrentIdentity currentIdentity, PaymentRepository paymentRepository) {
        this.currentIdentity = currentIdentity;
        this.paymentRepository = paymentRepository;
    }

    /** @throws AccessDeniedException (HTTP 403) when a participant tries to send as someone else */
    public void assertMayInitiateAs(UUID senderParticipantId) {

        CurrentIdentity.Identity identity = currentIdentity.require();

        if (identity.admin()) {
            return;
        }

        if (!identity.participantId().equals(senderParticipantId)) {
            throw new AccessDeniedException(
                    "A participant can only initiate payments as itself (senderParticipantId mismatch)"
            );
        }
    }

    /** @throws ResourceNotFoundException (HTTP 404) when the caller is not a party to the payment */
    @Transactional(readOnly = true)
    public void assertMayView(UUID paymentId) {

        CurrentIdentity.Identity identity = currentIdentity.require();

        if (identity.admin()) {
            return;
        }

        if (!paymentRepository.isParticipantInvolved(paymentId, identity.participantId())) {
            throw new ResourceNotFoundException("Payment not found: " + paymentId);
        }
    }

    /** Same rule for a payment that has already been loaded (e.g. looked up by reference). */
    public void assertMayView(PaymentResponse payment) {

        CurrentIdentity.Identity identity = currentIdentity.require();

        if (identity.admin()) {
            return;
        }

        UUID me = identity.participantId();

        boolean involved = me.equals(payment.senderParticipantId()) || me.equals(payment.destinationParticipantId());

        if (!involved) {
            throw new ResourceNotFoundException("Payment not found: " + payment.paymentReference());
        }
    }
}
