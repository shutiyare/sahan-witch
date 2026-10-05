package com.sahanswitch.security;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * @param accessToken   the signed JWT; send it as {@code Authorization: Bearer <token>}
 * @param expiresIn     lifetime in seconds
 * @param participantId set for participant users, absent for administrators
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String username,
        Role role,
        UUID participantId
) {
}
