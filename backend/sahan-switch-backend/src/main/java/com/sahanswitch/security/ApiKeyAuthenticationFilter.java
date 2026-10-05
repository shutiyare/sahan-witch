package com.sahanswitch.security;

import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Task 1: authenticates a participant's system calling the API with {@code X-API-KEY}.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>no header: the filter does nothing (a JWT or no credential is handled further down);</li>
 *   <li>valid key of an ACTIVE participant: the request runs as that participant;</li>
 *   <li>unknown key, or a key of a deactivated participant: answered immediately with 401.
 *       Both cases produce the same message so a caller cannot learn which keys exist.</li>
 * </ul>
 *
 * <p>An API key never grants {@code ADMIN}, whatever {@code allowed_roles} contains.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-KEY";

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ParticipantRepository participantRepository;
    private final ApiKeyService apiKeyService;
    private final RestAuthenticationEntryPoint entryPoint;

    public ApiKeyAuthenticationFilter(
            ParticipantRepository participantRepository,
            ApiKeyService apiKeyService,
            RestAuthenticationEntryPoint entryPoint
    ) {
        this.participantRepository = participantRepository;
        this.apiKeyService = apiKeyService;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String presentedKey = request.getHeader(HEADER);

        if (presentedKey == null || presentedKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String hash = apiKeyService.hash(presentedKey.trim());

        Participant participant = participantRepository.findByApiKeyHash(hash)
                .filter(Participant::isActive)
                .orElse(null);

        if (participant == null) {
            logger.warn("Rejected API key (unknown key or inactive participant) for {} {}",
                    request.getMethod(), request.getRequestURI());
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthenticationToken(
                        participant.getId(),
                        participant.getCode(),
                        authoritiesOf(participant)
                )
        );

        chain.doFilter(request, response);
    }

    /** Turns {@code allowed_roles} ("PARTICIPANT,OTHER") into authorities, never including ADMIN. */
    static List<GrantedAuthority> authoritiesOf(Participant participant) {
        List<GrantedAuthority> authorities = Arrays.stream(participant.getAllowedRoles().split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(String::toUpperCase)
                .filter(role -> !role.equals(Role.ADMIN.name()))
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        if (authorities.isEmpty()) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + Role.PARTICIPANT.name()));
        }
        return authorities;
    }
}
