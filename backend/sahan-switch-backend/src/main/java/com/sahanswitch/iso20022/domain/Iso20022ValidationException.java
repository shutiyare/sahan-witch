package com.sahanswitch.iso20022.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown when an incoming ISO 20022 message is malformed or misses mandatory data.
 *
 * <p><b>Task 4.2.</b> The {@code violations} map (ISO element path -> problem) is exposed to
 * API clients as {@code fieldErrors} (see {@code GlobalExceptionHandler}), so a sender
 * can fix every problem in one round trip instead of one at a time.
 */
public class Iso20022ValidationException extends RuntimeException {

    private final Map<String, String> violations;

    public Iso20022ValidationException(String message, Map<String, String> violations) {
        super(message);
        this.violations = Collections.unmodifiableMap(new LinkedHashMap<>(violations));
    }

    /** Convenience for a single, message-level problem such as "XML is not well-formed". */
    public Iso20022ValidationException(String message) {
        this(message, Map.of());
    }

    public Map<String, String> getViolations() {
        return violations;
    }
}
