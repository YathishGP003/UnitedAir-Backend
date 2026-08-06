package com.unitedair.ai.tools;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.stereotype.Component;

/**
 * Read-only irregular-operations helper. It finds safe recovery choices but never mutates
 * a booking; a chosen option still goes through the existing two-phase reschedule action.
 */
@Component
public class DisruptionRecoveryWorker {

    public static final String OPERATION = "DISRUPTION_ALTERNATIVES";

    private final BookingRepository bookings;
    private final SimulatorRepository simulator;
    private final ToolInvocationLogger logger;

    public DisruptionRecoveryWorker(
            BookingRepository bookings,
            SimulatorRepository simulator,
            ToolInvocationLogger logger) {
        this.bookings = bookings;
        this.simulator = simulator;
        this.logger = logger;
    }

    public ToolDtos.RecoveryPlan findAlternatives(String pnr, BookingAccess access) {
        ToolDtos.BookingView booking = bookings.findByPnr(pnr, access)
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No booking found for that reference. Please check it and try again."));
        boolean cancelled = "CANCELLED".equalsIgnoreCase(booking.flightStatus());
        boolean delayed = booking.delayMinutes() >= 60
                || "DELAYED".equalsIgnoreCase(booking.flightStatus());
        if (!cancelled && !delayed) {
            throw new ApiExceptions.BadRequest(
                    "This flight is not currently disrupted, so recovery alternatives are not needed.");
        }

        List<ToolDtos.FlightOption> candidates = new ArrayList<>();
        candidates.addAll(simulator.searchFlights(
                booking.origin(), booking.destination(), booking.flightDate(), booking.cabin()));
        candidates.addAll(simulator.searchFlights(
                booking.origin(), booking.destination(), booking.flightDate().plusDays(1),
                booking.cabin()));

        List<ToolDtos.RecoveryAlternative> alternatives = candidates.stream()
                .filter(flight -> !(flight.flightNo().equalsIgnoreCase(booking.flightNo())
                        && flight.flightDate().equals(booking.flightDate())))
                .filter(flight -> !"CANCELLED".equalsIgnoreCase(flight.status()))
                .flatMap(flight -> flight.fares().stream()
                        .filter(fare -> fare.seatsAvailable() > 0)
                        .filter(fare -> booking.cabin().equalsIgnoreCase(fare.cabin()))
                        .min(Comparator.comparing(ToolDtos.FareOption::totalFare))
                        .stream()
                        .map(fare -> new ToolDtos.RecoveryAlternative(
                                flight.flightInstanceId(), flight.flightNo(), flight.flightDate(),
                                flight.departureTime(), flight.arrivalTime(), fare.cabin(),
                                fare.fareClass(), fare.fareBrand(), fare.totalFare(),
                                fare.totalFare().subtract(nullSafe(booking.amountPaid())),
                                fare.seatsAvailable())))
                .sorted(Comparator
                        .comparing(ToolDtos.RecoveryAlternative::flightDate)
                        .thenComparing(ToolDtos.RecoveryAlternative::arrivalTime)
                        .thenComparing(ToolDtos.RecoveryAlternative::totalFare))
                .limit(3)
                .toList();

        String reason = cancelled
                ? "The original flight is cancelled."
                : "The original flight is delayed by " + booking.delayMinutes() + " minutes.";
        return new ToolDtos.RecoveryPlan(booking, reason, alternatives, Instant.now());
    }

    public ToolDtos.ToolOutcome invoke(String pnr, BookingAccess access) {
        Map<String, Object> logged = new LinkedHashMap<>();
        logged.put("pnrProvided", pnr != null && !pnr.isBlank());
        logged.put("operation", "findDisruptionAlternatives");
        return logger.invoke(
                BookingManagementTool.NAME,
                OPERATION,
                access.role().name(),
                "ORCHESTRATOR_WORKER",
                logged,
                () -> {
            ToolDtos.RecoveryPlan plan = findAlternatives(pnr, access);
            String summary = plan.alternatives().isEmpty()
                    ? plan.reason() + " No same-route alternative currently has seats."
                    : plan.reason() + " " + plan.alternatives().size()
                            + " same-route alternatives are available.";
            return new ToolInvocationLogger.ToolResult(plan, summary);
        });
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
