package com.sahanswitch.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Task 1: answers "who are you?" (HTTP 401) with the same {@code ApiError} JSON shape the rest of
 * the API uses. The message is generic on purpose: it never says whether a key, a token or a
 * user exists.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        SecurityErrorWriter.write(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "Unauthorized",
                "Authentication is required: send a Bearer token or a valid X-API-KEY header"
        );
    }
}

/** Writes the {@code ApiError} JSON by hand, so the security filters need no ObjectMapper. */
final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    static void write(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"timestamp\":\"" + Instant.now() + "\","
                        + "\"status\":" + status + ","
                        + "\"error\":\"" + error + "\","
                        + "\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"
        );
    }
}
