-- ===========================================================================
--  V9 - Tool request trace enrichment
--
--  The base tool_invocation row records that a call happened. To render the
--  trace panel in the UI and to answer FR-030 (staff querying the audit trail
--  for overrides and approvals) we also need to know who the call was made on
--  behalf of, which attempt it was, and how it relates to a retry.
-- ===========================================================================

ALTER TABLE tool_invocation
    ADD COLUMN actor_role           VARCHAR(32) NULL      AFTER tool_name,
    ADD COLUMN attempt_no           INT         NOT NULL DEFAULT 1 AFTER actor_role,
    ADD COLUMN parent_invocation_id BIGINT      NULL      AFTER attempt_no,
    -- Which agentic pattern dispatched this call, for the trace visualisation:
    -- ROUTING | ORCHESTRATOR_WORKER | PARALLELIZATION | EVALUATOR_OPTIMIZER | CHAIN
    ADD COLUMN dispatch_pattern     VARCHAR(32) NULL      AFTER parent_invocation_id,
    ADD KEY idx_tool_parent (parent_invocation_id),
    ADD CONSTRAINT fk_tool_parent
        FOREIGN KEY (parent_invocation_id) REFERENCES tool_invocation (id) ON DELETE SET NULL;

-- Convenience view backing GET /audit/{sessionId}: one row per tool call with
-- the timing already computed.
CREATE OR REPLACE VIEW v_tool_trace AS
SELECT ti.id,
       ti.session_uuid,
       ti.trace_id,
       ti.tool_name,
       ti.actor_role,
       ti.attempt_no,
       ti.dispatch_pattern,
       ti.status,
       ti.duration_ms,
       ti.error_message,
       ti.invoked_at,
       ti.completed_at
FROM tool_invocation ti;
