package com.sahanswitch.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Task 1: answers "who is making this request?" in one place, whichever way they signed in
 * (JWT or API key), so business code never inspects Spring Security types directly.
 */
@Component
public class CurrentIdentity {

    /**
     * @param subject       username (JWT) or participant code (API key)
     * @param admin         true for the switch operator
     * @param participantId the institution the caller acts for; null for administrators
     */
    public record Identity(String subject, boolean admin, UUID participantId) {
    }

    /** The identity of the current request. A missing identity is an authentication failure (401). */
    public Identity require() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated identity");
        }

        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(("ROLE_" + Role.ADMIN.name())::equals);

        UUID participantId = null;

        if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            participantId = apiKey.getParticipantId();
        } else if (authentication instanceof JwtAuthenticationToken jwtToken) {
            String claim = jwtToken.getToken().getClaimAsString(JwtTokenService.CLAIM_PARTICIPANT_ID);
            if (claim != null) {
                participantId = UUID.fromString(claim);
            }
        }

        // A non-admin without a participant cannot be tied to any payment: treat as unauthenticated
        if (!admin && participantId == null) {
            throw new AuthenticationCredentialsNotFoundException("Identity is not linked to a participant");
        }

        return new Identity(authentication.getName(), admin, participantId);
    }
}
