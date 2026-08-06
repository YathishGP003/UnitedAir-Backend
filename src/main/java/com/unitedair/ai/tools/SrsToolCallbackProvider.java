package com.unitedair.ai.tools;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.TraceContext;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * The only Spring AI callback registry in the application.
 *
 * <p>Callbacks are described to the hosted model, but model-side internal execution is
 * disabled by {@link com.unitedair.ai.llm.ChatGateway}. The model can only propose calls;
 * this provider validates the proposal before dispatching it to one of the four SRS
 * facades. Mutating operations return a proposal and still require an authenticated
 * confirmation endpoint.
 */
@Component
public class SrsToolCallbackProvider implements ToolCallbackProvider {

    public static final Set<String> FAMILIES = Set.of(
            FlightSearchTool.NAME,
            BookingManagementTool.NAME,
            CheckInStatusTool.NAME,
            EscalationTool.NAME);

    private static final Map<String, Map<String, OperationSpec>> REGISTRY = registry();

    private final FlightSearchTool flights;
    private final BookingManagementTool bookings;
    private final CheckInStatusTool checkInStatus;
    private final EscalationTool escalations;
    private final CurrentUser currentUser;
    private final ToolInvocationLogger logger;
    private final int maxCalls;
    private final ToolCallbackProvider callbacks;

    public SrsToolCallbackProvider(
            FlightSearchTool flights,
            BookingManagementTool bookings,
            CheckInStatusTool checkInStatus,
            EscalationTool escalations,
            CurrentUser currentUser,
            ToolInvocationLogger logger,
            UnitedAirProperties properties) {
        this.flights = flights;
        this.bookings = bookings;
        this.checkInStatus = checkInStatus;
        this.escalations = escalations;
        this.currentUser = currentUser;
        this.logger = logger;
        this.maxCalls = Math.min(3, properties.getRag().getMaxToolCallsPerRequest());
        this.callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(flights, bookings, checkInStatus, escalations)
                .build();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.getToolCallbacks().clone();
    }

    public List<ToolDtos.ValidatedToolCall> validate(
            List<ToolDtos.ProposedToolCall> proposedCalls) {
        if (proposedCalls == null || proposedCalls.isEmpty()) {
            return List.of();
        }
        CurrentUser.Authenticated actor = currentUser.require();
        if (proposedCalls.size() > maxCalls) {
            reject(
                    proposedCalls.getFirst(),
                    actor.role(),
                    "A model may propose at most " + maxCalls + " tool calls per turn.");
        }

        List<ToolDtos.ValidatedToolCall> validated = new ArrayList<>();
        for (ToolDtos.ProposedToolCall proposal : proposedCalls) {
            validateOne(proposal, actor);
            ToolDtos.ValidatedToolCall call = new ToolDtos.ValidatedToolCall(
                    proposal.toolFamily(),
                    proposal.operation(),
                    proposal.arguments(),
                    actor.role(),
                    TraceContext.sessionUuid(),
                    TraceContext.traceId());
            logger.recordValidation(
                    call.toolFamily(),
                    call.operation(),
                    call.actorRole().name(),
                    call.arguments());
            validated.add(call);
        }
        return List.copyOf(validated);
    }

    public List<ToolDtos.GovernedToolResult> execute(
            List<ToolDtos.ProposedToolCall> proposedCalls) {
        return validate(proposedCalls).stream().map(this::executeOne).toList();
    }

