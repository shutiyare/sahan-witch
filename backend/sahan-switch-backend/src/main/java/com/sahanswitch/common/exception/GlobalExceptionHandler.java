package com.sahanswitch.common.exception;

import com.sahanswitch.common.api.ApiError;
import com.sahanswitch.iso20022.domain.Iso20022ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates exceptions into consistent {@link ApiError} responses.
 *
 * <p><b>Task 1.3 (global exception handling).</b> Summary of the mapping:
 * <ul>
 *   <li>400 - bean validation ({@code fieldErrors} filled), bad arguments, bad ISO 20022 messages,
 *       missing mandatory headers</li>
 *   <li>404 - unknown resource</li>
 *   <li>409 - duplicates, idempotency conflicts, illegal payment state transitions,
 *       optimistic-lock failures (client should retry), data integrity violations</li>
 *   <li>422 - a well-formed request that breaks a business rule (e.g. inactive participant)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------- 409 CONFLICT

    /** Same Idempotency-Key reused with a different payload. */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException exception) {
        return buildResponse(HttpStatus.CONFLICT, "Idempotency Conflict", exception.getMessage(), null);
    }

    /** A payment was asked to make a transition its state machine does not allow. */
    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ApiError> handleInvalidPaymentState(InvalidPaymentStateException exception) {
        return buildResponse(HttpStatus.CONFLICT, "Invalid Payment State", exception.getMessage(), null);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateResourceException exception) {
        return buildResponse(HttpStatus.CONFLICT, "Duplicate Resource", exception.getMessage(), null);
    }

    /**
     * Two requests changed the same row at the same time and this one lost (JPA {@code @Version}).
     * Nothing was applied, so it is always safe for the client to retry the whole request.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException exception) {
        logger.warn("Optimistic locking conflict: {}", exception.getMessage());
        return buildResponse(
                HttpStatus.CONFLICT,
                "Concurrent Modification",
                "The resource was modified by another transaction. Please retry the request.",
                null
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException exception) {
        return buildResponse(
                HttpStatus.CONFLICT,
                "Data Integrity Violation",
                "The request conflicts with existing data",
                null
        );
    }

    // ---------------------------------------------------------------- 404 NOT FOUND

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception) {
        return buildResponse(HttpStatus.NOT_FOUND, "Resource Not Found", exception.getMessage(), null);
    }

    // ---------------------------------------------------------------- 400 BAD REQUEST

    /** Bean validation failed: expose every offending field in {@code fieldErrors}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {

        Map<String, String> errors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Validation Failed",
                "Request validation failed",
                errors
        );
    }

    /** ISO 20022 XML was malformed or misses mandatory elements. */
    @ExceptionHandler(Iso20022ValidationException.class)
    public ResponseEntity<ApiError> handleIsoValidation(Iso20022ValidationException exception) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid ISO 20022 Message",
                exception.getMessage(),
                exception.getViolations().isEmpty() ? null : exception.getViolations()
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException exception) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Missing Header",
                "Required header '" + exception.getHeaderName() + "' is missing",
                null
        );
    }

    // ---------------------------------------------------------------- 401 / 403 (Task 1: security)

    /** Wrong username or password, or no usable identity for the request. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException exception) {
        String message = exception instanceof BadCredentialsException
                ? exception.getMessage()
                : "Authentication is required: send a Bearer token or a valid X-API-KEY header";
        return buildResponse(HttpStatus.UNAUTHORIZED, "Unauthorized", message, null);
    }

    /** Authenticated, but not allowed (e.g. a participant trying to send as another participant). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        return buildResponse(HttpStatus.FORBIDDEN, "Forbidden", exception.getMessage(), null);
    }

    /** Unreadable request body: malformed JSON, or a value that is not a valid enum constant. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Malformed Request",
                "Request body is missing or malformed (check field names, enum values and JSON syntax)",
                null
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", exception.getMessage(), null);
    }

    // ---------------------------------------------------------------- 422 UNPROCESSABLE ENTITY

    /**
     * The request is syntactically valid but the system is not in a state that allows it
     * (e.g. the sender participant is inactive). That is a business-rule violation, not a
     * conflict between two requests, hence 422.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException exception) {
        return buildResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Unprocessable Request",
                exception.getMessage(),
                null
        );
    }

    // ---------------------------------------------------------------- helper

    private ResponseEntity<ApiError> buildResponse(
            HttpStatus status,
            String error,
            String message,
            Map<String, String> fieldErrors
    ) {

        ApiError response = new ApiError(
                Instant.now(),
                status.value(),
                error,
                message,
                fieldErrors
        );

        // Always answer errors as JSON, whatever the request's Accept header says.
        // Without this, a client that sends "Accept: application/xml" (natural on the
        // ISO 20022 endpoints) makes Spring fail with HttpMediaTypeNotAcceptableException
        // while writing the error body, and the real error (400/404/409) turns into a 500.
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}
