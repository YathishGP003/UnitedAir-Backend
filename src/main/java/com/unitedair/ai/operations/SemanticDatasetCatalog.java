package com.unitedair.ai.operations;

import com.unitedair.ai.identity.Role;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.unitedair.ai.operations.OperationalQueryDtos.Dataset;
import static com.unitedair.ai.operations.OperationalQueryDtos.Operator;

/** Allowlisted business schema presented to the semantic planner. */
@Component
public class SemanticDatasetCatalog {

    private static final Set<Operator> TEXT =
            Set.of(Operator.EQ, Operator.NE, Operator.IN, Operator.CONTAINS);
    private static final Set<Operator> ORDERED =
            Set.of(Operator.EQ, Operator.NE, Operator.IN, Operator.GT,
                    Operator.GTE, Operator.LT, Operator.LTE, Operator.BETWEEN);
    private static final Set<Role> ALL_ROLES =
            Set.of(Role.PASSENGER, Role.AIRLINE_STAFF, Role.ADMIN);
    private static final Set<Role> STAFF =
            Set.of(Role.AIRLINE_STAFF, Role.ADMIN);
    private static final Set<Role> ADMIN = Set.of(Role.ADMIN);

    private final Map<Dataset, DatasetDescriptor> descriptors =
            new EnumMap<>(Dataset.class);

    public SemanticDatasetCatalog() {
        add(Dataset.AIRPORTS, "v_ai_airport", ALL_ROLES, null,
                fields("code", "city", "name", "country", "timezone", "domestic"));
        add(Dataset.ROUTES, "v_ai_route", ALL_ROLES, null,
                fields("flightNo", "origin", "originCity", "destination",
                        "destinationCity", "durationMinutes", "aircraft", "international"));
        add(Dataset.FLIGHT_SCHEDULES, "v_ai_flight_schedule", ALL_ROLES, null,
                fields("flightNo", "origin", "destination", "departureTime",
                        "arrivalTime", "durationMinutes", "aircraft", "international"));
        add(Dataset.FLIGHT_INSTANCES, "v_ai_flight_instance", ALL_ROLES, null,
                fields("instanceId", "flightNo", "origin", "destination", "flightDate",
                        "status", "delayMinutes", "terminal", "gate", "belt", "updatedAt"));
        add(Dataset.FLIGHT_INVENTORY, "v_ai_flight_inventory", ALL_ROLES, null,
                fields("instanceId", "flightNo", "origin", "destination", "flightDate",
                        "cabin", "fareClass", "fareBrand", "priceInr", "refundable",
                        "changeable", "checkedBaggageKg", "cabinBaggageKg",
                        "seatsAvailable"));
        add(Dataset.SEAT_INVENTORY, "v_ai_seat_inventory", ALL_ROLES, null,
                fields("instanceId", "flightNo", "flightDate", "seatNumber", "cabin",
                        "seatType", "extraLegroom", "exitRow", "feeInr", "available"));
        add(Dataset.BOOKINGS, "v_ai_booking", ALL_ROLES, "owner_user_id",
                fields("pnr", "status", "flightNo", "origin", "destination",
                        "flightDate", "flightStatus", "cabin", "fareClass", "fareBrand",
                        "refundable", "changeable", "amountPaidInr", "refundAmountInr",
                        "refundStatus", "seatNumber", "specialService", "bookedAt",
                        "cancelledAt"));
        add(Dataset.CHECK_IN_STATE, "v_ai_checkin", ALL_ROLES, "owner_user_id",
                fields("pnr", "flightNo", "flightDate", "checkedIn", "checkedInAt",
                        "seatNumber", "boardingGate", "channel", "sequenceNumber"));
        add(Dataset.MEAL_AVAILABILITY, "v_ai_meal", ALL_ROLES, null,
                fields("flightNo", "flightDate", "origin", "destination",
                        "mealService", "availableCodes", "updatedAt"));
        add(Dataset.SPECIAL_SERVICES, "v_ai_special_service", ALL_ROLES,
                "owner_user_id", fields("pnr", "flightNo", "flightDate",
                        "specialService", "bookingStatus"));
        add(Dataset.PAYMENT_STATUS, "v_ai_payment", ALL_ROLES, "owner_user_id",
                fields("paymentReference", "pnr", "method", "status",
                        "amountInr", "currency", "authorizedAt", "capturedAt",
                        "refundedAt", "createdAt", "updatedAt"));
        add(Dataset.REFUND_CASES, "v_ai_refund_case", ALL_ROLES, "owner_user_id",
                fields("caseReference", "pnr", "status", "fareBrand", "amountPaidInr",
                        "cancellationFeeInr", "refundAmountInr", "paymentMethod",
                        "dueAt", "assignedTo", "createdAt", "updatedAt", "completedAt"));
        add(Dataset.REFUND_HISTORY, "v_ai_refund_history", ALL_ROLES, "owner_user_id",
                fields("caseReference", "pnr", "fromStatus", "toStatus",
                        "note", "changedAt"));
        add(Dataset.ESCALATIONS, "v_ai_escalation", STAFF, null,
                fields("caseReference", "reason", "targetQueue", "priority",
                        "status", "createdAt", "resolvedAt"));
        add(Dataset.OPERATIONAL_DECISIONS, "v_ai_operational_decision", STAFF, null,
                fields("decisionReference", "decisionType", "outcome", "actorRole",
                        "pnrDisplay", "reason", "sourcePolicyCode",
                        "sourcePolicySection", "createdAt", "decidedAt"));
        add(Dataset.AUDIT_EVENTS, "v_ai_audit_event", ADMIN, null,
                fields("eventType", "actorRole", "createdAt"));
    }

