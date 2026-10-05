package com.sahanswitch.security;

import com.sahanswitch.security.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Task 1: issues the signed JWTs the portal uses after {@code POST /api/v1/auth/login}.
 *
 * <p>Claims: {@code sub} (username), {@code roles} (e.g. ["ADMIN"]), {@code participantId}
 * (only for participant users), plus {@code iss}, {@code iat} and {@code exp}. The token is
 * signed with HS256 and verified by the resource server on every request, so the API holds no
 * session state.
 */
@Service
public class JwtTokenService {

    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_PARTICIPANT_ID = "participantId";

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;

    public JwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public IssuedToken issue(User user) {

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().ttl());

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(CLAIM_ROLES, java.util.List.of(user.getRole().name()));

        if (user.getParticipant() != null) {
            claims.claim(CLAIM_PARTICIPANT_ID, user.getParticipant().getId().toString());
        }

        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())
        ).getTokenValue();

        return new IssuedToken(token, properties.jwt().ttl().toSeconds());
    }

    /** @param expiresInSeconds remaining lifetime at issue time */
    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
