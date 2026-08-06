package com.unitedair.ai.tools;

/** Stable failure categories shared by tools, orchestration and the UI. */
public enum OperationalFailureKind {
    UNSUPPORTED_AIRPORT,
    NO_ROUTE,
    NO_INVENTORY,
    NOT_FOUND,
    VALIDATION,
    UNAVAILABLE,
    UNAUTHORIZED
}
