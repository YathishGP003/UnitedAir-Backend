package com.unitedair.ai.shared;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Privacy-safe quality and provider-use aggregates over the latest 200 answers. */
@Service
public class QualityMetricsService {

    private final JdbcClient jdbc;

    public QualityMetricsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public QualitySummary current() {
        List<QualityRow> rows = jdbc.sql("""
                    SELECT ai_mode, degraded_reason, status, confidence, citation_coverage,
                           duration_ms, repair_attempts
                    FROM answer_record
                    ORDER BY created_at DESC
                    LIMIT 200
                """)
                .query((rs, row) -> new QualityRow(
                        rs.getString("ai_mode"),
                        rs.getString("degraded_reason"),
                        rs.getString("status"),
                        nullableDouble(rs.getObject("confidence")),
                        nullableDouble(rs.getObject("citation_coverage")),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                        rs.getInt("repair_attempts")))
                .list();
        long escalations = jdbc.sql("""
                    SELECT COUNT(*) FROM escalation_case
                    WHERE created_at >= DATE_SUB(UTC_TIMESTAMP(), INTERVAL 30 DAY)
                """).query(Long.class).single();
        return summarize(rows, escalations);
    }

    static QualitySummary summarize(List<QualityRow> rows, long escalations) {
        int total = rows.size();
        long rateLimited = rows.stream()
                .filter(row -> "RATE_LIMIT".equals(row.degradedReason())).count();
        long toolOnly = rows.stream()
                .filter(row -> "TOOL_GROUNDED".equals(row.status())).count();
        long hosted = rows.stream()
                .filter(row -> "LIVE".equals(row.aiMode()))
                .filter(row -> row.degradedReason() == null)
                .filter(row -> !"TOOL_GROUNDED".equals(row.status()))
                .count();
        long repairs = rows.stream().mapToLong(QualityRow::repairAttempts).sum();
        return new QualitySummary(
                total, hosted, rateLimited, toolOnly,
                total == 0 ? 0 : (double) rateLimited / total,
                average(rows.stream().map(QualityRow::confidence).toList()),
                average(rows.stream().map(QualityRow::citationCoverage).toList()),
                averageLong(rows.stream().map(QualityRow::durationMs).toList()),
                escalations, repairs);
    }

    private static double average(List<Double> values) {
        return values.stream().filter(value -> value != null)
                .mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double averageLong(List<Long> values) {
        return values.stream().filter(value -> value != null)
                .mapToLong(Long::longValue).average().orElse(0);
    }

    private static Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    public record QualityRow(
            String aiMode,
            String degradedReason,
            String status,
            Double confidence,
            Double citationCoverage,
            Long durationMs,
            int repairAttempts) { }

    public record QualitySummary(
            int windowAnswers,
            long hostedCompletions,
            long rateLimitFallbacks,
            long toolOnlyAnswers,
            double fallbackRate,
            double averageConfidence,
            double averageCitationCoverage,
            double averageDurationMs,
            long escalations,
            long validationRepairs) { }
}