    private ToolDtos.GovernedToolResult executeOne(ToolDtos.ValidatedToolCall call) {
        return switch (call.toolFamily()) {
            case FlightSearchTool.NAME -> flights.invoke(
                    new ToolDtos.FlightSearchToolRequest(
                            enumValue(
                                    ToolDtos.FlightSearchOperation.class,
                                    call.operation()),
                            call.arguments()));
            case BookingManagementTool.NAME -> bookings.invoke(
                    new ToolDtos.BookingManagementToolRequest(
                            enumValue(
                                    ToolDtos.BookingManagementOperation.class,
                                    call.operation()),
                            call.arguments()));
            case CheckInStatusTool.NAME -> checkInStatus.invoke(
                    new ToolDtos.CheckInStatusToolRequest(
                            enumValue(
                                    ToolDtos.CheckInStatusOperation.class,
                                    call.operation()),
                            call.arguments()));
            case EscalationTool.NAME -> escalations.invoke(
                    new ToolDtos.EscalationToolRequest(
                            enumValue(
                                    ToolDtos.EscalationOperation.class,
                                    call.operation()),
                            call.arguments()));
            default -> throw new ApiExceptions.BadRequest("Unknown tool family.");
        };
    }

    private void validateOne(
            ToolDtos.ProposedToolCall proposal,
            CurrentUser.Authenticated actor) {
        if (proposal == null
                || proposal.toolFamily() == null
                || proposal.operation() == null) {
            throw new ApiExceptions.BadRequest(
                    "Every tool proposal requires a family and operation.");
        }
        logger.recordModelProposal(
                safe(proposal.toolFamily()),
                safe(proposal.operation()),
                actor.role().name(),
                proposal.arguments());
        Map<String, OperationSpec> family = REGISTRY.get(proposal.toolFamily());
        if (family == null) {
            reject(proposal, actor.role(), "Unknown tool family.");
        }
        OperationSpec spec = family.get(proposal.operation());
        if (spec == null) {
            reject(
                    proposal,
                    actor.role(),
                    "Unknown or direct-mutation operation for "
                            + proposal.toolFamily() + ".");
        }
        if (!spec.roles().contains(actor.role())) {
            reject(proposal, actor.role(), "That role cannot use this operation.");
        }
        Set<String> keys = proposal.arguments().keySet();
        if (!keys.containsAll(spec.required())) {
            Set<String> missing = new java.util.LinkedHashSet<>(spec.required());
            missing.removeAll(keys);
            reject(proposal, actor.role(), "Missing required arguments: " + missing + ".");
        }
        Set<String> extra = new java.util.LinkedHashSet<>(keys);
        extra.removeAll(spec.allowed());
        if (!extra.isEmpty()) {
            reject(proposal, actor.role(), "Unexpected arguments: " + extra + ".");
        }
        if (actor.role() == Role.PASSENGER
                && spec.ownedPnr()
                && !bookings.find(
                        text(proposal.arguments().get("pnr")),
                        new BookingAccess(actor.role(), actor.id())).isPresent()) {
            reject(
                    proposal,
                    actor.role(),
                    "No booking owned by the authenticated passenger matches that reference.");
        }
    }

    private void reject(
            ToolDtos.ProposedToolCall proposal, Role role, String detail) {
        logger.recordRejection(
                proposal == null ? "UNKNOWN" : safe(proposal.toolFamily()),
                proposal == null ? "UNKNOWN" : safe(proposal.operation()),
                role.name(),
                proposal == null ? Map.of() : proposal.arguments(),
                detail);
        throw new ApiExceptions.BadRequest(detail);
    }

