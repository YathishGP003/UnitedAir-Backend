package com.unitedair.ai.shared;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import com.unitedair.ai.llm.AiMode;
import com.unitedair.ai.providers.BookingProvider;
import com.unitedair.ai.providers.CheckInProvider;
import com.unitedair.ai.providers.FlightProvider;
import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.providers.StatusProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Small, read-only operational summaries. No passenger contact data is returned. */
@RestController
public class OperationsController {

    private final JdbcClient jdbc;
    private final AiMode aiMode;
    private final QualityMetricsService qualityMetrics;
    private final List<ProviderCapability> providerCapabilities;

    public OperationsController(JdbcClient jdbc, AiMode aiMode) {
        this(jdbc, aiMode, new QualityMetricsService(jdbc),
                List.of(), List.of(), List.of(), List.of());
    }

    @Autowired
    public OperationsController(
            JdbcClient jdbc,
            AiMode aiMode,
            QualityMetricsService qualityMetrics,
            List<FlightProvider> flights,
            List<BookingProvider> bookings,
            List<StatusProvider> statuses,
            List<CheckInProvider> checkIns) {
        this.jdbc = jdbc;
        this.aiMode = aiMode;
        this.qualityMetrics = qualityMetrics;
        this.providerCapabilities = Stream.of(
                        flights.stream().map(FlightProvider::capability),
                        bookings.stream().map(BookingProvider::capability),
                        statuses.stream().map(StatusProvider::capability),
                        checkIns.stream().map(CheckInProvider::capability))
                .flatMap(stream -> stream)
                .distinct()
                .toList();
    }

    public OperationsController(
            JdbcClient jdbc, AiMode aiMode, QualityMetricsService qualityMetrics) {
        this(jdbc, aiMode, qualityMetrics,
                List.of(), List.of(), List.of(), List.of());
    }

    @GetMapping("/admin/operations/summary")
    @PreAuthorize("hasAnyRole('AIRLINE_STAFF','ADMIN')")
    public OperationsSummary operationsSummary() {
        FlightTotals flights = jdbc.sql("""
                    SELECT COUNT(*) total,
                           SUM(status = 'DELAYED') delayed_count,
                           SUM(status = 'CANCELLED') cancelled_count
                    FROM sim_flight_instance
                    WHERE flight_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 2 DAY)
                """)
                .query((rs, row) -> new FlightTotals(
                        rs.getLong("total"),
                        rs.getLong("delayed_count"),
                        rs.getLong("cancelled_count")))
                .single();

        List<QueueCount> queues = jdbc.sql("""
                    SELECT target_queue, COUNT(*) case_count
                    FROM escalation_case
                    WHERE status = 'OPEN'
                    GROUP BY target_queue
                    ORDER BY case_count DESC, target_queue
                """)
                .query((rs, row) -> new QueueCount(
                        rs.getString("target_queue"), rs.getLong("case_count")))
                .list();

        List<FareRule> fareRules = jdbc.sql("""
                    SELECT fare_class, cabin, fare_brand, refundable, changeable,
                           change_fee_inr, cancel_fee_inr, ffp_accrual_pct
                    FROM sim_fare
                    GROUP BY fare_class, cabin, fare_brand, refundable, changeable,
                             change_fee_inr, cancel_fee_inr, ffp_accrual_pct
                    ORDER BY cabin, MIN(id)
                """)
                .query((rs, row) -> new FareRule(
                        rs.getString("fare_class"),
                        rs.getString("fare_brand"),
                        rs.getString("cabin"),
                        rs.getBoolean("changeable"),
                        rs.getBoolean("refundable"),
                        rs.getBigDecimal("change_fee_inr"),
                        rs.getBigDecimal("cancel_fee_inr"),
                        rs.getInt("ffp_accrual_pct")))
                .list();

        return new OperationsSummary(
                flights.total(), flights.delayed(), flights.cancelled(), queues, fareRules);
    }

    @GetMapping("/admin/health/details")
    @PreAuthorize("hasRole('ADMIN')")
    public HealthDetails healthDetails() {
        long chunks = jdbc.sql("SELECT COUNT(*) FROM kb_chunk").query(Long.class).single();
        long documents = jdbc.sql("SELECT COUNT(*) FROM kb_document").query(Long.class).single();
        long failedJobs = jdbc.sql("""
                    SELECT COUNT(*) FROM kb_ingestion_job WHERE status = 'FAILED'
                """).query(Long.class).single();
        return new HealthDetails("UP", aiMode.name(), chunks, documents, failedJobs);
    }

    @GetMapping("/admin/quality/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public QualityMetricsService.QualitySummary qualitySummary() {
        return qualityMetrics.current();
    }

    @GetMapping("/admin/providers")
    @PreAuthorize("hasAnyRole('AIRLINE_STAFF','ADMIN')")
    public List<ProviderCapability> providers() {
        return providerCapabilities;
    }

    public record QueueCount(String queue, long count) { }
    public record FareRule(
            String code,
            String brand,
            String cabin,
            boolean changeable,
            boolean refundable,
            BigDecimal changeFee,
            BigDecimal cancelFee,
            int ffpAccrualPct) { }
    public record OperationsSummary(
            long departures,
            long delayed,
            long cancelled,
            List<QueueCount> openEscalations,
            List<FareRule> fareRules) { }
    public record HealthDetails(
            String database,
            String aiMode,
            long indexedPassages,
            long documents,
            long failedIngestions) { }
    private record FlightTotals(long total, long delayed, long cancelled) { }
}
