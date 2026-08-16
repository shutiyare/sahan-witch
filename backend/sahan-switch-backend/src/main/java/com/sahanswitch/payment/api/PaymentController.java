package com.sahanswitch.payment.api;

import com.sahanswitch.payment.application.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody InitiatePaymentRequest request) {
        PaymentResponse response =
                paymentService.initiatePayment(request, idempotencyKey);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/{paymentId}/processing")
    public ResponseEntity<PaymentResponse> startProcessing(@PathVariable UUID paymentId) {

        PaymentResponse response =
                paymentService.startProcessing(paymentId);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{paymentId}/complete")
    public ResponseEntity<PaymentResponse> completePayment(@PathVariable UUID paymentId) {

        PaymentResponse response = paymentService.completePayment(paymentId);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{paymentId}/fail")
    public ResponseEntity<PaymentResponse> failPayment(@PathVariable UUID paymentId) {

        PaymentResponse response =
                paymentService.failPayment(paymentId);

        return ResponseEntity.ok(response);
    }

}