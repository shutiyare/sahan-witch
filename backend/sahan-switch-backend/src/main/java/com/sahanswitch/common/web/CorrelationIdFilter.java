package com.sahanswitch.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every HTTP request a correlation id.
 *
 * <p><b>Task 5.2 (audit trail support).</b> The id is taken from the {@code X-Correlation-Id}
 * request header when the caller supplies one, otherwise a random UUID is generated. It is
 * put in the SLF4J {@link MDC} (so every log line of the request carries it, see the log
 * pattern in {@code application.yml}), echoed back in the response header, and stored in
 * each audit row so a single request can be followed through logs and the database.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Name of the HTTP header used to send and return the correlation id. */
    public static final String HEADER_NAME = "X-Correlation-Id";

    /** Key under which the id is stored in the MDC. */
    public static final String MDC_KEY = "correlationId";

    /**
     * Client supplied ids are only trusted if they look harmless. This keeps log forging
     * (newlines, control characters) and oversized values out of logs and the database
     * (the audit column is VARCHAR(100)).
     */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._\\-]{1,100}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER_NAME);

        if (correlationId == null || !SAFE_ID.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Threads are pooled: always clean up so the id never leaks into another request.
            MDC.remove(MDC_KEY);
        }
    }
}
