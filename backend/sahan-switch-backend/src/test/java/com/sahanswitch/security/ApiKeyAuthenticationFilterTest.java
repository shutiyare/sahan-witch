package com.sahanswitch.security;

import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthenticationFilterTest {

    private final ApiKeyService apiKeyService = new ApiKeyService();
    private final ParticipantRepository repository = mock(ParticipantRepository.class);
    private final RestAuthenticationEntryPoint entryPoint = mock(RestAuthenticationEntryPoint.class);
    private final FilterChain chain = mock(FilterChain.class);

    private ApiKeyAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(repository, apiKeyService, entryPoint);
        request = new MockHttpServletRequest("GET", "/api/v1/payments");
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Participant participant(boolean active, String allowedRoles) {
        Participant participant = mock(Participant.class);
        when(participant.getId()).thenReturn(UUID.randomUUID());
        when(participant.getCode()).thenReturn("BANKA");
        when(participant.isActive()).thenReturn(active);
        when(participant.getAllowedRoles()).thenReturn(allowedRoles);
        return participant;
    }

    @Test
    void noHeaderLeavesTheRequestAloneForLaterFilters() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(repository, never()).findByApiKeyHash(any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void blankHeaderIsTreatedAsAbsent() throws Exception {
        request.addHeader("X-API-KEY", "   ");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(entryPoint, never()).commence(any(), any(), any());
    }

    @Test
    void aValidKeyAuthenticatesTheParticipantWithoutKeepingTheKey() throws Exception {
        String key = apiKeyService.generateKey();
        Participant participant = participant(true, "PARTICIPANT");
        when(repository.findByApiKeyHash(apiKeyService.hash(key))).thenReturn(Optional.of(participant));
        request.addHeader("X-API-KEY", " " + key + " "); // surrounding whitespace is tolerated

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        ApiKeyAuthenticationToken token = assertInstanceOf(ApiKeyAuthenticationToken.class, authentication);
        assertEquals(participant.getId(), token.getParticipantId());
        assertEquals("BANKA", token.getName());
        assertNull(token.getCredentials(), "the clear-text key must not be retained");
        assertTrue(token.isAuthenticated());
        assertEquals(List.of("ROLE_PARTICIPANT"),
                token.getAuthorities().stream().map(Object::toString).toList());
    }

    @Test
    void anUnknownKeyIsRejectedWithoutReachingTheChain() throws Exception {
        when(repository.findByApiKeyHash(any())).thenReturn(Optional.empty());
        request.addHeader("X-API-KEY", "ssk_unknown");

        filter.doFilter(request, response, chain);

        verify(entryPoint).commence(any(), any(), any(BadCredentialsException.class));
        verify(chain, never()).doFilter(any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aDeactivatedParticipantsKeyGetsTheSameRejection() throws Exception {
        Participant inactive = participant(false, "PARTICIPANT");
        when(repository.findByApiKeyHash(any())).thenReturn(Optional.of(inactive));
        request.addHeader("X-API-KEY", "ssk_whatever");

        filter.doFilter(request, response, chain);

        verify(entryPoint).commence(any(), any(), any(BadCredentialsException.class));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void anApiKeyNeverGrantsAdminEvenIfTheDatabaseSaysSo() {
        Participant participant = participant(true, "ADMIN, participant ,admin");

        List<String> authorities = ApiKeyAuthenticationFilter.authoritiesOf(participant).stream()
                .map(Object::toString).toList();

        assertEquals(List.of("ROLE_PARTICIPANT"), authorities);
        assertFalse(authorities.contains("ROLE_ADMIN"));
    }

    @Test
    void anEmptyRoleListFallsBackToParticipant() {
        List<String> authorities = ApiKeyAuthenticationFilter.authoritiesOf(participant(true, "ADMIN")).stream()
                .map(Object::toString).toList();

        assertEquals(List.of("ROLE_PARTICIPANT"), authorities);
    }
}
