package com.unitedair.ai.operations;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;

/** Compiles only policy-validated business ASTs into parameterized SELECT statements. */
@Component
public class OperationalQueryCompiler {

    private final SemanticDatasetCatalog catalog;

    public OperationalQueryCompiler(SemanticDatasetCatalog catalog) {
        this.catalog = catalog;
    }

    public CompiledQuery compile(ValidatedQuery validated) {
        if (validated == null
                || !OperationalQueryPolicy.VALIDATION_MARKER.equals(
                        validated.validationId())) {
            throw new IllegalArgumentException("Query was not validated by policy.");
        }
        DatasetQuery query = validated.query();
        SemanticDatasetCatalog.DatasetDescriptor descriptor =
                catalog.descriptor(query.dataset());
        ArrayList<Object> parameters = new ArrayList<>();
        ArrayList<String> selections = new ArrayList<>();
        for (String fieldName : query.select()) {
            String column = descriptor.fields().get(fieldName).column();
            selections.add("t." + column + " AS " + fieldName);
        }
        for (Aggregate aggregate : query.aggregates()) {
            String argument = "*".equals(aggregate.field())
                    ? "*" : "t." + descriptor.fields().get(aggregate.field()).column();
            selections.add(aggregate.function().name()
                    + "(" + argument + ") AS " + aggregate.alias());
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selections))
                .append(" FROM ").append(descriptor.viewName()).append(" t");
        ArrayList<String> predicates = new ArrayList<>();
        if (validated.role() == com.unitedair.ai.identity.Role.PASSENGER
                && descriptor.ownershipColumn() != null) {
            predicates.add("t." + descriptor.ownershipColumn() + " = ?");
            parameters.add(validated.userId());
        }
        for (Filter filter : query.filters()) {
            String column = "t." + descriptor.fields().get(filter.field()).column();
            predicates.add(compileFilter(column, filter, parameters));
        }
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        if (!query.groupBy().isEmpty()) {
            sql.append(" GROUP BY ");
            sql.append(query.groupBy().stream()
                    .map(name -> "t." + descriptor.fields().get(name).column())
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (!query.sort().isEmpty()) {
            sql.append(" ORDER BY ");
            ArrayList<String> sorts = new ArrayList<>();
            for (Sort sort : query.sort()) {
                SemanticDatasetCatalog.FieldDescriptor field =
                        descriptor.fields().get(sort.field());
                String expression = field == null
                        ? sort.field() : "t." + field.column();
                sorts.add(expression + " " + sort.direction().name());
            }
            sql.append(String.join(", ", sorts));
        }
        sql.append(" LIMIT ").append(query.limit());
        String statement = sql.toString();
        return new CompiledQuery(
                query.dataset(), statement, parameters,
                descriptor.timeoutMs(), sha256(statement));
    }

    private static String compileFilter(
            String column,
            Filter filter,
            List<Object> parameters) {
        return switch (filter.operator()) {
            case EQ -> bind(column + " = ?", filter.values(), parameters);
            case NE -> bind(column + " <> ?", filter.values(), parameters);
            case GT -> bind(column + " > ?", filter.values(), parameters);
            case GTE -> bind(column + " >= ?", filter.values(), parameters);
            case LT -> bind(column + " < ?", filter.values(), parameters);
            case LTE -> bind(column + " <= ?", filter.values(), parameters);
            case CONTAINS -> {
                parameters.add("%" + filter.values().getFirst() + "%");
                yield column + " LIKE ?";
            }
            case BETWEEN -> {
                parameters.addAll(filter.values());
                yield column + " BETWEEN ? AND ?";
            }
            case IN -> {
                parameters.addAll(filter.values());
                yield column + " IN ("
                        + String.join(", ", java.util.Collections.nCopies(
                                filter.values().size(), "?"))
                        + ")";
            }
        };
    }

    private static String bind(
            String expression,
            List<Object> values,
            List<Object> parameters) {
        parameters.add(values.getFirst());
        return expression;
    }

    private static String sha256(String statement) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(statement.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
