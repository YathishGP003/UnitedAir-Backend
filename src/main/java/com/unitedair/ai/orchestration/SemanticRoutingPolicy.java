package com.unitedair.ai.orchestration;

import com.unitedair.ai.llm.AiMode;
import org.springframework.stereotype.Component;

/**
 * Selects hosted semantic planning by runtime mode, never by message keywords.
 *
 * <p>Mandatory safety/security boundaries are enforced by the validated planner. In
 * live mode every other natural-language turn is interpreted by the hosted model.
 */
@Component
public class SemanticRoutingPolicy {

    private final AiMode aiMode;

    public SemanticRoutingPolicy(AiMode aiMode) {
        this.aiMode = aiMode;
    }

    public Eligibility evaluate(RoutingContext context) {
        if (!aiMode.isLive()) {
            return new Eligibility(false, false, "OFFLINE_MODE");
        }
        return new Eligibility(true, false, "LIVE_MODEL_FIRST");
    }

    public record Eligibility(
            boolean refine,
            boolean scopeSensitive,
            String reason) { }
}
