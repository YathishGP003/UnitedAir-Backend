package com.unitedair.ai.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;

/** Plans and executes flexible, governed, read-only questions over semantic data. */
@Component
public class OperationalDataAgent {

    private static final Logger log = LoggerFactory.getLogger(OperationalDataAgent.class);
    private static final Set<String> ROOT_FIELDS = Set.of("queries");
    private static final Set<String> QUERY_FIELDS = Set.of(
            "dataset", "select", "filters", "joins", "aggregates",
            "groupBy", "sort", "limit");
    private static final Set<String> FILTER_FIELDS =
            Set.of("field", "operator", "values");
    private static final Set<String> JOIN_FIELDS =
            Set.of("dataset", "leftField", "rightField");
    private static final Set<String> AGGREGATE_FIELDS =
            Set.of("function", "field", "alias");
    private static final Set<String> SORT_FIELDS =
            Set.of("field", "direction");
    private static final String SYSTEM_PROMPT = """
            Convert one in-scope UnitedAir read-only operational question into a
            strict JSON query plan. Use only the supplied semantic datasets and fields.
            Return exactly {"queries":[...]} with query keys dataset, select, filters,
            joins, aggregates, groupBy, sort, limit. Filters use field, operator, values.
            Never output SQL, table names, write operations, credentials, or commentary.
            """;

    private final ChatGateway gateway;
    private final SemanticDatasetCatalog catalog;
    private final OperationalQueryPolicy policy;
    private final OperationalQueryCompiler compiler;
    private final OperationalQueryExecutor executor;
    private final UnitedAirProperties properties;
    private final AuditService audit;

    public OperationalDataAgent(
            ChatGateway gateway,
            SemanticDatasetCatalog catalog,
            OperationalQueryPolicy policy,
            OperationalQueryCompiler compiler,
            OperationalQueryExecutor executor,
            UnitedAirProperties properties,
            AuditService audit) {
        this.gateway = gateway;
        this.catalog = catalog;
        this.policy = policy;
        this.compiler = compiler;
        this.executor = executor;
        this.properties = properties;
        this.audit = audit;
    }

    public AgentResult answer(AgentRequest request) {
        String catalogPrompt = compactCatalog(request.role());
        ChatDtos.ChatResult hosted = gateway.completeDirect(
                SYSTEM_PROMPT, List.of(),
                "Semantic catalog:\n" + catalogPrompt
                        + "\nRedacted question:\n" + request.redactedQuery(),
                "");
        QueryPlan proposed = hosted.live() ? parse(hosted.text()) : null;
        String degradation = hosted.live() ? null
                : safeDegradationCode(hosted.degradedReason());
        if (proposed == null) {
            degradation = hosted.live()
                    ? "INVALID_SEMANTIC_PLAN" : degradation;
            proposed = localTemplate(request);
        }
        if (proposed == null) {
            audit.recordOperationalQuery(
                    "OPERATIONAL_QUERY_REJECTED",
                    request.role().name(), request.userId(),
                    "PLAN", "", 0L, 0, false,
                    degradation);
            return new AgentResult(
                    List.of(),
                    hosted.live() ? Status.REJECTED : Status.DEGRADED,
                    degradation);
        }
        proposed = addTrustedPnr(proposed, request.trustedPnr());
        try {
            int availableBudget = Math.max(1, Math.min(
                    request.remainingQueryBudget(),
                    properties.getOperationalQuery().getMaxQueriesPerTurn()));
            ValidatedPlan validated = policy.validate(
                    proposed,
                    request.role(),
                    request.userId(),
                    new OperationalQueryPolicy.QueryLimits(
                            availableBudget,
                            properties.getOperationalQuery().getMaxJoinsPerQuery(),
                            properties.getOperationalQuery().getMaxRowLimit()));
            ArrayList<OperationalDataResult> results = new ArrayList<>();
            for (ValidatedQuery query : validated.queries()) {
                CompiledQuery compiled = compiler.compile(query);
                audit.recordOperationalQuery(
                        "OPERATIONAL_QUERY_VALIDATED",
                        request.role().name(), request.userId(),
                        compiled.dataset().name(), compiled.fingerprint(),
                        0L, 0, false, degradation);
                long queryStarted = System.nanoTime();
                OperationalDataResult result = executor.execute(compiled);
                long durationMs = Math.max(
                        0L, (System.nanoTime() - queryStarted) / 1_000_000L);
                results.add(result);
                audit.recordOperationalQuery(
                        "OPERATIONAL_QUERY_EXECUTED",
                        request.role().name(), request.userId(),
                        result.dataset().name(), result.queryFingerprint(),
                        durationMs, result.rowCount(), result.truncated(), degradation);
            }
            return new AgentResult(results, Status.EXECUTED, degradation);
        } catch (OperationalQueryPolicy.QueryRejectedException rejected) {
            log.debug("Operational query rejected: {}", rejected.code());
            audit.recordOperationalQuery(
                    "OPERATIONAL_QUERY_REJECTED",
                    request.role().name(), request.userId(),
                    "PLAN", "", 0L, 0, false, rejected.code());
            return new AgentResult(List.of(), Status.REJECTED, rejected.code());
        } catch (RuntimeException invalid) {
            log.warn("Operational query did not execute: {}", invalid.toString());
            audit.recordOperationalQuery(
                    "OPERATIONAL_QUERY_REJECTED",
                    request.role().name(), request.userId(),
                    "PLAN", "", 0L, 0, false, "QUERY_EXECUTION_FAILED");
            return new AgentResult(List.of(), Status.REJECTED, "QUERY_EXECUTION_FAILED");
        }
    }

