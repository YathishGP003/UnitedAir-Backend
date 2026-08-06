package com.unitedair.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.SrsToolCallbackProvider;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

class ChatGatewayToolCallingTest {

    @SuppressWarnings("unchecked")
    @Test
    void sendsFourFamilyCallbacksWithInternalExecutionDisabledAndValidatesProposal() {
        ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        ObjectProvider<SrsToolCallbackProvider> providers = mock(ObjectProvider.class);
        ChatModel model = mock(ChatModel.class);
        SrsToolCallbackProvider provider = mock(SrsToolCallbackProvider.class);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(FlightSearchTool.NAME)
                .description("verified search")
                .inputSchema("{\"type\":\"object\"}")
                .build());
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[] { callback });
        when(providers.getIfAvailable()).thenReturn(provider);
        when(models.getIfAvailable()).thenReturn(model);
        when(models.getObject()).thenReturn(model);

        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        FlightSearchTool.NAME,
                        """
                        {"request":{"operation":"LIST_SUPPORTED_AIRPORTS","arguments":{}}}
                        """)))
                .build();
        when(model.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    assertThat(prompt.getOptions())
                            .isInstanceOf(ToolCallingChatOptions.class);
                    ToolCallingChatOptions options =
                            (ToolCallingChatOptions) prompt.getOptions();
                    assertThat(options.getInternalToolExecutionEnabled()).isFalse();
                    return new ChatResponse(List.of(new Generation(output)));
                });
        ToolDtos.ValidatedToolCall validated = new ToolDtos.ValidatedToolCall(
                FlightSearchTool.NAME,
                "LIST_SUPPORTED_AIRPORTS",
                Map.of(),
                Role.PASSENGER,
                "session",
                "trace");
        when(provider.validate(any())).thenReturn(List.of(validated));

        ChatGateway gateway = new ChatGateway(
                AiMode.LIVE, models, new HostedCallLimiter(1), providers);
        ChatDtos.ToolProposalResult result = gateway.proposeToolCalls(
                "Use only the registered tools.", List.of(), "Where do you fly?");

        assertThat(result.calls()).containsExactly(validated);
        verify(provider).validate(List.of(new ToolDtos.ProposedToolCall(
                FlightSearchTool.NAME,
                "LIST_SUPPORTED_AIRPORTS",
                Map.of())));
    }
}
