package com.unitedair.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.unitedair.ai.shared.TraceContext;
import com.unitedair.ai.shared.ApiExceptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class ToolInvocationLoggerTest {

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void directToolInvocationCreatesAnAuditSessionInsteadOfWritingNull() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        AtomicReference<Object> loggedSession = new AtomicReference<>();

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenAnswer(invocation -> {
            if ("s".equals(invocation.getArgument(0))) {
                loggedSession.set(invocation.getArgument(1));
            }
            return statement;
        });
        when(statement.update(any(KeyHolder.class))).thenReturn(1);
        when(statement.update()).thenReturn(1);
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject("passenger@unitedair.demo")
                .claim("uid", 7L)
                .claim("name", "Passenger")
                .claim("role", "PASSENGER")
                .build();
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken(jwt, null);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        ToolInvocationLogger logger = new ToolInvocationLogger(jdbc);
        logger.invoke(
                "FlightSearchTool",
                "PASSENGER",
                "ROUTING",
                Map.of("origin", "BLR", "destination", "GOI"),
                () -> new ToolInvocationLogger.ToolResult(Map.of("count", 1), "one flight"));

        assertThat(loggedSession.get()).isInstanceOf(String.class);
        assertThat((String) loggedSession.get()).isNotBlank();
    }

    @Test
    void applicationNotFoundFailureRemainsTypedThroughTheToolEnvelope() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update(any(KeyHolder.class))).thenReturn(1);
        when(statement.update()).thenReturn(1);

        ToolInvocationLogger logger = new ToolInvocationLogger(jdbc);
        ToolDtos.ToolOutcome outcome = logger.invoke(
                "BookingManagementTool",
                "RETRIEVE_BOOKING",
                "PASSENGER",
                "ROUTING",
                Map.of("pnrProvided", true),
                () -> {
                    throw new ApiExceptions.NotFound("No booking found.");
                });

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.data()).isInstanceOf(OperationalFailure.class);
        assertThat(((OperationalFailure) outcome.data()).kind())
                .isEqualTo(OperationalFailureKind.NOT_FOUND);
        assertThat(((OperationalFailure) outcome.data()).details())
                .doesNotContainKey("pnrProvided");
    }
}
