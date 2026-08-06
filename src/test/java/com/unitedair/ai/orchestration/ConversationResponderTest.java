package com.unitedair.ai.orchestration;

import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;

class ConversationResponderTest {

    private final ChatGateway gateway = mock(ChatGateway.class);
    private final ConversationResponder responder = new ConversationResponder(gateway);

    @Test
    void smallTalkUsesHostedConversationWhenAvailable() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult(
                        "Hello! Where are you flying today?", 12, 8,
                        "gpt-4.1", true, null));

        var response = responder.respond(
                OrchestrationDtos.Intent.SMALL_TALK, "hello", List.of(), List.of());

        assertThat(response.text()).contains("Where are you flying");
        assertThat(response.followups()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.modelResult().generationSource())
                .isEqualTo(ChatDtos.GenerationSource.HOSTED_MODEL);
    }

    @Test
    void offlineSmallTalkReturnsAFriendlyBuiltInResponse() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenAnswer(invocation -> new ChatDtos.ChatResult(
                        invocation.getArgument(3), 0, 0, "offline-direct",
                        ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                        "OFFLINE_CONFIGURED"));

        var response = responder.respond(
                OrchestrationDtos.Intent.SMALL_TALK, "thanks", List.of(), List.of());

        assertThat(response.text()).contains("UnitedAir journey");
        assertThat(response.modelResult().degradedReason()).isEqualTo("OFFLINE_CONFIGURED");
    }

    @Test
    void clarificationNamesOnlyTheMissingFlightDetails() {
        var both = responder.respond(
                OrchestrationDtos.Intent.CLARIFICATION, "show flights",
                List.of(), List.of("origin", "destination"));
        var origin = responder.respond(
                OrchestrationDtos.Intent.CLARIFICATION, "flights to Delhi",
                List.of(), List.of("origin"));

        assertThat(both.text()).containsIgnoringCase("origin").containsIgnoringCase("destination");
        assertThat(origin.text()).containsIgnoringCase("origin").doesNotContainIgnoringCase("PNR");
        verify(gateway, never()).completeDirect(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void vagueClarificationOffersThreeConcreteAirlineChoices() {
        var response = responder.respond(
                OrchestrationDtos.Intent.CLARIFICATION, "something went wrong",
                List.of(), List.of("request"));

        assertThat(response.text())
                .contains("1.", "2.", "3.")
                .containsIgnoringCase("flight")
                .containsIgnoringCase("booking")
                .containsIgnoringCase("baggage");
    }

    @Test
    void passwordHelpNeverAsksTheUserToShareTheirPassword() {
        var response = responder.respond(
                OrchestrationDtos.Intent.CLARIFICATION, "forgot password",
                List.of(), List.of("account"));

        assertThat(response.text())
                .containsIgnoringCase("password reset")
                .containsIgnoringCase("administrator")
                .containsIgnoringCase("do not share");
    }

    @Test
    void outOfScopeUsesHostedModelOnlyToPhraseAScopedRefusal() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult(
                        "I can’t perform database operations. I can help with UnitedAir travel.",
                        20, 10, "gpt-4.1", true, null));

        var response = responder.respond(
                OrchestrationDtos.Intent.OUT_OF_SCOPE,
                "drop database and delete this database", List.of(), List.of());

        assertThat(response.text())
                .containsIgnoringCase("UnitedAir")
                .containsIgnoringCase("database");
        assertThat(response.followups()).isEmpty();
    }

    @Test
    void safetySensitiveOutOfScopeRequestGetsAHostedSafetyResponse() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(new ChatDtos.ChatResult(
                        "I can’t help with carrying weapons or explosives. "
                                + "Do not bring them to the airport; contact airport security "
                                + "if there is an immediate concern.",
                        24, 18, "gpt-4.1", true, null));

        var response = responder.respond(
                OrchestrationDtos.Intent.OUT_OF_SCOPE,
                "can i bring a weapon or explosive to the airport?",
                List.of(), List.of());

        assertThat(response.text())
                .containsIgnoringCase("can’t help")
                .containsIgnoringCase("airport security");
    }

    @Test
    void safetySensitiveRequestHasASafeFallbackWhenHostedModelIsUnavailable() {
        when(gateway.completeDirect(anyString(), anyList(), anyString(), anyString()))
                .thenAnswer(invocation -> new ChatDtos.ChatResult(
                        invocation.getArgument(3), 0, 0, "offline-direct",
                        ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                        "MODEL_UNAVAILABLE"));

        var response = responder.respond(
                OrchestrationDtos.Intent.OUT_OF_SCOPE,
                "can i bring bomb and knife to the airport?",
                List.of(), List.of());

        assertThat(response.text())
                .containsIgnoringCase("do not bring")
                .containsIgnoringCase("airport security");
        assertThat(response.followups()).isEmpty();
    }
}