    private static String safe(String value) {
        if (value == null) {
            return "UNKNOWN";
        }
        String safe = value.replaceAll("[^A-Za-z0-9_-]", "");
        return safe.isBlank() ? "UNKNOWN" : safe.substring(0, Math.min(64, safe.length()));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException invalid) {
            throw new ApiExceptions.BadRequest("Unsupported tool operation.");
        }
    }

    private static Map<String, Map<String, OperationSpec>> registry() {
        Map<String, Map<String, OperationSpec>> registry = new LinkedHashMap<>();
        registry.put(FlightSearchTool.NAME, Map.of(
                "SEARCH_FLIGHTS", spec(
                        roles(),
                        required("origin", "destination", "departureDate"),
                        allowed(
                                "origin", "destination", "departureDate", "cabin",
                                "adults", "children", "infants"),
                        false),
                "GET_SEAT_MAP", spec(
                        roles(), required("flightNo", "date"),
                        allowed("flightNo", "date"), false),
                "GET_MEAL_AVAILABILITY", spec(
                        roles(), required("flightNo", "date"),
                        allowed("flightNo", "date", "mealCode"), false),
                "QUOTE_EXCESS_BAGGAGE", spec(
                        roles(), required("routeType", "cabin", "excessKg"),
                        allowed("routeType", "cabin", "excessKg"), false),
                "LIST_SUPPORTED_AIRPORTS", spec(
                        roles(), Set.of(), allowed("query"), false)));
        registry.put(BookingManagementTool.NAME, Map.ofEntries(
                Map.entry("RETRIEVE_BOOKING", owned(required("pnr"), allowed("pnr"))),
                Map.entry(
                        "CREATE_BOOKING_PROPOSAL",
                        spec(
                                EnumSet.of(Role.PASSENGER),
                                required("flightInstanceId", "fareId"),
                                allowed("flightInstanceId", "fareId", "traveller"),
                                false)),
                Map.entry("QUOTE_CANCELLATION", owned(required("pnr"), allowed("pnr"))),
                Map.entry(
                        "GET_REFUND_STATUS",
                        owned(required("pnr"), allowed("pnr", "caseUuid"))),
                Map.entry(
                        "LIST_REFUND_CASES",
                        spec(
                                EnumSet.of(Role.AIRLINE_STAFF, Role.ADMIN),
                                Set.of(),
                                allowed("status", "dueBefore", "limit"),
                                false)),
                Map.entry("PROPOSE_CANCELLATION", owned(required("pnr"), allowed("pnr"))),
                Map.entry(
                        "PROPOSE_RESCHEDULE",
                        owned(
                                required("pnr", "targetFlightInstanceId"),
                                allowed("pnr", "targetFlightInstanceId", "targetFareClass"))),
                Map.entry(
                        "PROPOSE_SEAT_CHANGE",
                        owned(required("pnr", "targetSeat"), allowed("pnr", "targetSeat"))),
                Map.entry(
                        "DISRUPTION_ALTERNATIVES",
                        owned(required("pnr"), allowed("pnr")))));
        registry.put(CheckInStatusTool.NAME, Map.of(
                "GET_FLIGHT_STATUS", spec(
                        roles(), required("flightNo", "date"),
                        allowed("flightNo", "date"), false),
                "GET_CHECKIN_ELIGIBILITY", owned(required("pnr"), allowed("pnr")),
                "PROPOSE_CHECK_IN", owned(required("pnr"), allowed("pnr")),
                "SUBSCRIBE_GATE_TERMINAL", spec(
                        roles(), required("flightNo", "date"),
                        allowed("flightNo", "date", "pnr"), false)));
        registry.put(EscalationTool.NAME, Map.of(
                "CREATE_ESCALATION", spec(
                        roles(),
                        required("reason", "summary"),
                        allowed("reason", "summary", "pnr", "confidence", "priority"),
                        false)));
        return Map.copyOf(registry);
    }

    private static OperationSpec owned(Set<String> required, Set<String> allowed) {
        return spec(roles(), required, allowed, true);
    }

    private static OperationSpec spec(
            Set<Role> roles,
            Set<String> required,
            Set<String> allowed,
            boolean ownedPnr) {
        return new OperationSpec(
                Set.copyOf(roles), Set.copyOf(required), Set.copyOf(allowed), ownedPnr);
    }

    private static Set<Role> roles() {
        return EnumSet.allOf(Role.class);
    }

    private static Set<String> required(String... values) {
        return Set.of(values);
    }

    private static Set<String> allowed(String... values) {
        return Set.of(values);
    }

    private record OperationSpec(
            Set<Role> roles,
            Set<String> required,
            Set<String> allowed,
            boolean ownedPnr) { }
}
