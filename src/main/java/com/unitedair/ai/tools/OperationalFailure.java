package com.unitedair.ai.tools;

import java.util.Map;

/** Typed, user-safe result for an operational tool call that could not be completed. */
public record OperationalFailure(
        String toolFamily,
        String operation,
        OperationalFailureKind kind,
        String userMessage,
        Map<String, Object> details) {

    public OperationalFailure {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
