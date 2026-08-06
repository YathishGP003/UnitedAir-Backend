package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.unitedair.ai.actions.ActionDtos;
import com.unitedair.ai.commerce.CommerceDtos;
import com.unitedair.ai.conversation.ConversationDtos;
import com.unitedair.ai.conversation.SessionService;
import com.unitedair.ai.grounding.GroundingDtos;
import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.AiMode;
import com.unitedair.ai.llm.ChatDtos;
import org.junit.jupiter.api.Test;

class ChatControllerTest {

    @Test
    void synchronousResponseExposesToolsUsedByTheOrchestrator() {
        AgenticOrchestrator orchestrator = mock(AgenticOrchestrator.class);
        SessionService sessions = mock(SessionService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        AiMode aiMode = mock(AiMode.class);
        var user = new CurrentUser.Authenticated(
                7L, "passenger@unitedair.demo", "Passenger", Role.PASSENGER);
        when(currentUser.require()).thenReturn(user);
        when(sessions.require("session-1", user)).thenReturn(new ConversationDtos.SessionView(
                "session-1", "Test", Role.PASSENGER.name(), null, null, true, 0));
        when(sessions.actorRole("session-1")).thenReturn(Role.PASSENGER);
        when(aiMode.name()).thenReturn("LIVE");
        when(orchestrator.answer(
                anyString(), anyString(), any(Role.class), anyLong(), anyBoolean(), any()))
                .thenReturn(new GroundingDtos.GroundedAnswer(
                        "[AIR-PNR-REDACTED]: UA101 is available [T1]. "
                                + "\u00e2\u0080\u00a2 Carry photo ID.",
                        GroundingDtos.AnswerStatus.TOOL_GROUNDED,
                        false,
                        List.of(),
                        List.of("Would you like the fare options?"),
                        List.of("FlightSearchTool"),
                        1.0,
                        1.0,
                        0,
                        "TOOL_CALL",
                        "FAST",
                        "trace-1",
                        "session-1",
                        null,
                        new ActionDtos.ActionView(
                                "action-1", "CANCEL_BOOKING", "PENDING", "B6X9K2",
                                Map.of("action", "Cancel booking B6X9K2"),
                                List.of(), null, null, null, null)));

        var response = new ChatController(
                orchestrator, sessions, currentUser, aiMode)
                .sync(new OrchestrationDtos.ChatRequest(
                        "Find a flight", "session-1", false));

        assertThat(response.toolCalls())
                .extracting(OrchestrationDtos.ToolCallView::toolName)
                .containsExactly("FlightSearchTool");
        assertThat(response.toolCalls()).allMatch(OrchestrationDtos.ToolCallView::success);
        assertThat(response.proposedAction().actionUuid()).isEqualTo("action-1");
        assertThat(response.generationSource())
                .isEqualTo(ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION.name());
        assertThat(response.answer())
                .isEqualTo("Your booking: UA101 is available [T1]. \u2022 Carry photo ID.");
        assertThat(response.answer()).doesNotContain("AIR-PNR-REDACTED");
    }

    @Test
    void bookingTurnsComeFromTheOrchestratorWithTheCommerceContractIntact() {
        AgenticOrchestrator orchestrator = mock(AgenticOrchestrator.class);
        SessionService sessions = mock(SessionService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        AiMode aiMode = mock(AiMode.class);
        var user = new CurrentUser.Authenticated(
                7L, "passenger@unitedair.demo", "Passenger", Role.PASSENGER);
        var session = new ConversationDtos.SessionView(
                "session-1", "Test", Role.PASSENGER.name(), null, null, true, 0);
        var draft = new CommerceDtos.BookingDraftView(
                java.util.UUID.randomUUID(), CommerceDtos.DraftState.FLIGHTS_SHOWN,
                "BLR", "GOI", java.time.LocalDate.now().plusDays(2), "ECONOMY",
                null, null, null, null, null, 1,
                java.time.Instant.now().plusSeconds(1800));
        var payload = new CommerceDtos.CommercePayload(
                CommerceDtos.CommerceType.FLIGHT_OPTIONS,
                draft, List.of(), List.of(), null);
        when(currentUser.require()).thenReturn(user);
        when(sessions.require("session-1", user)).thenReturn(session);
        when(sessions.actorRole("session-1")).thenReturn(Role.PASSENGER);
        when(aiMode.name()).thenReturn("LIVE");
        when(orchestrator.answer(
                anyString(), anyString(), any(Role.class), anyLong(), anyBoolean(), any()))
                .thenReturn(new GroundingDtos.GroundedAnswer(
                        "I found one BLR to GOI flight [E1] [T1].",
                        GroundingDtos.AnswerStatus.TOOL_GROUNDED,
                        false,
                        List.of(
                                GroundingDtos.Citation.fromKb(
                                        "E1", "KB-AIR-001",
                                        "Flight Booking and Search",
                                        "Flight search", 1,
                                        "policy-manual", 1.0,
                                        "Booking workflow policy."),
                                GroundingDtos.Citation.fromTool(
                                        "T1", "FlightSearchTool",
                                        "SEARCH_FLIGHTS",
                                        "UNITEDAIR_SIMULATOR",
                                        "Verified flight inventory.",
                                        java.time.Instant.now())),
                        List.of(
                                "Would you like to compare baggage?",
                                "Would you like refundable fares?"),
                        List.of("FlightSearchTool"),
                        1.0,
                        1.0,
                        0,
                        "BOOK_FLIGHT",
                        "FAST",
                        "trace-1",
                        "session-1",
                        ChatDtos.GenerationSource.STRUCTURED_TOOL,
                        null,
                        null,
                        null,
                        payload));

        var response = new ChatController(
                orchestrator, sessions, currentUser, aiMode)
                .sync(new OrchestrationDtos.ChatRequest(
                        "BLR to GOI", "session-1", false));

        verify(orchestrator).answer(
                "BLR to GOI", "session-1", Role.PASSENGER,
                7L, false, null);
        assertThat(response.citations())
                .extracting(OrchestrationDtos.CitationView::documentCode)
                .contains("KB-AIR-001", "FlightSearchTool");
        assertThat(response.followups()).hasSize(2);
        assertThat(response.generationSource()).isEqualTo("STRUCTURED_TOOL");
        assertThat(response.commerce().type())
                .isEqualTo(CommerceDtos.CommerceType.FLIGHT_OPTIONS);
    }
}
