package com.sahanswitch.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("dev")
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sahan Switch API")
                        .version("1.0.0")
                        .description("API Documentation for Sahan Switch Backend Payment & Participant Engine")
                        .contact(new Contact()
                                .name("Sahan Switch Team")
                                .email("support@sahanswitch.com")));
    }
}