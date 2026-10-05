package com.sahanswitch.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Standard error body returned by every failing API call.
 *
 * <p><b>Task 1.3.</b> {@code fieldErrors} carries field-level validation problems
 * (field name -> message) so clients can highlight the exact inputs that are wrong.
 * It is {@code null} - and therefore omitted from the JSON - for every other kind of error.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {

    /** Convenience constructor for errors that have no field-level details. */
    public ApiError(Instant timestamp, int status, String error, String message) {
        this(timestamp, status, error, message, null);
    }
}
