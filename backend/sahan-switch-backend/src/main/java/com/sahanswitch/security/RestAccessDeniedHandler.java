package com.sahanswitch.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Task 1: answers "you may not do this" (HTTP 403) with the standard {@code ApiError} JSON. */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        SecurityErrorWriter.write(
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "Forbidden",
                "You are not allowed to perform this operation"
        );
    }
}
