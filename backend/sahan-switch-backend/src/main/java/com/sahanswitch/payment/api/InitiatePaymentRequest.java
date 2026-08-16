package com.sahanswitch.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record InitiatePaymentRequest(

        @NotNull(message = "Participant ID is required")
        UUID participantId,

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
        String currency
) {
}