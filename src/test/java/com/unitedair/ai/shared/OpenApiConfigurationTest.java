package com.unitedair.ai.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

class OpenApiConfigurationTest {

    @Test
    void swaggerDeclaresGlobalBearerJwtAuthentication() throws Exception {
        Class<?> configurationType;
        try {
            configurationType = Class.forName(
                    "com.unitedair.ai.shared.OpenApiConfiguration");
        } catch (ClassNotFoundException missing) {
            fail("OpenApiConfiguration is missing, so Swagger cannot authorize JWT requests.");
            return;
        }

        Object configuration = configurationType.getConstructor().newInstance();
        OpenAPI openApi = (OpenAPI) configurationType
                .getMethod("unitedAirOpenApi")
                .invoke(configuration);

        assertThat(openApi.getComponents().getSecuritySchemes())
                .containsKey("bearerAuth");
        SecurityScheme bearer =
                openApi.getComponents().getSecuritySchemes().get("bearerAuth");
        assertThat(bearer.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(bearer.getScheme()).isEqualTo("bearer");
        assertThat(bearer.getBearerFormat()).isEqualTo("JWT");
        assertThat(openApi.getSecurity())
                .anySatisfy(requirement ->
                        assertThat(requirement).containsKey("bearerAuth"));
    }
}
