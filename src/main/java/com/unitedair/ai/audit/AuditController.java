package com.unitedair.ai.audit;

import java.util.List;
import java.util.Map;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.KbCatalogRepository;
import com.unitedair.ai.knowledge.KbDtos;
import com.unitedair.ai.shared.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit and administration endpoints.
 *
 * <p>{@code GET /audit/{sessionId}} is available to the session's owner and to Staff and
 * Admin. A passenger being able to see why they were told something is the point of an
 * explainable assistant; FR-030 is why Staff can read any session.
 */
@RestController
@Tag(name = "Audit", description = "Session trails, escalations and ingestion jobs")
public class AuditController {

    private final AuditTrailRepository trails;
    private final KbCatalogRepository kbCatalog;
    private final CurrentUser currentUser;
    private final JdbcClient jdbc;
    private final OperationalDecisionService decisions;

    public AuditController(AuditTrailRepository trails,
                           KbCatalogRepository kbCatalog,
                           CurrentUser currentUser,
                           JdbcClient jdbc,
                           OperationalDecisionService decisions) {
        this.trails = trails;
        this.kbCatalog = kbCatalog;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.decisions = decisions;
    }

    /** SRS 5: full audit trail for a session. */
    @GetMapping("/audit/{sessionId}")
    @Operation(summary = "Query, retrieved chunks, tool calls, citations and escalation flag")
    public AuditDtos.SessionTrail sessionTrail(@PathVariable String sessionId) {
        CurrentUser.Authenticated user = currentUser.require();

        if (!user.role().atLeast(Role.AIRLINE_STAFF)) {
            Long ownerId = jdbc.sql("SELECT user_id FROM chat_session WHERE session_uuid = :s")
                    .param("s", sessionId).query(Long.class).optional().orElse(null);
            if (ownerId != null && !ownerId.equals(user.id())) {
                throw new ApiExceptions.Forbidden("This conversation belongs to another user.");
            }
        }

        return trails.trailFor(sessionId);
    }

    /** FR-030: staff querying the audit trail for approvals and overrides. */
    @GetMapping("/admin/escalations")
    @PreAuthorize("hasAnyRole('AIRLINE_STAFF','ADMIN')")
    @Operation(summary = "Escalation queue, most recent and open cases first")
    public List<AuditDtos.EscalationView> escalations(
            @RequestParam(defaultValue = "50") int limit) {
        return trails.openEscalations(Math.min(limit, 200));
    }

    @PostMapping("/admin/escalations/{caseUuid}/resolve")
    @PreAuthorize("hasAnyRole('AIRLINE_STAFF','ADMIN')")
    @Operation(summary = "Close an escalation case with a resolution note")
    public Map<String, String> resolve(@PathVariable String caseUuid,
                                       @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        trails.resolveEscalation(caseUuid, note);
        return Map.of("status", "RESOLVED", "caseUuid", caseUuid);
    }

    /** FR-032: the ingestion audit log. */
    @GetMapping("/admin/ingestion-jobs")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "KB ingestion jobs with admin, status, chunk counts and timings")
    public List<KbDtos.IngestionJobView> ingestionJobs(
            @RequestParam(defaultValue = "50") int limit) {
        return kbCatalog.listJobs(Math.min(limit, 200));
    }

    @PostMapping("/audit/decisions/search")
    @PreAuthorize("hasAnyRole('AIRLINE_STAFF','ADMIN')")
    @Operation(summary = "Search normalized operational approvals and overrides")
    public List<OperationalDecisionDtos.DecisionView> decisions(
            @RequestBody(required = false) OperationalDecisionDtos.DecisionQuery query) {
        return decisions.search(query);
    }

    @GetMapping("/audit/decisions/{decisionUuid}")
    @PreAuthorize("hasAnyRole('AIRLINE_STAFF','ADMIN')")
    @Operation(summary = "Inspect one normalized operational decision")
    public OperationalDecisionDtos.DecisionView decision(
            @PathVariable String decisionUuid) {
        return decisions.get(decisionUuid);
    }
}
