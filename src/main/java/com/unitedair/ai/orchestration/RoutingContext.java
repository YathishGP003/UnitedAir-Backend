package com.unitedair.ai.orchestration;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.ChatDtos;

import java.util.List;

/** Immutable, redacted input to semantic route selection. */
public record RoutingContext(
        String currentQuery,
        String standaloneQuery,
        List<ChatDtos.HistoryTurn> history,
        boolean historyRelevant,
        TrustedConversationState trustedState,
        Role actorRole,
        Long userId,
        OrchestrationDtos.Classification deterministic) {

    public RoutingContext {
        history = history == null ? List.of() : List.copyOf(history);
        trustedState = trustedState == null
                ? TrustedConversationState.empty()
                : trustedState;
    }
}
