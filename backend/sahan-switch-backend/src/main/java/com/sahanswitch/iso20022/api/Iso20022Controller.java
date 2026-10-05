package com.sahanswitch.iso20022.api;

import com.sahanswitch.iso20022.application.Iso20022MessageTransformer;
import com.sahanswitch.iso20022.application.Iso20022Pacs002Service;
import com.sahanswitch.iso20022.application.Iso20022Pacs008Service;
import com.sahanswitch.payment.api.InitiatePaymentRequest;
import com.sahanswitch.payment.application.PaymentExecutionResult;
import com.sahanswitch.payment.application.PaymentService;
import com.sahanswitch.security.PaymentAccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ISO 20022 (XML) entry points of the switch.
 *
 * <p><b>Task 4.</b> A participant can submit a standard {@code pacs.008.001.10} message and
 * receives a standard {@code pacs.002.001.10} status report. Internally the message is
 * converted to an {@link InitiatePaymentRequest} and runs through the very same
 * {@link PaymentService} pipeline as JSON payments (idempotency, routing, audit).
 *
 * <p>The response content type is set explicitly on each response instead of using
 * {@code produces=}: errors are still returned as JSON {@code ApiError}, and a restrictive
 * {@code produces} would stop the exception handler from writing them.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "ISO 20022", description = "pacs.008 / pacs.002 messaging")
public class Iso20022Controller {

    private final Iso20022MessageTransformer transformer;
    private final Iso20022Pacs008Service pacs008Service;
    private final Iso20022Pacs002Service pacs002Service;
    private final PaymentService paymentService;
    private final PaymentAccessPolicy accessPolicy;

    public Iso20022Controller(
            Iso20022MessageTransformer transformer,
            Iso20022Pacs008Service pacs008Service,
            Iso20022Pacs002Service pacs002Service,
            PaymentService paymentService,
            PaymentAccessPolicy accessPolicy
    ) {
        this.transformer = transformer;
        this.pacs008Service = pacs008Service;
        this.pacs002Service = pacs002Service;
        this.paymentService = paymentService;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Submit a pacs.008. Returns a pacs.002: 201 when the payment was created, 200 when the
     * same message was submitted before (idempotent replay). A payment rejected by the
     * destination participant is still "created": the report then carries status RJCT.
     *
     * <p>The {@code Idempotency-Key} header is optional here; when absent the message's
     * UETR (unique per transaction by definition) is used as the idempotency key.
     */
    @Operation(summary = "Submit a pacs.008 credit transfer, receive a pacs.002 status report")
    @PostMapping(
            value = "/iso20022/pacs008",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE}
    )
    public ResponseEntity<String> submitPacs008(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody String xml
    ) {
        InitiatePaymentRequest request = transformer.parsePacs008(xml);

        // Task 1: the debtor agent in the message must be the authenticated participant
        accessPolicy.assertMayInitiateAs(request.senderParticipantId());

        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? request.uetr().toString()
                : idempotencyKey;

        PaymentExecutionResult result = paymentService.processPayment(request, key);

        String statusReport = pacs002Service.generate(result.payment().id());

        return xml(result.created() ? HttpStatus.CREATED : HttpStatus.OK, statusReport);
    }

    @Operation(summary = "Get a stored payment as a pacs.008 message")
    @GetMapping("/payments/{paymentId}/pacs008")
    public ResponseEntity<String> getPacs008(@PathVariable UUID paymentId) {
        accessPolicy.assertMayView(paymentId);
        return xml(HttpStatus.OK, pacs008Service.generate(paymentId));
    }

    @Operation(summary = "Get the current status of a payment as a pacs.002 status report")
    @GetMapping("/payments/{paymentId}/pacs002")
    public ResponseEntity<String> getPacs002(@PathVariable UUID paymentId) {
        accessPolicy.assertMayView(paymentId);
        return xml(HttpStatus.OK, pacs002Service.generate(paymentId));
    }

    private ResponseEntity<String> xml(HttpStatus status, String body) {
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_XML)
                .body(body);
    }
}
