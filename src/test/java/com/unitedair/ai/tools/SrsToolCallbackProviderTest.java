package com.unitedair.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SrsToolCallbackProviderTest {

    @Test
    void exposesExactlyTheFourSrsToolFamilies() {
        Fixture fixture = fixture(Role.PASSENGER);

        assertThat(fixture.provider().getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrderElementsOf(SrsToolCallbackProvider.FAMILIES);
    }

    @Test
    void rejectsUnknownFamilyOperationMissingExtraAndOverBudgetCalls() {
        Fixture fixture = fixture(Role.PASSENGER);

        assertThatThrownBy(() -> fixture.provider().validate(List.of(
                call("SqlTool", "QUERY", Map.of()))))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("Unknown tool family");
        assertThatThrownBy(() -> fixture.provider().validate(List.of(
                call(BookingManagementTool.NAME, "CONFIRM_CANCELLATION",
                        Map.of("pnr", "ABC123")))))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("direct-mutation");
        assertThatThrownBy(() -> fixture.provider().validate(List.of(
                call(FlightSearchTool.NAME, "SEARCH_FLIGHTS",
                        Map.of("origin", "BLR", "destination", "GOI")))))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("departureDate");
        assertThatThrownBy(() -> fixture.provider().validate(List.of(
                call(FlightSearchTool.NAME, "LIST_SUPPORTED_AIRPORTS",
                        Map.of("sql", "DROP TABLE booking")))))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("Unexpected arguments");

        ToolDtos.ProposedToolCall safe = call(
                FlightSearchTool.NAME, "LIST_SUPPORTED_AIRPORTS", Map.of());
        assertThatThrownBy(() -> fixture.provider().validate(
                List.of(safe, safe, safe, safe)))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("at most 3");
    }

    @Test
    void rejectsCrossPassengerPnrBeforeExecution() {
        Fixture fixture = fixture(Role.PASSENGER);
        when(fixture.repository().findByPnr(any(), any(BookingAccess.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.provider().validate(List.of(
                call(BookingManagementTool.NAME, "RETRIEVE_BOOKING",
                        Map.of("pnr", "ABC123")))))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("owned by the authenticated passenger");
    }

    @Test
    void onlyApprovedFacadeSourceFilesEndWithTool() throws Exception {
        Path source = Path.of("src/main/java/com/unitedair/ai");
        Set<String> toolClasses;
        try (var files = Files.walk(source)) {
            toolClasses = files
                    .filter(path -> path.getFileName().toString().endsWith("Tool.java"))
                    .map(path -> path.getFileName().toString().replace(".java", ""))
                    .filter(name -> !Set.of(
                            "ToolController",
                            "ToolInvocationLogger",
                            "ToolOrchestrator",
                            "ToolAnswerComposer").contains(name))
                    .collect(java.util.stream.Collectors.toSet());
        }
        assertThat(toolClasses).containsExactlyInAnyOrderElementsOf(
                SrsToolCallbackProvider.FAMILIES);
    }

    private static ToolDtos.ProposedToolCall call(
            String family, String operation, Map<String, Object> arguments) {
        return new ToolDtos.ProposedToolCall(family, operation, arguments);
    }

    private static Fixture fixture(Role role) {
        SimulatorRepository simulator = mock(SimulatorRepository.class);
        BookingRepository repository = mock(BookingRepository.class);
        ToolInvocationLogger logger = mock(ToolInvocationLogger.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        CurrentUser.Authenticated actor =
                new CurrentUser.Authenticated(7L, "user@example.test", "User", role);
        when(currentUser.require()).thenReturn(actor);
        when(currentUser.role()).thenReturn(role);

        FlightSearchTool flights =
                new FlightSearchTool(simulator, repository, logger, currentUser);
        BookingManagementTool bookings =
                new BookingManagementTool(repository, logger);
        CheckInStatusTool checkInStatus = new CheckInStatusTool(
                mock(JdbcClient.class), repository, bookings, logger, currentUser, null);
        EscalationTool escalations = new EscalationTool(
                mock(JdbcClient.class), logger, currentUser);
        UnitedAirProperties properties = new UnitedAirProperties();
        properties.getRag().setMaxToolCallsPerRequest(3);

        return new Fixture(
                new SrsToolCallbackProvider(
                        flights, bookings, checkInStatus, escalations,
                        currentUser, logger, properties),
                repository);
    }

    private record Fixture(
            SrsToolCallbackProvider provider,
            BookingRepository repository) { }
}
