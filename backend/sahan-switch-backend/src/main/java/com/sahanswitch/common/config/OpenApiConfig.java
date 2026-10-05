package com.sahanswitch.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("dev")
public class OpenApiConfig {

    /** Task 1: the two ways to authenticate, selectable with the "Authorize" button in Swagger UI. */
    private static final String BEARER = "bearerAuth";
    private static final String API_KEY = "apiKeyAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sahan Switch API")
                        .version("1.0.0")
                        .description("API Documentation for Sahan Switch Backend Payment & Participant Engine. "
                                + "Authenticate with a JWT from POST /api/v1/auth/login (Bearer) "
                                + "or with a participant API key (X-API-KEY header).")
                        .contact(new Contact()
                                .name("Sahan Switch Team")
                                .email("support@sahanswitch.com")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes(API_KEY, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-KEY")))
                // either scheme satisfies the requirement
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY));
    }
}
