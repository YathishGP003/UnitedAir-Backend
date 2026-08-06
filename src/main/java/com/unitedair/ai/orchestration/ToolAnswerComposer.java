package com.unitedair.ai.orchestration;

import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.OperationalFailure;
import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.audit.OperationalDecisionDtos;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.operations.OperationalAnswerComposer;
import com.unitedair.ai.operations.OperationalQueryDtos;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Produces a deterministic, fully cited answer from successful structured tool output.
 *
 * <p>This is the safe fallback when model prose fails validation even though a tool call
 * succeeded. It does not relax any grounding gate: every factual line points back to the
 * corresponding tool result handle.
 */
@Component
public class ToolAnswerComposer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private final OperationalAnswerComposer operationalComposer =
            new OperationalAnswerComposer();

    public Optional<String> compose(List<ToolDtos.ToolOutcome> outcomes) {
        return compose(outcomes, "");
    }

    public Optional<String> compose(List<ToolDtos.ToolOutcome> outcomes, String question) {
        if (outcomes == null) {
            return Optional.empty();
        }
        List<OperationalQueryDtos.OperationalDataResult> operationalResults =
                outcomes.stream()
                        .filter(ToolDtos.ToolOutcome::success)
                        .map(ToolDtos.ToolOutcome::data)
                        .filter(OperationalQueryDtos.OperationalDataResult.class::isInstance)
                        .map(OperationalQueryDtos.OperationalDataResult.class::cast)
                        .toList();
        if (!operationalResults.isEmpty()) {
            return Optional.of(operationalComposer.compose(operationalResults));
        }

        ToolDtos.BookingView combinedBooking = null;
        String bookingHandle = null;
        ToolDtos.RefundQuote refundQuote = null;
        String refundHandle = null;
        ToolDtos.FlightStatusView combinedFlightStatus = null;
        String combinedFlightStatusHandle = null;
        int successfulHandle = 0;
        for (ToolDtos.ToolOutcome outcome : outcomes) {
            if (!outcome.success()) {
                continue;
            }
            String handle = "[T" + (++successfulHandle) + "]";
            if (combinedBooking == null && outcome.data() instanceof ToolDtos.BookingView view) {
                combinedBooking = view;
                bookingHandle = handle;
            }
            if (refundQuote == null && outcome.data() instanceof ToolDtos.RefundQuote quote) {
                refundQuote = quote;
                refundHandle = handle;
            }
            if (combinedFlightStatus == null
                    && outcome.data() instanceof ToolDtos.FlightStatusView status) {
                combinedFlightStatus = status;
                combinedFlightStatusHandle = handle;
            }
        }
        if (combinedBooking != null && refundQuote != null) {
            List<String> parts = new ArrayList<>();
            if (combinedFlightStatus != null) {
                parts.add(renderFlightStatus(
                        combinedFlightStatus, combinedFlightStatusHandle));
            }
            parts.add(renderBooking(combinedBooking, bookingHandle));
            parts.add(renderRefundQuote(refundQuote, refundHandle));
            return Optional.of(String.join("\n\n", parts));
        }

        /*
         * A multi-part servicing question can legitimately produce a booking lookup,
         * check-in eligibility and flight status in one plan. Returning only the first
         * typed result loses requested facts; flattening the tool payloads exposes raw
         * JSON. Compose the verified views together as readable, independently cited
         * prose so the safe fallback remains complete.
         */
        ToolDtos.BookingView journeyBooking = null;
        String journeyBookingHandle = null;
        ToolDtos.CheckInEligibility checkInEligibility = null;
        String checkInHandle = null;
        ToolDtos.FlightStatusView liveFlightStatus = null;
        String flightStatusHandle = null;
        int journeyHandle = 0;
        for (ToolDtos.ToolOutcome outcome : outcomes) {
            if (!outcome.success()) {
                continue;
            }
            String handle = "[T" + (++journeyHandle) + "]";
            if (journeyBooking == null && outcome.data() instanceof ToolDtos.BookingView view) {
                journeyBooking = view;
                journeyBookingHandle = handle;
            } else if (checkInEligibility == null
                    && outcome.data() instanceof ToolDtos.CheckInEligibility eligibility) {
                checkInEligibility = eligibility;
                checkInHandle = handle;
            } else if (liveFlightStatus == null
                    && outcome.data() instanceof ToolDtos.FlightStatusView status) {
                liveFlightStatus = status;
                flightStatusHandle = handle;
            }
        }
        int journeyParts = (journeyBooking == null ? 0 : 1)
                + (checkInEligibility == null ? 0 : 1)
                + (liveFlightStatus == null ? 0 : 1);
        if (journeyParts >= 2 && liveFlightStatus != null) {
            List<String> parts = new ArrayList<>();
            if (journeyBooking != null) {
                parts.add(renderBooking(journeyBooking, journeyBookingHandle));
            }
            if (checkInEligibility != null) {
                parts.add(renderCheckInEligibility(checkInEligibility, checkInHandle));
            }
            if (liveFlightStatus != null) {
                parts.add(renderFlightStatus(liveFlightStatus, flightStatusHandle));
            }
            return Optional.of(String.join("\n\n", parts));
        }

        int priorityHandle = 0;
        for (ToolDtos.ToolOutcome outcome : outcomes) {
            if (!outcome.success()) {
                continue;
            }
            String handle = "[T" + (++priorityHandle) + "]";
            if (outcome.data() instanceof ToolDtos.RefundQuote quote) {
                return Optional.of(renderRefundQuote(quote, handle));
            }
            if (outcome.data() instanceof RefundDtos.RefundStatusView status) {
                return Optional.of(renderRefundStatus(status, handle));
            }
            if (outcome.data() instanceof ToolDtos.FlightStatusView status) {
                return Optional.of(renderFlightStatus(status, handle));
            }
            if (outcome.data() instanceof ToolDtos.MealAvailability meal) {
                return Optional.of(renderDatedMealAvailability(meal, handle, question));
            }
            if (outcome.data() instanceof ToolDtos.ExcessBaggageQuote quote) {
                return Optional.of(renderExcessBaggageQuote(quote, handle));
            }
            if (outcome.data() instanceof List<?> rows
                    && !rows.isEmpty()
                    && rows.stream().allMatch(
                            OperationalDecisionDtos.DecisionView.class::isInstance)) {
                @SuppressWarnings("unchecked")
                List<OperationalDecisionDtos.DecisionView> decisions =
                        (List<OperationalDecisionDtos.DecisionView>) rows;
                return Optional.of(renderOperationalDecisions(decisions, handle));
            }
            if (outcome.data() instanceof List<?> rows
                    && !rows.isEmpty()
                    && rows.stream().allMatch(RefundDtos.RefundStatusView.class::isInstance)) {
                @SuppressWarnings("unchecked")
                List<RefundDtos.RefundStatusView> cases =
                        (List<RefundDtos.RefundStatusView>) rows;
                return Optional.of(renderRefundCases(cases, handle));
            }
            if (outcome.data() instanceof List<?> rows
                    && !rows.isEmpty()
                    && rows.stream().allMatch(ToolDtos.SeatOption.class::isInstance)) {
                @SuppressWarnings("unchecked")
                List<ToolDtos.SeatOption> seats = (List<ToolDtos.SeatOption>) rows;
                return Optional.of(renderSeatOptions(seats, handle));
            }
        }

        priorityHandle = 0;
        for (ToolDtos.ToolOutcome outcome : outcomes) {
            if (!outcome.success()) {
                continue;
            }
            String handle = "[T" + (++priorityHandle) + "]";
            if (outcome.data() instanceof ToolDtos.CheckInEligibility
                    && outcome.summary() != null
                    && !outcome.summary().isBlank()) {
                return Optional.of(cite(outcome.summary().trim(), handle));
            }
        }

        int toolHandle = 0;
        for (ToolDtos.ToolOutcome outcome : outcomes) {
            if (!outcome.success()) {
                continue;
            }
            String handle = "[T" + (++toolHandle) + "]";
            if (outcome.data() instanceof ToolDtos.RefundQuote
                    && outcome.summary() != null
                    && !outcome.summary().isBlank()) {
                return Optional.of(cite(outcome.summary().trim(), handle));
            }
            if (outcome.data() instanceof ToolDtos.BookingView booking) {
                return Optional.of(renderBooking(booking, handle));
            }
            if (FlightSearchTool.NAME.equals(outcome.toolName())
                    && outcome.data() instanceof ToolDtos.FlightSearchResult result) {
                return Optional.of(renderFlights(result, handle, question));
            }
            if (outcome.data() instanceof ToolDtos.RecoveryPlan plan) {
                return Optional.of(renderRecovery(plan, handle));
            }
        }

        toolHandle = 0;
        for (ToolDtos.ToolOutcome outcome : outcomes) {
            if (!outcome.success()) {
                continue;
            }
            String handle = "[T" + (++toolHandle) + "]";
            if (outcome.success() && outcome.summary() != null && !outcome.summary().isBlank()) {
                return Optional.of(cite(outcome.summary().trim(), handle));
            }
        }
        return Optional.empty();
    }

    public Optional<String> composeFailure(List<ToolDtos.ToolOutcome> outcomes) {
        if (outcomes == null) {
            return Optional.empty();
        }
        return outcomes.stream()
                .filter(outcome -> !outcome.success())
                .map(outcome -> outcome.data() instanceof OperationalFailure failure
                        ? failure.userMessage()
                        : outcome.errorMessage())
                .filter(message -> message != null && !message.isBlank())
                .findFirst();
    }

    private String renderFlights(
            ToolDtos.FlightSearchResult result,
            String handle,
            String question) {
        if (result.flights() == null || result.flights().isEmpty()) {
            return "I couldn't find any UnitedAir flights from " + result.origin() + " to "
                    + result.destination() + " on " + DATE.format(result.departureDate())
                    + " " + handle + ".";
        }

        String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (lower.contains("cheapest") && lower.contains("refund")) {
            return renderCheapestRefundable(result, handle);
        }
        if (lower.contains("fare") && lower.contains("baggage")) {
            return renderFareAndBaggage(result, handle);
        }
        if (containsAny(lower, "meal", "vgml", "ksml", "dbml", "blml", "chml")) {
            return renderMealAvailability(result, handle, lower);
        }
        List<ToolDtos.FlightOption> selected = new ArrayList<>(result.flights());
        if (containsAny(lower, "later", "latest")) {
            selected = selected.stream()
                    .max(Comparator.comparing(ToolDtos.FlightOption::departureTime))
                    .map(List::of)
                    .orElse(List.of());
        } else if (lower.contains("evening")) {
            selected = selected.stream()
                    .filter(flight -> !flight.departureTime().isBefore(LocalTime.of(17, 0)))
                    .toList();
        } else if (lower.contains("morning")) {
            selected = selected.stream()
                    .filter(flight -> flight.departureTime().isBefore(LocalTime.NOON))
                    .toList();
        } else if (lower.contains("afternoon")) {
            selected = selected.stream()
                    .filter(flight -> !flight.departureTime().isBefore(LocalTime.NOON)
                            && flight.departureTime().isBefore(LocalTime.of(17, 0)))
                    .toList();
        }
        if (selected.isEmpty()) {
            return "I couldn't find a departure in that time window from "
                    + result.origin() + " to " + result.destination() + " on "
                    + DATE.format(result.departureDate()) + " " + handle + ".";
        }
        if (lower.contains("seat")
                && containsAny(lower, "left", "available", "availability", "are there")) {
            return renderSeatAvailability(selected, handle);
        }

        ToolDtos.FlightOption first = selected.getFirst();
        StringBuilder answer = new StringBuilder()
                .append(selected.size()).append(" UnitedAir flight")
                .append(selected.size() == 1 ? " is" : "s are").append(" available from ")
                .append(first.originCity()).append(" (").append(result.origin()).append(") to ")
                .append(first.destinationCity()).append(" (").append(result.destination()).append(") on ")
                .append(DATE.format(result.departureDate())).append(" ").append(handle).append(".\n");

        for (ToolDtos.FlightOption flight : selected) {
            answer.append("- ").append(flight.flightNo()).append(": ")
                    .append(TIME.format(flight.departureTime())).append("–")
                    .append(TIME.format(flight.arrivalTime()));

            cheapestAvailableFare(flight.fares()).ifPresent(fare ->
                    answer.append(", from INR ").append(formatInr(fare.totalFare()))
                            .append(" (").append(fare.fareBrand()).append(")"));

            answer.append(" ").append(handle).append(".\n");
        }

        return answer.toString().trim();
    }

    private String renderCheapestRefundable(
            ToolDtos.FlightSearchResult result,
            String handle) {
        List<FlightFare> available = result.flights().stream()
                .flatMap(flight -> flight.fares().stream()
                        .filter(fare -> fare.seatsAvailable() > 0)
                        .map(fare -> new FlightFare(flight, fare)))
                .toList();
        Optional<FlightFare> cheapest = available.stream()
                .min(Comparator.comparing(item -> item.fare().totalFare()));
        Optional<FlightFare> refundable = available.stream()
                .filter(item -> item.fare().refundable())
                .min(Comparator.comparing(item -> item.fare().totalFare()));
        if (cheapest.isEmpty()) {
            return "No fare inventory is currently available for this route " + handle + ".";
        }

        FlightFare lowest = cheapest.get();
        StringBuilder answer = new StringBuilder()
                .append("The cheapest flight fare is ").append(lowest.fare().fareBrand())
                .append(" on flight ").append(lowest.flight().flightNo())
                .append(" at INR ").append(formatInr(lowest.fare().totalFare()))
                .append("; it is ")
                .append(lowest.fare().refundable() ? "refundable" : "not refundable")
                .append(" ").append(handle).append(".");
        refundable.ifPresent(item -> answer
                .append("\nThe cheapest refundable flight fare is ")
                .append(item.fare().fareBrand()).append(" on flight ")
                .append(item.flight().flightNo()).append(" at INR ")
                .append(formatInr(item.fare().totalFare()))
                .append(" ").append(handle).append("."));
        return answer.toString();
    }

    private String renderSeatAvailability(
            List<ToolDtos.FlightOption> flights,
            String handle) {
        StringBuilder answer = new StringBuilder(
                "Current lowest-fare flight seat availability " + handle + ":\n");
        for (ToolDtos.FlightOption flight : flights) {
            Optional<ToolDtos.FareOption> fare = cheapestAvailableFare(flight.fares());
            if (fare.isPresent()) {
                answer.append("- ").append(flight.flightNo()).append(": ")
                        .append(fare.get().seatsAvailable()).append(" seats available in ")
                        .append(fare.get().fareBrand()).append(" at INR ")
                        .append(formatInr(fare.get().totalFare())).append(" ")
                        .append(handle).append(".\n");
            }
        }
        return answer.toString().trim();
    }

    private String renderRecovery(ToolDtos.RecoveryPlan plan, String handle) {
        StringBuilder answer = new StringBuilder(plan.reason()).append(" ").append(handle).append(".");
        if (plan.alternatives().isEmpty()) {
            return answer.append("\nNo same-route alternative currently has available seats ")
                    .append(handle).append(".").toString();
        }
        answer.append("\nRecovery options (no change is made until you confirm):\n");
        for (ToolDtos.RecoveryAlternative option : plan.alternatives()) {
            answer.append("- ").append(option.flightNo()).append(" on ")
                    .append(DATE.format(option.flightDate())).append(", ")
                    .append(TIME.format(option.departureTime())).append("–")
                    .append(TIME.format(option.arrivalTime())).append(", ")
                    .append(option.fareBrand()).append(" INR ")
                    .append(formatInr(option.totalFare())).append(", ")
                    .append(option.availableSeats()).append(" seats ").append(handle).append(".\n");
        }
        return answer.toString().trim();
    }

    private String renderBooking(ToolDtos.BookingView booking, String handle) {
        StringBuilder answer = new StringBuilder()
                .append("Your booking ").append(booking.pnr()).append(" is for flight ")
                .append(booking.flightNo()).append(" ")
                .append(booking.origin()).append("-").append(booking.destination())
                .append(" on ").append(DATE.format(booking.flightDate()))
                .append(", ").append(booking.cabin()).append(" ")
                .append(booking.fareBrand())
                .append(booking.refundable() ? " (refundable)" : " (non-refundable)")
                .append(", status ").append(booking.status());
        if (booking.seatNumber() != null && !booking.seatNumber().isBlank()) {
            answer.append(", seat ").append(booking.seatNumber());
        }
        if (booking.checkedBaggageKg() > 0) {
            answer.append(", ").append(booking.checkedBaggageKg())
                    .append(" kg checked baggage");
        }
        return cite(answer.append(".").toString(), handle);
    }

    private String renderRefundQuote(ToolDtos.RefundQuote quote, String handle) {
        String answer = "Here is the current cancellation estimate:\n\n"
                + "**Fare:** " + quote.fareBrand()
                + (quote.refundable() ? " (refundable)" : " (not refundable)")
                + " " + handle + "."
                + "\n**Amount paid:** INR " + formatInr(quote.amountPaid()) + " " + handle + "."
                + "\n**Cancellation fee:** INR " + formatInr(quote.cancellationFee())
                + " " + handle + "."
                + "\n**Estimated refund:** INR " + formatInr(quote.estimatedRefund())
                + " " + handle + "."
                + "\n**Refund timing:** "
                + quote.refundTimeline().replaceAll("[.\\s]+$", "")
                + " " + handle + ".";
        return answer;
    }

    private String renderRefundStatus(
            RefundDtos.RefundStatusView status,
            String handle) {
        String answer = "The refund for booking **"
                + status.bookingReferenceDisplay()
                + "** is **" + status.status() + "** "
                + handle + ".\n\n"
                + "- **Amount:** INR " + formatInr(status.amount()) + " " + handle + ".\n";
        if (status.dueAt() != null
                && !"COMPLETED".equalsIgnoreCase(status.status())) {
            answer += "- **Expected by:** " + status.dueAt() + " " + handle + ".\n";
        }
        if (status.updatedAt() != null) {
            answer += "- **Last updated:** " + status.updatedAt() + " " + handle + ".\n";
        }
        answer += "- **Next step:** "
                + status.nextAction().replaceAll("[.\\s]+$", "")
                + " " + handle + ".";
        return answer;
    }

    private String renderRefundCases(
            List<RefundDtos.RefundStatusView> cases,
            String handle) {
        if (cases.isEmpty()) {
            return "No refund cases match those filters " + handle + ".";
        }
        StringBuilder answer = new StringBuilder()
                .append(cases.size())
                .append(cases.size() == 1 ? " refund case matches" : " refund cases match")
                .append(" the current filters ").append(handle).append(".\n");
        for (RefundDtos.RefundStatusView refundCase : cases) {
            answer.append("- Refund case ").append(refundCase.caseUuid())
                    .append(": ").append(refundCase.status().toLowerCase(Locale.ROOT)
                            .replace('_', ' '))
                    .append(", INR ").append(formatInr(refundCase.amount()));
            if (refundCase.dueAt() != null) {
                answer.append(", due ").append(refundCase.dueAt());
            }
            answer.append(" ").append(handle).append(".\n");
        }
        return answer.toString().trim();
    }

    private String renderOperationalDecisions(
            List<OperationalDecisionDtos.DecisionView> decisions,
            String handle) {
        Map<OperationalDecisionDtos.DecisionType,
                List<OperationalDecisionDtos.DecisionView>> grouped =
                new EnumMap<>(OperationalDecisionDtos.DecisionType.class);
        for (OperationalDecisionDtos.DecisionView decision : decisions) {
            grouped.computeIfAbsent(
                    decision.decisionType(), ignored -> new ArrayList<>()).add(decision);
        }
        StringBuilder answer = new StringBuilder()
                .append(decisions.size())
                .append(decisions.size() == 1
                        ? " operational audit record matches"
                        : " operational audit records match")
                .append(" the filters ").append(handle).append(".\n");
        for (var entry : grouped.entrySet()) {
            Map<String, Integer> outcomes = new LinkedHashMap<>();
            TreeSet<String> policies = new TreeSet<>();
            for (OperationalDecisionDtos.DecisionView decision : entry.getValue()) {
                outcomes.merge(
                        decision.outcome().toLowerCase(Locale.ROOT)
                                .replace('_', ' '),
                        1,
                        Integer::sum);
                if (decision.sourcePolicyCode() != null
                        && !decision.sourcePolicyCode().isBlank()) {
                    policies.add(decision.sourcePolicyCode());
                }
            }
            String outcomeSummary = outcomes.entrySet().stream()
                    .map(item -> item.getKey() + ": " + item.getValue())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("no outcome");
            answer.append("- ")
                    .append(decisionTypeLabel(entry.getKey()))
                    .append(": ").append(entry.getValue().size())
                    .append(" (").append(outcomeSummary).append(")");
            if (!policies.isEmpty()) {
                answer.append("; source ")
                        .append(String.join(", ", policies));
            }
            answer.append(" ").append(handle).append(".\n");
        }
        answer.append("Each record preserves the decision type, outcome, actor role, "
                + "masked booking reference, source policy, trace and session references, "
                + "and timestamps ").append(handle).append(".");
        return answer.toString().trim();
    }

    private String decisionTypeLabel(OperationalDecisionDtos.DecisionType type) {
        return switch (type) {
            case REFUND_APPROVAL -> "Refund approvals";
            case UPGRADE_AUTHORIZATION -> "Upgrade authorizations";
            case BOARDING_OVERRIDE -> "Boarding overrides";
            case SPECIAL_SERVICE_EXCEPTION -> "Special-service exceptions";
        };
    }

    private String renderFlightStatus(
            ToolDtos.FlightStatusView status,
            String handle) {
        StringBuilder answer = new StringBuilder()
                .append(status.flightNo()).append(" ")
                .append(status.origin()).append("-").append(status.destination())
                .append(" on ").append(DATE.format(status.flightDate()))
                .append(" is ")
                .append(status.status().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (status.delayMinutes() > 0) {
            answer.append(", delayed by ").append(status.delayMinutes())
                    .append(" minutes with estimated departure at ")
                    .append(TIME.format(status.estimatedDeparture()));
        }
        if (status.terminal() != null) {
            answer.append(", terminal ").append(status.terminal());
        }
        if (status.gate() != null) {
            answer.append(", gate ").append(status.gate());
        }
        return cite(answer.append(".").toString(), handle);
    }

    private String renderCheckInEligibility(
            ToolDtos.CheckInEligibility eligibility,
            String handle) {
        StringBuilder answer = new StringBuilder()
                .append(eligibility.eligible()
                        ? "Check-in is open for booking "
                        : "Check-in is not open for booking ")
                .append(eligibility.pnr()).append(".");
        if (eligibility.reason() != null && !eligibility.reason().isBlank()) {
            answer.append(" ").append(eligibility.reason().replaceAll("[.\\s]+$", ""))
                    .append(".");
        }
        if (eligibility.availableChannels() != null
                && !eligibility.availableChannels().isEmpty()) {
            answer.append(" Available channels: ")
                    .append(String.join(", ", eligibility.availableChannels()))
                    .append(".");
        }
        return cite(answer.toString(), handle);
    }

    private String renderSeatOptions(
            List<ToolDtos.SeatOption> seats,
            String handle) {
        StringBuilder answer = new StringBuilder("Available seats:\n");
        for (ToolDtos.SeatOption seat : seats) {
            answer.append("- ").append(seat.seatNumber())
                    .append(": ").append(seat.seatType())
                    .append(" in ").append(seat.cabin());
            if (seat.extraLegroom()) {
                answer.append(", extra legroom");
            }
            if (seat.exitRow()) {
                answer.append(", exit row");
            }
            answer.append(", ")
                    .append(seat.feeInr() == null || seat.feeInr().signum() == 0
                            ? "included" : "INR " + formatInr(seat.feeInr()))
                    .append(" ").append(handle).append(".\n");
        }
        return answer.toString().trim();
    }

    private String renderFareAndBaggage(
            ToolDtos.FlightSearchResult result,
            String handle) {
        StringBuilder answer = new StringBuilder("Lowest available fare and baggage details:\n");
        for (ToolDtos.FlightOption flight : result.flights()) {
            cheapestAvailableFare(flight.fares()).ifPresent(fare -> answer
                    .append("- ").append(flight.flightNo()).append(": ")
                    .append(fare.fareBrand()).append(" at INR ")
                    .append(formatInr(fare.totalFare())).append(", ")
                    .append(fare.checkedBaggageKg()).append(" kg checked and ")
                    .append(fare.cabinBaggageKg()).append(" kg cabin baggage")
                    .append(fare.refundable() ? ", refundable" : ", non-refundable")
                    .append(" ").append(handle).append(".\n"));
        }
        return answer.toString().trim();
    }

    private String renderMealAvailability(
            ToolDtos.FlightSearchResult result,
            String handle,
            String question) {
        String requestedCode = List.of("VGML", "KSML", "DBML", "BLML", "CHML")
                .stream()
                .filter(code -> question.contains(code.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
        StringBuilder answer = new StringBuilder(
                "Special-meal availability for the selected dated flight(s):\n");
        for (ToolDtos.FlightOption flight : result.flights()) {
            ToolDtos.MealAvailability meal = flight.mealAvailability();
            answer.append("- ").append(flight.flightNo()).append(": ");
            if (meal == null || !meal.mealService()) {
                answer.append("no scheduled meal service");
            } else if (requestedCode != null) {
                answer.append(requestedCode)
                        .append(meal.availableCodes().contains(requestedCode)
                                ? " is available" : " is not available");
                if (meal.availableCodes().contains(requestedCode)
                        && meal.orderDeadline() != null) {
                    answer.append("; order by ").append(meal.orderDeadline());
                }
            } else {
                answer.append("meal service is scheduled; available special-meal codes are ")
                        .append(String.join(", ", meal.availableCodes()));
            }
            answer.append(" ").append(handle).append(".\n");
        }
        return answer.toString().trim();
    }

    private String renderDatedMealAvailability(
            ToolDtos.MealAvailability meal,
            String handle,
            String question) {
        String requestedCode = List.of(
                        "AVML", "BBML", "BLML", "CHML", "DBML", "FPML", "GFML",
                        "HNML", "KSML", "LCML", "MOML", "NLML", "VGML", "VLML")
                .stream()
                .filter(code -> question != null
                        && question.toUpperCase(Locale.ROOT).contains(code))
                .findFirst()
                .orElse(null);
        if (!meal.mealService()) {
            return meal.flightNo() + " on " + DATE.format(meal.date())
                    + " does not have scheduled meal service " + handle + ".";
        }
        if (requestedCode != null) {
            if (!meal.availableCodes().contains(requestedCode)) {
                return requestedCode + " is not available on " + meal.flightNo()
                        + " on " + DATE.format(meal.date()) + " " + handle + ".";
            }
            String deadline = meal.orderDeadline() == null
                    ? "" : " Order it by " + meal.orderDeadline() + ".";
            return requestedCode + " is available on " + meal.flightNo()
                    + " on " + DATE.format(meal.date()) + " " + handle + "."
                    + deadline;
        }
        return meal.flightNo() + " on " + DATE.format(meal.date())
                + " has scheduled meal service. Available special-meal codes: "
                + String.join(", ", meal.availableCodes()) + " " + handle + ".";
    }

    private String renderExcessBaggageQuote(
            ToolDtos.ExcessBaggageQuote quote,
            String handle) {
        return "For " + quote.excessKg() + " kg of excess baggage on a "
                + quote.routeType().toLowerCase(Locale.ROOT) + " "
                + quote.cabin().toLowerCase(Locale.ROOT)
                + " itinerary, the current simulator tariff is "
                + quote.currency() + " " + formatInr(quote.advanceFee())
                + " when purchased in advance or " + quote.currency() + " "
                + formatInr(quote.airportFee()) + " at the airport "
                + handle + ".";
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private Optional<ToolDtos.FareOption> cheapestAvailableFare(List<ToolDtos.FareOption> fares) {
        if (fares == null) {
            return Optional.empty();
        }
        return fares.stream()
                .filter(fare -> fare.seatsAvailable() > 0)
                .min(Comparator.comparing(ToolDtos.FareOption::totalFare));
    }

    private String formatInr(BigDecimal value) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.US);
        format.setGroupingUsed(true);
        return format.format(value);
    }

    private String cite(String sentence, String handle) {
        String body = sentence.replaceFirst("[.!?]+$", "");
        return body + " " + handle + ".";
    }

    private record FlightFare(
            ToolDtos.FlightOption flight,
            ToolDtos.FareOption fare) { }
}
