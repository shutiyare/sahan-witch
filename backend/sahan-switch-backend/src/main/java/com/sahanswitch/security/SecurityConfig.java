package com.sahanswitch.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sahanswitch.participant.infrastructure.ParticipantRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Task 1: who may call what.
 *
 * <h3>Two ways to authenticate</h3>
 * <ul>
 *   <li><b>JWT</b> ({@code Authorization: Bearer ...}) for people using the portal, obtained from
 *       {@code POST /api/v1/auth/login}. Verified statelessly with an HS256 secret.</li>
 *   <li><b>{@code X-API-KEY}</b> for a participant's own systems. Maps to exactly one participant.</li>
 * </ul>
 *
 * <h3>Rules at URL level</h3>
 * Public: login, health, Swagger. {@code ADMIN} only: creating/changing participants, issuing
 * keys, analytics, the manual payment-lifecycle endpoints, actuator details. Everything else just
 * needs a valid identity; <em>which</em> payments an identity may touch is decided per request by
 * {@link PaymentAccessPolicy}.
 *
 * <p>The API is stateless (no session, so no CSRF risk): credentials travel in headers only.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32;

    // ------------------------------------------------------------------ JWT plumbing

    @Bean
    public SecretKey jwtSecretKey(SecurityProperties properties) {

        String secret = properties.jwt().secret();

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // Fail at startup rather than run with a guessable signing key
            throw new IllegalStateException(
                    "sahanswitch.security.jwt.secret (env SAHANSWITCH_JWT_SECRET) must be set and at least "
                            + MIN_SECRET_BYTES + " bytes long"
            );
        }

        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // default validators (expiry) + the issuer must be ours
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Reads the {@code roles} claim ["ADMIN"] as the authority ROLE_ADMIN. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtTokenService.CLAIM_ROLES);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    // ------------------------------------------------------------------ filter chain

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ParticipantRepository participantRepository,
            ApiKeyService apiKeyService,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        // preflight requests carry no credentials
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // public
                        .requestMatchers("/api/v1/auth/login", "/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()

                        // operator only
                        .requestMatchers(HttpMethod.POST, "/api/v1/participants", "/api/v1/participants/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/participants/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/analytics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/payments/*/processing",
                                "/api/v1/payments/*/complete",
                                "/api/v1/payments/*/fail").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // everything else: any authenticated identity (ownership checked per payment)
                        .anyRequest().authenticated()
                )

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                // The API-key filter runs just before the bearer-token filter
                .addFilterBefore(
                        new ApiKeyAuthenticationFilter(participantRepository, apiKeyService, authenticationEntryPoint),
                        BearerTokenAuthenticationFilter.class
                );

        return http.build();
    }

    // ------------------------------------------------------------------ CORS

    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Idempotency-Key", "X-API-KEY", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
