package com.unitedair.ai.orchestration;

import java.util.List;
import java.util.Set;

/** Fully validated route used by orchestration; hosted output cannot bypass this type. */
public record ValidatedRoute(
        OrchestrationDtos.Classification primary,
        List<OrchestrationDtos.ToolTarget> tools,
        Scope scope,
        boolean useHistory,
        Source source,
        double semanticConfidence,
        double ambiguity,
        Set<AnswerRequirements.RequestedTopic> topics,
        ActionDecision actionDecision,
        String rationale,
        String degradationReason) {

    public ValidatedRoute {
        tools = tools == null ? List.of() : List.copyOf(tools);
        topics = topics == null ? Set.of() : Set.copyOf(topics);
        actionDecision = actionDecision == null
                ? ActionDecision.NONE : actionDecision;
    }

    public static ValidatedRoute deterministic(
            RoutingContext context,
            String degradationReason) {
        OrchestrationDtos.Classification classification = context.deterministic();
        boolean executableToolIntent =
                classification.intent() == OrchestrationDtos.Intent.TOOL_CALL
                        || classification.intent()
                                == OrchestrationDtos.Intent.TOOL_PLUS_KB;
        List<OrchestrationDtos.ToolTarget> tools =
                executableToolIntent && classification.needsTool()
                ? List.of(classification.tool()) : List.of();
        Scope scope = classification.intent() == OrchestrationDtos.Intent.OUT_OF_SCOPE
                ? Scope.OUT_OF_SCOPE
                : classification.intent() == OrchestrationDtos.Intent.CLARIFICATION
                        ? Scope.AMBIGUOUS : Scope.IN_SCOPE;
        return new ValidatedRoute(
                classification,
                tools,
                scope,
                context.historyRelevant(),
                Source.DETERMINISTIC,
                classification.confidence(),
                scope == Scope.AMBIGUOUS ? 1.0 : 0.0,
                AnswerRequirements.from(
                        context.currentQuery(), classification).topics(),
                ActionDecision.NONE,
                classification.rationale(),
                degradationReason);
    }

    public enum Scope { IN_SCOPE, OUT_OF_SCOPE, AMBIGUOUS }
    public enum Source { DETERMINISTIC, HOSTED_SEMANTIC }
    public enum ActionDecision { NONE, PROPOSE, QUOTE_ONLY, CONFIRM, REJECT }
}
