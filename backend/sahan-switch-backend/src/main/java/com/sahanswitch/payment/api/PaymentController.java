package com.sahanswitch.payment.api;

import com.sahanswitch.payment.application.PaymentExecutionResult;
import com.sahanswitch.payment.application.PaymentSearchCriteria;
import com.sahanswitch.payment.application.PaymentSearchService;
import com.sahanswitch.payment.application.PaymentService;
import com.sahanswitch.payment.domain.PaymentStatus;
import com.sahanswitch.security.PaymentAccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * JSON API for payments.
 *
 * <p>The XML (ISO 20022) entry points live in {@code Iso20022Controller}; both call the same
 * {@link PaymentService} pipeline.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Endpoints for managing payment transactions")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentSearchService searchService;
    private final PaymentAccessPolicy accessPolicy;

    public PaymentController(
            PaymentService paymentService,
            PaymentSearchService searchService,
            PaymentAccessPolicy accessPolicy
    ) {
        this.paymentService = paymentService;
        this.searchService = searchService;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Task 3.1: 201 Created for a new payment, 200 OK when the {@code Idempotency-Key} was
     * already used with the same request (replay of the stored result).
     */
    @Operation(
            summary = "Initiate a new payment",
            description = "Routes and processes an incoming payment. Returns 201 when the payment is created "
                    + "and 200 when the Idempotency-Key was already used with the same request (replay)."
    )
    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InitiatePaymentRequest request
    ) {
        // Task 1: a participant can only send money as itself
        accessPolicy.assertMayInitiateAs(request.senderParticipantId());

        PaymentExecutionResult result = paymentService.processPayment(request, idempotencyKey);

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;

        return ResponseEntity
                .status(status)
                .body(result.payment());
    }

    /**
     * Task 3: paginated search. All filters are optional and combine with AND.
     * Example: {@code /api/v1/payments/search?status=FAILED&currency=USD&fromDate=2026-10-01&page=0&size=20&sort=createdAt,desc}.
     * Declared before {@code /{paymentId}}; "search" is a literal path so it always wins.
     */
    @Operation(
            summary = "Search payments (paginated)",
            description = "Filters: senderParticipantId, destinationParticipantId, status, currency, reference "
                    + "(contains), fromDate / toDate (yyyy-MM-dd or ISO-8601 instant, inclusive). "
                    + "Paging: page (from 0), size (max 100), sort (createdAt, updatedAt, amount, currency, status, "
                    + "paymentReference; e.g. sort=amount,desc). Participants only see their own payments."
    )
    @GetMapping("/search")
    public Page<PaymentResponse> search(
            @RequestParam(required = false) UUID senderParticipantId,
            @RequestParam(required = false) UUID destinationParticipantId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            Pageable pageable
    ) {
        Instant from = parseBoundary(fromDate, "fromDate", false);
        Instant to = parseBoundary(toDate, "toDate", true);

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("fromDate must not be after toDate");
        }

        return searchService.search(
                new PaymentSearchCriteria(
                        senderParticipantId,
                        destinationParticipantId,
                        status,
                        currency,
                        from,
                        to,
                        reference
                ),
                pageable
        );
    }

    /**
     * Accepts "2026-10-05" (a whole day, UTC: start of day for fromDate, end of day for toDate)
     * or a full instant "2026-10-05T07:30:00Z".
     */
    private static Instant parseBoundary(String value, String name, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() == 10) {
                LocalDate date = LocalDate.parse(text);
                return endOfDay
                        ? date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1_000)
                        : date.atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return Instant.parse(text);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    name + " must be yyyy-MM-dd or an ISO-8601 instant (e.g. 2026-10-05T07:30:00Z)");
        }
    }

    @Operation(summary = "Get a payment by id")
    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@PathVariable UUID paymentId) {
        accessPolicy.assertMayView(paymentId);
        return paymentService.getById(paymentId);
    }

    @Operation(summary = "Get a payment by its public reference (SHN-...)")
    @GetMapping("/by-reference/{paymentReference}")
    public PaymentResponse getPaymentByReference(@PathVariable String paymentReference) {
        PaymentResponse payment = paymentService.getByReference(paymentReference);
        accessPolicy.assertMayView(payment);
        return payment;
    }

    // ---- manual lifecycle endpoints (kept for backwards compatibility) ----

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
