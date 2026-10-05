package com.sahanswitch.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

/**
 * Task 1: the authentication produced by a valid {@code X-API-KEY}. It always represents one
 * participant; it can never be an administrator.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID participantId;
    private final String participantCode;

    public ApiKeyAuthenticationToken(
            UUID participantId,
            String participantCode,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(authorities);
        this.participantId = participantId;
        this.participantCode = participantCode;
        setAuthenticated(true);
    }

    public UUID getParticipantId() {
        return participantId;
    }

    @Override
    public Object getCredentials() {
        // The clear-text key is deliberately not kept after the lookup
        return null;
    }

    @Override
    public Object getPrincipal() {
        return participantCode;
    }
}
