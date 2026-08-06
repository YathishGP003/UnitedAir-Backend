package com.unitedair.ai.operations;

import com.unitedair.ai.identity.Role;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;

/** Validates semantic query plans and attaches non-optional authorization context. */
@Component
public class OperationalQueryPolicy {

    static final String VALIDATION_MARKER = "UA_OPERATIONAL_QUERY_V1";
    private static final Pattern SAFE_ALIAS = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final SemanticDatasetCatalog catalog;

    public OperationalQueryPolicy(SemanticDatasetCatalog catalog) {
        this.catalog = catalog;
    }

    public ValidatedPlan validate(
            QueryPlan proposed,
            Role role,
            Long userId,
            QueryLimits limits) {
        if (proposed == null || proposed.queries().isEmpty()) {
            throw rejected("INVALID_PLAN", "At least one query is required.");
        }
        if (limits == null || proposed.queries().size() > limits.maxQueriesPerTurn()) {
            throw rejected("BUDGET_EXCEEDED", "Query count exceeds the turn budget.");
        }
        Role actorRole = role == null ? Role.PASSENGER : role;
        ArrayList<ValidatedQuery> validated = new ArrayList<>();
        for (DatasetQuery query : proposed.queries()) {
            validated.add(validateOne(query, actorRole, userId, limits));
        }
        return new ValidatedPlan(validated);
    }

    private ValidatedQuery validateOne(
            DatasetQuery query,
            Role role,
            Long userId,
            QueryLimits limits) {
        if (query == null || query.dataset() == null) {
            throw rejected("UNKNOWN_DATASET", "A valid dataset is required.");
        }
        SemanticDatasetCatalog.DatasetDescriptor descriptor =
                catalog.descriptor(query.dataset());
        if (!descriptor.roles().contains(role)) {
            throw rejected("ROLE_FORBIDDEN", "The role cannot read this dataset.");
        }
        if (role == Role.PASSENGER
                && descriptor.ownershipColumn() != null
                && userId == null) {
            throw rejected("OWNER_REQUIRED", "Authenticated ownership is required.");
        }
        if (query.select().isEmpty() && query.aggregates().isEmpty()) {
            throw rejected("INVALID_PLAN", "A selection or aggregate is required.");
        }
        for (String field : query.select()) {
            requireField(descriptor, field);
        }
        for (Filter filter : query.filters()) {
            SemanticDatasetCatalog.FieldDescriptor field =
                    requireField(descriptor, filter.field());
            if (filter.operator() == null || !field.operators().contains(filter.operator())) {
                throw rejected("FILTER_NOT_ALLOWED", "The filter operator is not allowed.");
            }
            int values = filter.values().size();
            if (values == 0
                    || (filter.operator() == Operator.BETWEEN && values != 2)
                    || (filter.operator() != Operator.IN
                        && filter.operator() != Operator.BETWEEN && values != 1)
                    || (filter.operator() == Operator.IN && values > 20)) {
                throw rejected("INVALID_PLAN", "The filter value count is invalid.");
            }
        }
        if (query.joins().size() > limits.maxJoinsPerQuery()) {
            throw rejected("BUDGET_EXCEEDED", "Join count exceeds the query budget.");
        }
        for (Join join : query.joins()) {
            if (join == null || !descriptor.allowedJoins().contains(join.dataset())) {
                throw rejected("JOIN_NOT_ALLOWED", "The requested join is not approved.");
            }
        }
        Set<String> aliases = new HashSet<>();
        for (Aggregate aggregate : query.aggregates()) {
            if (aggregate == null || aggregate.function() == null
                    || aggregate.alias() == null
                    || !SAFE_ALIAS.matcher(aggregate.alias()).matches()
                    || !aliases.add(aggregate.alias())) {
                throw rejected("AGGREGATE_NOT_ALLOWED", "The aggregate is invalid.");
            }
            if (!"*".equals(aggregate.field())) {
                SemanticDatasetCatalog.FieldDescriptor field =
                        requireField(descriptor, aggregate.field());
                if (!field.aggregatable()
                        && aggregate.function() != AggregateFunction.COUNT) {
                    throw rejected("AGGREGATE_NOT_ALLOWED",
                            "The field cannot use this aggregate.");
                }
            } else if (aggregate.function() != AggregateFunction.COUNT) {
                throw rejected("AGGREGATE_NOT_ALLOWED", "Only COUNT may use '*'.");
            }
        }
        for (String field : query.groupBy()) {
            requireField(descriptor, field);
        }
        for (Sort sort : query.sort()) {
            requireFieldOrAlias(descriptor, aliases, sort.field());
            if (sort.direction() == null) {
                throw rejected("INVALID_PLAN", "Sort direction is required.");
            }
        }
        int requestedLimit = query.limit() == null
                ? descriptor.defaultLimit() : query.limit();
        int maximum = Math.min(descriptor.maxLimit(), limits.maxRowsPerQuery());
        if (requestedLimit < 1 || requestedLimit > maximum) {
            throw rejected("BUDGET_EXCEEDED", "Row limit exceeds the query budget.");
        }
        DatasetQuery normalized = new DatasetQuery(
                query.dataset(), query.select(), query.filters(), query.joins(),
                query.aggregates(), query.groupBy(), query.sort(), requestedLimit);
        return new ValidatedQuery(normalized, role, userId, VALIDATION_MARKER);
    }

    private SemanticDatasetCatalog.FieldDescriptor requireField(
            SemanticDatasetCatalog.DatasetDescriptor descriptor,
            String name) {
        SemanticDatasetCatalog.FieldDescriptor field = descriptor.fields().get(name);
        if (field == null) {
            throw rejected("FIELD_NOT_ALLOWED", "The requested field is not approved.");
        }
        return field;
    }

    private void requireFieldOrAlias(
            SemanticDatasetCatalog.DatasetDescriptor descriptor,
            Set<String> aliases,
            String name) {
        if (!aliases.contains(name)) {
            requireField(descriptor, name);
        }
    }

    private static QueryRejectedException rejected(String code, String message) {
        return new QueryRejectedException(code, message);
    }

    public record QueryLimits(
            int maxQueriesPerTurn,
            int maxJoinsPerQuery,
            int maxRowsPerQuery) { }

    public static final class QueryRejectedException extends RuntimeException {
        private final String code;

        public QueryRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }

        public String getCode() {
            return code;
        }
    }
}
