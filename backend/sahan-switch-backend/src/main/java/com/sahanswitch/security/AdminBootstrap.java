package com.sahanswitch.security;

import com.sahanswitch.security.user.User;
import com.sahanswitch.security.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Task 1: makes sure somebody can sign in on a brand-new database.
 *
 * <p>If there is no ADMIN user yet, one is created from {@code sahanswitch.security.admin.*}
 * (env {@code SAHANSWITCH_ADMIN_USERNAME} / {@code SAHANSWITCH_ADMIN_PASSWORD}). If no password
 * is configured, a random one is generated and printed to the log <b>once</b>. When an admin
 * already exists nothing happens, so restarting never resets a password.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties properties;

    public AdminBootstrap(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            SecurityProperties properties
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (userRepository.existsByRole(Role.ADMIN)) {
            return;
        }

        String username = properties.admin().username();
        String password = properties.admin().password();
        boolean generated = password == null || password.isBlank();

        if (generated) {
            byte[] bytes = new byte[18];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        userRepository.save(new User(username, passwordEncoder.encode(password), Role.ADMIN, null));

        if (generated) {
            logger.warn("Created the first administrator '{}' with the GENERATED password '{}'. "
                    + "Save it now: it is not stored anywhere and will not be shown again.", username, password);
        } else {
            logger.info("Created the first administrator '{}' from configuration", username);
        }
    }
}
