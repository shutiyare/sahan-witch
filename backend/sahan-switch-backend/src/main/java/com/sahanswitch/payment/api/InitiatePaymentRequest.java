package com.sahanswitch.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to initiate a payment between two participants.
 *
 * <p><b>Task 2.2.</b> Carries both the sender (initiator) and the destination (receiver)
 * participant, so the switch knows where to route the payment.
 *
 * <p><b>Task 4.</b> The last four fields are optional ISO 20022 identifiers. They are
 * filled when the request was produced from a pacs.008 message
 * ({@code Iso20022MessageTransformer}) and left {@code null} for plain JSON clients, in
 * which case the switch generates a UETR and uses the payment reference as EndToEndId.
 */
public record InitiatePaymentRequest(

        @NotNull(message = "Sender participant ID is required")
        UUID senderParticipantId,

        @NotNull(message = "Destination participant ID is required")
        UUID destinationParticipantId,

        @NotBlank(message = "Source account is required")
        @Size(max = 100)
        String sourceAccount,

        @NotBlank(message = "Destination account is required")
        @Size(max = 100)
        String destinationAccount,

        @NotNull(message = "Amount is required")
        @DecimalMin(
                value = "0.0001",
                inclusive = true,
                message = "Amount must be greater than zero"
        )
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Pattern(
                regexp = "^[A-Z]{3}$",
                message = "Currency must be a 3-letter uppercase code"
        )
        String currency,

        @Size(max = 35, message = "EndToEndId must not exceed 35 characters")
        String endToEndId,

        UUID uetr,

        @Size(max = 140, message = "Debtor name must not exceed 140 characters")
        String debtorName,

        @Size(max = 140, message = "Creditor name must not exceed 140 characters")
        String creditorName
) {

    /** Plain (non-ISO) request: no EndToEndId, UETR or party names. */
    public InitiatePaymentRequest(
            UUID senderParticipantId,
            UUID destinationParticipantId,
            String sourceAccount,
            String destinationAccount,
            BigDecimal amount,
            String currency
    ) {
        this(senderParticipantId, destinationParticipantId, sourceAccount,
                destinationAccount, amount, currency, null, null, null, null);
    }
}
