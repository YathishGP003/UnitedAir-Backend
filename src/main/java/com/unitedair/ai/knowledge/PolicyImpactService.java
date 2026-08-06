package com.unitedair.ai.knowledge;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Evidence-based KB version and declared functional-requirement impact summary. */
@Service
public class PolicyImpactService {

    private static final List<String> BOUNDARIES = List.of(
            "FR-026: KB-AIR-009 provides approved WorldTracer, PIR, tracing-SLA and Montreal Convention "
                    + "guidance. Licensed vendor screens, credentials and final liability decisions "
                    + "remain restricted to authorised Airline Staff and governed escalation.");

    private final JdbcClient jdbc;

    public PolicyImpactService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PolicyImpactSummary current() {
        List<DocumentImpact> documents = jdbc.sql("""
                    SELECT d.document_code,
                           GROUP_CONCAT(DISTINCT v.serves_frs ORDER BY v.created_at SEPARATOR ', ') AS serves_frs,
                           COUNT(v.id) AS version_count
                    FROM kb_document d
                    LEFT JOIN kb_document_version v ON v.document_id = d.id
                    GROUP BY d.id, d.document_code
                    ORDER BY d.document_code
                """)
                .query((rs, row) -> new DocumentImpact(
                        rs.getString("document_code"),
                        rs.getString("serves_frs"),
                        rs.getInt("version_count")))
                .list();
        long inactiveCitations = jdbc.sql("""
                    SELECT COUNT(*)
                    FROM evidence_record e
                    JOIN kb_chunk c ON c.id = e.chunk_id
                    JOIN kb_document_version v ON v.id = c.version_id
                    WHERE e.used_in_answer = TRUE AND v.status <> 'ACTIVE'
                """).query(Long.class).single();
        return summarize(documents, inactiveCitations);
    }

    static PolicyImpactSummary summarize(List<DocumentImpact> rows, long inactiveCitationCount) {
        Set<String> requirements = new LinkedHashSet<>();
        rows.stream()
                .map(DocumentImpact::servesFrs)
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> Arrays.stream(value.split("[,;\\s]+")))
                .map(String::trim)
                .filter(value -> value.matches("FR-\\d{3}"))
                .forEach(requirements::add);
        return new PolicyImpactSummary(
                rows.size(),
                rows.stream().filter(row -> row.versionCount() > 1).count(),
                inactiveCitationCount,
                List.copyOf(requirements),
                rows,
                BOUNDARIES);
    }

    public record DocumentImpact(String documentCode, String servesFrs, int versionCount) { }

    public record PolicyImpactSummary(
            int documents,
            long versionedDocuments,
            long inactiveCitationCount,
            List<String> declaredFunctionalRequirements,
            List<DocumentImpact> documentImpact,
            List<String> authoritativeBoundaries) { }
}
