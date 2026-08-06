package com.unitedair.ai.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class OperationalDecisionDtos {

    private OperationalDecisionDtos() { }

    public enum DecisionType {
        REFUND_APPROVAL,
        UPGRADE_AUTHORIZATION,
        BOARDING_OVERRIDE,
        SPECIAL_SERVICE_EXCEPTION
    }

    public record DecisionQuery(
            Set<DecisionType> types,
            String pnr,
            Long decidedBy,
            Instant from,
            Instant to,
            String outcome) { }

    public record DecisionView(
            String decisionUuid,
            String actionUuid,
            DecisionType decisionType,
            String outcome,
            Long actorUserId,
            String actorRole,
            String pnrDisplay,
            String reason,
            String sourcePolicyCode,
            String sourcePolicySection,
            String traceId,
            String sessionUuid,
            Map<String, Object> detail,
            Instant createdAt,
            Instant decidedAt) { }
}

