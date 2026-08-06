package com.unitedair.ai.shared;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger/OpenAPI metadata, including the JWT control used by protected endpoints. */
@Configuration
public class OpenApiConfiguration {

    static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI unitedAirOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("UnitedAir AI API")
                        .version("1.0")
                        .description("""
                                Agentic RAG airline assistant API. Sign in with /auth/login,
                                copy the returned token, and use Swagger's Authorize button.
                                """))
                .components(new Components().addSecuritySchemes(
                        BEARER_AUTH,
                        new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
