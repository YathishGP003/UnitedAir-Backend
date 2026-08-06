package com.unitedair.ai.operations;

import com.unitedair.ai.identity.Role;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Strict business-level query AST. It intentionally contains no SQL fields. */
public final class OperationalQueryDtos {

    private OperationalQueryDtos() { }

    public enum Dataset {
        AIRPORTS, ROUTES, FLIGHT_SCHEDULES, FLIGHT_INSTANCES,
        FLIGHT_INVENTORY, SEAT_INVENTORY, BOOKINGS, CHECK_IN_STATE,
        MEAL_AVAILABILITY, SPECIAL_SERVICES, PAYMENT_STATUS,
        REFUND_CASES, REFUND_HISTORY, ESCALATIONS,
        OPERATIONAL_DECISIONS, AUDIT_EVENTS
    }

    public enum Operator { EQ, NE, IN, GT, GTE, LT, LTE, CONTAINS, BETWEEN }
    public enum AggregateFunction { COUNT, SUM, MIN, MAX, AVG }
    public enum Direction { ASC, DESC }

    public record Filter(String field, Operator operator, List<Object> values) {
        public Filter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record Sort(String field, Direction direction) { }
    public record Aggregate(AggregateFunction function, String field, String alias) { }
    public record Join(Dataset dataset, String leftField, String rightField) { }

    public record DatasetQuery(
            Dataset dataset,
            List<String> select,
            List<Filter> filters,
            List<Join> joins,
            List<Aggregate> aggregates,
            List<String> groupBy,
            List<Sort> sort,
            Integer limit) {
        public DatasetQuery {
            select = select == null ? List.of() : List.copyOf(select);
            filters = filters == null ? List.of() : List.copyOf(filters);
            joins = joins == null ? List.of() : List.copyOf(joins);
            aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
            groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
            sort = sort == null ? List.of() : List.copyOf(sort);
        }
    }

    public record QueryPlan(List<DatasetQuery> queries) {
        public QueryPlan {
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }

    public record ValidatedQuery(
            DatasetQuery query,
            Role role,
            Long userId,
            String validationId) { }

    public record ValidatedPlan(List<ValidatedQuery> queries) {
        public ValidatedPlan {
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }

    public record CompiledQuery(
            Dataset dataset,
            String sql,
            List<Object> parameters,
            int timeoutMs,
            String fingerprint) {
        public CompiledQuery {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    public record OperationalDataResult(
            Dataset dataset,
            List<Map<String, Object>> rows,
            Map<String, Object> effectiveFilters,
            Instant snapshotAt,
            int rowCount,
            boolean truncated,
            String queryFingerprint) {
        public OperationalDataResult {
            rows = rows == null ? List.of() : rows.stream().map(Map::copyOf).toList();
            effectiveFilters = effectiveFilters == null
                    ? Map.of() : Map.copyOf(effectiveFilters);
        }
    }
}
