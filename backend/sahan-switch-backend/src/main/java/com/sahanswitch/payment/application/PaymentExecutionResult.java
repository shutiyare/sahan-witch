package com.sahanswitch.payment.application;

import com.sahanswitch.payment.api.PaymentResponse;

/**
 * Outcome of {@link PaymentService#processPayment}.
 *
 * @param payment the payment as currently stored
 * @param created true if this call created the payment, false if it is an idempotent replay
 */
public record PaymentExecutionResult(PaymentResponse payment, boolean created) {
}