    public Set<Dataset> datasets() {
        return Set.copyOf(descriptors.keySet());
    }

    public DatasetDescriptor descriptor(Dataset dataset) {
        DatasetDescriptor descriptor = descriptors.get(dataset);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown semantic dataset: " + dataset);
        }
        return descriptor;
    }

    private void add(
            Dataset dataset,
            String view,
            Set<Role> roles,
            String ownership,
            Map<String, FieldDescriptor> fields) {
        descriptors.put(dataset, new DatasetDescriptor(
                dataset, view, fields, roles, ownership, Set.of(),
                20, 50, 1_500));
    }

    private static Map<String, FieldDescriptor> fields(String... names) {
        LinkedHashMap<String, FieldDescriptor> result = new LinkedHashMap<>();
        for (String name : names) {
            String column = camelToSnake(name);
            Set<Operator> operators = isOrdered(name) ? ORDERED : TEXT;
            result.put(name, new FieldDescriptor(column, operators, isAggregatable(name)));
        }
        return Map.copyOf(result);
    }

    private static boolean isOrdered(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith("at") || lower.contains("date")
                || lower.contains("time") || lower.contains("amount")
                || lower.contains("price") || lower.contains("fee")
                || lower.contains("minutes") || lower.contains("available")
                || lower.contains("number") || lower.contains("kg");
    }

    private static boolean isAggregatable(String name) {
        String lower = name.toLowerCase();
        return isOrdered(name) && !lower.contains("date") && !lower.endsWith("at");
    }

    private static String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    public record FieldDescriptor(
            String column,
            Set<Operator> operators,
            boolean aggregatable) {
        public FieldDescriptor {
            operators = Set.copyOf(operators);
        }
    }

    public record DatasetDescriptor(
            Dataset dataset,
            String viewName,
            Map<String, FieldDescriptor> fields,
            Set<Role> roles,
            String ownershipColumn,
            Set<Dataset> allowedJoins,
            int defaultLimit,
            int maxLimit,
            int timeoutMs) {
        public DatasetDescriptor {
            fields = Map.copyOf(fields);
            roles = Set.copyOf(roles);
            allowedJoins = Set.copyOf(allowedJoins);
        }
    }
}