    private QueryPlan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = Json.mapper().readTree(raw.trim());
            if (!root.isObject() || !exactFields(root, ROOT_FIELDS)
                    || !root.path("queries").isArray()) {
                return null;
            }
            for (JsonNode query : root.path("queries")) {
                if (!query.isObject() || !exactFields(query, QUERY_FIELDS)
                        || !validateArrayObjects(query.path("filters"), FILTER_FIELDS)
                        || !validateArrayObjects(query.path("joins"), JOIN_FIELDS)
                        || !validateArrayObjects(query.path("aggregates"), AGGREGATE_FIELDS)
                        || !validateArrayObjects(query.path("sort"), SORT_FIELDS)) {
                    return null;
                }
            }
            return Json.mapper().treeToValue(root, QueryPlan.class);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static boolean validateArrayObjects(JsonNode array, Set<String> fields) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode value : array) {
            if (!value.isObject() || !exactFields(value, fields)) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactFields(JsonNode node, Set<String> expected) {
        HashSet<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private QueryPlan addTrustedPnr(QueryPlan plan, String trustedPnr) {
        if (trustedPnr == null || trustedPnr.isBlank()) {
            return plan;
        }
        ArrayList<DatasetQuery> queries = new ArrayList<>();
        for (DatasetQuery query : plan.queries()) {
            if (!catalog.descriptor(query.dataset()).fields().containsKey("pnr")) {
                queries.add(query);
                continue;
            }
            ArrayList<Filter> filters = new ArrayList<>(query.filters());
            filters.removeIf(filter -> "pnr".equals(filter.field()));
            filters.add(new Filter("pnr", Operator.EQ,
                    List.of(trustedPnr.trim().toUpperCase(Locale.ROOT))));
            queries.add(new DatasetQuery(
                    query.dataset(), query.select(), filters, query.joins(),
                    query.aggregates(), query.groupBy(), query.sort(), query.limit()));
        }
        return new QueryPlan(queries);
    }

    private QueryPlan localTemplate(AgentRequest request) {
        String lower = request.redactedQuery() == null
                ? "" : request.redactedQuery().toLowerCase(Locale.ROOT);
        ArrayList<DatasetQuery> queries = new ArrayList<>();
        if (lower.contains("refund")
                && (lower.contains("status") || lower.contains("progress")
                    || lower.contains("pending") || lower.contains("completed"))) {
            List<Filter> filters = lower.contains("pending")
                    ? List.of(new Filter(
                            "status", Operator.IN,
                            List.of("PENDING", "PROCESSING")))
                    : List.of();
            queries.add(new DatasetQuery(
                    Dataset.REFUND_CASES,
                    List.of("caseReference", "pnr", "status", "refundAmountInr",
                            "dueAt", "updatedAt", "completedAt"),
                    filters, List.of(), List.of(), List.of(),
                    List.of(new Sort("updatedAt", Direction.DESC)), 20));
        }
        if (lower.contains("escalation") && request.role().atLeast(Role.AIRLINE_STAFF)) {
            queries.add(new DatasetQuery(
                    Dataset.ESCALATIONS,
                    List.of("caseReference", "reason", "priority", "status", "createdAt"),
                    List.of(new Filter("status", Operator.IN,
                            List.of("OPEN", "ACKNOWLEDGED"))),
                    List.of(), List.of(), List.of(),
                    List.of(new Sort("createdAt", Direction.ASC)), 20));
        }
        if (queries.isEmpty() && lower.contains("booking")) {
            queries.add(new DatasetQuery(
                    Dataset.BOOKINGS,
                    List.of("pnr", "status", "flightNo", "origin", "destination",
                            "flightDate", "fareBrand", "seatNumber"),
                    List.of(), List.of(), List.of(), List.of(),
                    List.of(new Sort("flightDate", Direction.ASC)), 20));
        }
        return queries.isEmpty() ? null : new QueryPlan(queries);
    }

    private String compactCatalog(Role role) {
        StringBuilder text = new StringBuilder();
        for (Dataset dataset : catalog.datasets().stream()
                .sorted().toList()) {
            var descriptor = catalog.descriptor(dataset);
            if (!descriptor.roles().contains(role)) {
                continue;
            }
            text.append(dataset.name()).append(": ")
                    .append(String.join(",", descriptor.fields().keySet()))
                    .append('\n');
        }
        return text.toString();
    }

    private static String safeDegradationCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SEMANTIC_PLANNER_UNAVAILABLE";
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("429") || upper.contains("RATE_LIMIT")) {
            return upper.contains("HTTP_429") ? "HTTP_429" : "RATE_LIMIT";
        }
        if (upper.contains("TIMEOUT") || upper.contains("TIMED_OUT")) {
            return "TIMEOUT";
        }
        if (upper.contains("OFFLINE")) {
            return "OFFLINE_CONFIGURED";
        }
        if (upper.contains("INVALID")) {
            return "INVALID_SEMANTIC_PLAN";
        }
        return "SEMANTIC_PLANNER_UNAVAILABLE";
    }

    public record AgentRequest(
            String redactedQuery,
            Role role,
            Long userId,
            String trustedPnr,
            int remainingQueryBudget) { }

    public record AgentResult(
            List<OperationalDataResult> results,
            Status status,
            String degradedReason) {
        public AgentResult {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    public enum Status { EXECUTED, DEGRADED, REJECTED }
}
