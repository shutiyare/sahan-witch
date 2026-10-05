package com.sahanswitch.security;

import com.sahanswitch.participant.domain.Participant;
import com.sahanswitch.security.user.User;
import com.sahanswitch.security.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task 1: username/password login that returns a signed JWT.
 *
 * <p>Every failure produces the same {@link BadCredentialsException} with the same message,
 * and a password check is also performed when the username does not exist, so neither the
 * response nor its timing reveals which usernames are real.
 */
@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private static final String GENERIC_FAILURE = "Invalid username or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;

    /** A valid hash nobody knows the password of, compared against when the user is unknown. */
    private final String dummyHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService tokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {

        User user = userRepository.findByUsername(request.username().trim()).orElse(null);

        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                user != null ? user.getPasswordHash() : dummyHash
        );

        if (user == null || !passwordMatches || !canSignIn(user)) {
            logger.warn("Failed login for username '{}'", request.username());
            throw new BadCredentialsException(GENERIC_FAILURE);
        }

        JwtTokenService.IssuedToken token = tokenService.issue(user);

        Participant participant = user.getParticipant();

        logger.info("User '{}' signed in with role {}", user.getUsername(), user.getRole());

        return new LoginResponse(
                token.value(),
                "Bearer",
                token.expiresInSeconds(),
                user.getUsername(),
                user.getRole(),
                participant != null ? participant.getId() : null
        );
    }

    /** A participant user can only sign in while its institution is active. */
    private boolean canSignIn(User user) {
        return user.getRole() == Role.ADMIN
                || (user.getParticipant() != null && user.getParticipant().isActive());
    }
}
