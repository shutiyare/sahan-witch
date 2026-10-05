package com.sahanswitch.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Task 1: sign-in endpoint used by the operations portal. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Sign in and receive a JWT")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(
            summary = "Sign in with username and password",
            description = "Returns a signed JWT. Send it as 'Authorization: Bearer <token>' on later calls."
    )
    @SecurityRequirements // public endpoint: no security requirement in the OpenAPI document
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
