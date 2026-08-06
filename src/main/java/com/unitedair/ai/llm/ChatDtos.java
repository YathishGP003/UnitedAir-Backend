package com.unitedair.ai.llm;

import java.util.List;

import com.unitedair.ai.tools.ToolDtos;

/** Transport types for the chat gateway. */
public final class ChatDtos {

    private ChatDtos() { }

    /** One prior turn from ChatMemory. Content is already redacted. */
    public record HistoryTurn(String role, String content) { }

    /**
     * A single evidence block offered to the model.
     *
     * <p>{@code handle} is the citation marker ({@code E1}, {@code E2}, ...) the model is
     * instructed to cite. Offline generation uses {@code text} directly to compose an
     * extractive answer, which is why grounding travels as structured data rather than
     * being pre-flattened into the prompt string.
     */
    public record Grounding(String handle, String label, String text) { }

    public record ChatRequest(
            String systemPrompt,
            List<HistoryTurn> history,
            String userPrompt,
            List<Grounding> grounding) { }

    public record ChatResult(
            String text,
            Integer promptTokens,
            Integer completionTokens,
            String model,
            GenerationSource generationSource,
            String degradedReason) {

        public ChatResult(
                String text,
                Integer promptTokens,
                Integer completionTokens,
                String model,
                boolean live,
                String degradedReason) {
            this(
                    text,
                    promptTokens,
                    completionTokens,
                    model,
                    live ? GenerationSource.HOSTED_MODEL
                            : GenerationSource.GROUNDED_EXTRACTIVE,
                    degradedReason);
        }

        public boolean live() {
            return generationSource == GenerationSource.HOSTED_MODEL;
        }
    }

    public enum GenerationSource {
        HOSTED_MODEL,
        GROUNDED_EXTRACTIVE,
        STRUCTURED_TOOL,
        DETERMINISTIC_CONVERSATION
    }

    public record ToolProposalResult(
            List<ToolDtos.ValidatedToolCall> calls,
            String model,
            String degradedReason) {
        public ToolProposalResult {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }
    }
}
