package com.unitedair.ai.shared;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Correlates everything that happens while answering one turn.
 *
 * <p>SRS 4.1.4 requires that every tool invocation be logged against the session UUID for
 * end-to-end traceability. A session spans many turns, so a session UUID alone cannot tell
 * you which retrieval run produced which answer. Each turn therefore also gets a trace ID,
 * and the two together are what {@code GET /audit/{sessionId}} reconstructs.
 *
 * <p>Values are held in a thread-local and mirrored into SLF4J's MDC so log lines carry the
 * trace without every call site having to pass it. Virtual threads are per-request here, so
 * the thread-local does not leak between turns; {@link #clear()} runs in a filter's finally
 * block regardless.
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_UUID = new ThreadLocal<>();

    private TraceContext() { }

    public static String startTrace() {
        String traceId = UUID.randomUUID().toString();
        TRACE_ID.set(traceId);
        MDC.put("traceId", traceId);
        return traceId;
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
        MDC.put("traceId", traceId);
    }

    /** Returns the current trace ID, starting one if this turn has not begun a trace yet. */
    public static String traceId() {
        String existing = TRACE_ID.get();
        return existing != null ? existing : startTrace();
    }

    public static void setSessionUuid(String sessionUuid) {
        SESSION_UUID.set(sessionUuid);
        MDC.put("sessionUuid", sessionUuid);
    }

    public static String sessionUuid() {
        return SESSION_UUID.get();
    }

    public static void clear() {
        TRACE_ID.remove();
        SESSION_UUID.remove();
        MDC.remove("traceId");
        MDC.remove("sessionUuid");
    }
}
