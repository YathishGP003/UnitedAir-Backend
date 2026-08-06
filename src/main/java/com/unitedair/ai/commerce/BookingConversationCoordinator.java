package com.unitedair.ai.commerce;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.unitedair.ai.orchestration.OrchestrationDtos;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.OperationalFailure;
import com.unitedair.ai.tools.OperationalFailureKind;
import com.unitedair.ai.tools.SimulatorRepository;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.stereotype.Service;

/**
 * Owns only the new-ticket conversation. Policy Q&A and existing-booking tools
 * continue through the established orchestrator unchanged.
 */
@Service
public class BookingConversationCoordinator {

    private final BookingDraftService drafts;
    private final SimulatorRepository simulator;
    private final FlightSearchTool flights;
    private final TravelCatalogRepository catalog;

    public BookingConversationCoordinator(
            BookingDraftService drafts,
            SimulatorRepository simulator,
            FlightSearchTool flights,
            TravelCatalogRepository catalog) {
        this.drafts = drafts;
        this.simulator = simulator;
        this.flights = flights;
        this.catalog = catalog;
    }

    public CommerceTurn handlePlanned(
            String message,
            OrchestrationDtos.Classification classification,
            long userId,
            String sessionUuid) {
        if (classification.intent() != OrchestrationDtos.Intent.BOOK_FLIGHT
                && classification.tool()
                        != OrchestrationDtos.ToolTarget.FLIGHT_SEARCH) {
            throw new IllegalArgumentException(
                    "Booking coordinator requires a validated booking route.");
        }
        Optional<CommerceDtos.BookingDraftView> active =
                drafts.findActive(userId, sessionUuid);
        if (active.isPresent()) {
            Optional<CommerceTurn> draftReference =
                    handleDraftReference(message, active.get());
            if (draftReference.isPresent()) {
                return draftReference.orElseThrow();
            }
        }

        CommerceDtos.BookingDraftView draft = active.orElseGet(
                () -> drafts.startOrResume(userId, sessionUuid));
        String origin = resolve(classification.origin(), draft.origin());
        String destination = resolve(classification.destination(), draft.destination());
        LocalDate date = classification.travelDate() == null
                ? draft.travelDate() : classification.travelDate();

        if (classification.origin() != null && origin == null
                || classification.destination() != null && destination == null) {
            List<CommerceDtos.AirportView> alternatives =
                    origin == null ? catalog.airports() : catalog.destinationsFrom(origin);
            String unavailableOrigin = classification.origin() != null && origin == null
                    ? classification.origin().trim() : null;
            String unavailableDestination =
                    classification.destination() != null && destination == null
                            ? classification.destination().trim() : null;
            String unavailable = unavailableOrigin != null
                    && unavailableDestination != null
                            ? unavailableOrigin + " and " + unavailableDestination
                            : unavailableOrigin != null
                                    ? unavailableOrigin : unavailableDestination;
            String networkMessage = unavailableOrigin != null
                    && unavailableDestination != null
                            ? unavailable + " are not currently in the UnitedAir network. "
                            : unavailable + " is not currently in the UnitedAir network. ";
            String nextStep = origin == null
                    ? "Choose a supported origin below, then select one of its "
                            + "available destinations."
                    : "Choose an available destination from "
                            + classification.origin().trim() + " below.";
            return new CommerceTurn(
                    networkMessage + nextStep,
                    new CommerceDtos.CommercePayload(
                            CommerceDtos.CommerceType.BOOKING_DETAILS_REQUIRED,
                            draft, List.of(), alternatives, null),
                    List.of("TravelCatalogTool"));
        }

        if (origin != null || destination != null || date != null) {
            draft = drafts.applySlots(
                    userId,
                    draft.draftUuid(),
                    new CommerceDtos.DraftPatch(
                            origin, destination, date,
                            classification.cabin() == null ? draft.cabin() : classification.cabin(),
                            null, null, null, null, null),
                    draft.version());
        }

        if (origin == null || destination == null) {
            return details(
                    "Which origin and destination would you like to book?", draft);
        }
        if (date == null) {
            return details(
                    "What date would you like to travel from " + origin
                            + " to " + destination + "?", draft);
        }

        FlightSearchTool.Outcome search = flights.search(
                new ToolDtos.FlightSearchRequest(
                        origin, destination, date,
                        draft.cabin() == null ? "ECONOMY" : draft.cabin(),
                        1, 0, 0),
                "PASSENGER");
        ToolDtos.FlightSearchResult result = search.data();
        if (result == null) {
            OperationalFailure failure = search.envelope() != null
                    && search.envelope().data() instanceof OperationalFailure typed
                            ? typed : null;
            boolean noRoute = failure != null
                    && failure.kind() == OperationalFailureKind.NO_ROUTE;
            boolean noInventory = failure != null
                    && failure.kind() == OperationalFailureKind.NO_INVENTORY;
            List<CommerceDtos.AirportView> alternatives = noRoute
                    ? catalog.destinationsFrom(origin) : List.of();
            Object detail = noInventory
                    ? catalog.nearbyDates(origin, destination, date) : null;
            String answer = failure == null
                    ? "I could not complete that flight search. Please try another route or date."
                    : failure.userMessage();
            if (noRoute && !alternatives.isEmpty()) {
                answer += " Choose another available destination below.";
            } else if (noInventory && detail != null) {
                answer += " I found the nearest scheduled dates below.";
            }
            return new CommerceTurn(
                    answer,
                    new CommerceDtos.CommercePayload(
                            CommerceDtos.CommerceType.BOOKING_DETAILS_REQUIRED,
                            draft, List.of(), alternatives, detail),
                    noRoute
                            ? List.of("FlightSearchTool", "TravelCatalogTool")
                            : List.of("FlightSearchTool"));
        }
        Object detail = result.flights().isEmpty()
                ? catalog.nearbyDates(origin, destination, date) : null;
        String answer = result.flights().isEmpty()
                ? "There are no UnitedAir seats on that route and date. "
                        + "I found the nearest scheduled dates below."
                : "I found " + result.resultCount() + " UnitedAir "
                        + (result.resultCount() == 1 ? "flight" : "flights")
                        + ". Choose a fare to continue securely.";
        return new CommerceTurn(
                answer,
                new CommerceDtos.CommercePayload(
                        CommerceDtos.CommerceType.FLIGHT_OPTIONS,
                        draft, result.flights(), List.of(), detail),
                List.of("FlightSearchTool"));
    }

    private Optional<CommerceTurn> handleDraftReference(
            String message,
            CommerceDtos.BookingDraftView draft) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String journey = journey(draft);

        boolean asksForPnr = (lower.contains("pnr")
                || lower.contains("booking reference")
                || lower.contains("record locator"))
                && (lower.contains("what") || lower.contains("where")
                    || lower.contains("find") || lower.contains("have"));
        if (asksForPnr) {
            return Optional.of(draftStateTurn(
                    "Your " + journey + " selection is not confirmed yet, so it does not "
                            + "have a PNR. Choose a fare and complete the traveller and "
                            + "simulated-payment steps; the PNR is issued after confirmation.",
                    draft));
        }

        boolean cancellationAction = lower.contains("cancel my flight")
                || lower.contains("cancel my booking")
                || lower.contains("cancel this flight")
                || lower.contains("cancel this booking")
                || lower.contains("cancel the flight")
                || lower.contains("cancel the booking")
                || lower.contains("please cancel")
                || lower.matches(".*\\bcancel it\\b.*");
        boolean hypothetical = lower.contains("if i cancel")
                || lower.contains("cancellation policy")
                || lower.contains("cancellation fee");
        if (cancellationAction && !hypothetical) {
            return Optional.of(draftStateTurn(
                    "Your " + journey + " selection is not confirmed, so there is no "
                            + "ticket to cancel. If you mean a confirmed trip, share its "
                            + "six-character booking reference (PNR) so I use the correct booking.",
                    draft));
        }

        boolean baggageForDraft = (lower.contains("baggage") || lower.contains("luggage"))
                && (lower.contains("my booking")
                    || lower.contains("this booking")
                    || lower.contains("that booking")
                    || lower.contains("my flight")
                    || lower.contains("this flight"));
        if (baggageForDraft) {
            if (draft.fareId() != null) {
                return Optional.of(draftStateTurn(
                        "Your " + journey + " selection is not confirmed yet, but a fare "
                                + "has been selected. Review the selected fare in checkout "
                                + "for its checked and cabin baggage allowance before payment.",
                        draft));
            }
            return Optional.of(draftStateTurn(
                    "Your " + journey + " selection is not confirmed and no fare has been "
                            + "chosen yet. The baggage allowance depends on the fare you select; "
                            + "choose a fare first and I will show the exact allowance before payment.",
                    draft));
        }
        return Optional.empty();
    }

    private CommerceTurn draftStateTurn(
            String answer,
            CommerceDtos.BookingDraftView draft) {
        return new CommerceTurn(
                answer,
                new CommerceDtos.CommercePayload(
                        CommerceDtos.CommerceType.BOOKING_DETAILS_REQUIRED,
                        draft, List.of(), List.of(), null),
                List.of("BookingDraftTool"));
    }

    private String journey(CommerceDtos.BookingDraftView draft) {
        if (draft.origin() != null && draft.destination() != null) {
            return draft.origin() + " to " + draft.destination();
        }
        return "new-flight";
    }

    private CommerceTurn details(
            String answer, CommerceDtos.BookingDraftView draft) {
        List<CommerceDtos.AirportView> choices = draft.origin() == null
                ? catalog.airports() : catalog.destinationsFrom(draft.origin());
        return new CommerceTurn(
                answer,
                new CommerceDtos.CommercePayload(
                        CommerceDtos.CommerceType.BOOKING_DETAILS_REQUIRED,
                        draft, List.of(), choices, null),
                List.of("TravelCatalogTool"));
    }

    private String resolve(String supplied, String existing) {
        if (supplied == null || supplied.isBlank()) {
            return existing;
        }
        return simulator.resolveAirport(supplied);
    }

    public record CommerceTurn(
            String answer,
            CommerceDtos.CommercePayload payload,
            List<String> toolsUsed,
            List<String> policyDocumentCodes,
            List<String> followups) {

        public CommerceTurn(
                String answer,
                CommerceDtos.CommercePayload payload,
                List<String> toolsUsed) {
            this(
                    answer,
                    payload,
                    toolsUsed,
                    policyCodes(answer, payload),
                    commerceFollowups(answer, payload));
        }

        private static List<String> policyCodes(
                String answer,
                CommerceDtos.CommercePayload payload) {
            java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
            String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
            CommerceDtos.CommerceType type = payload == null ? null : payload.type();
            if (type == CommerceDtos.CommerceType.CANCELLATION_QUOTE
                    || lower.contains("cancel") || lower.contains("refund")) {
                codes.add("KB-AIR-004");
            }
            if (lower.contains("baggage") || lower.contains("luggage")) {
                codes.add("KB-AIR-003");
            }
            if (lower.contains("seat")
                    || type == CommerceDtos.CommerceType.CHECKOUT_READY
                    || type == CommerceDtos.CommerceType.FLIGHT_OPTIONS) {
                codes.add("KB-AIR-005");
            }
            if (lower.contains("check-in") || lower.contains("boarding")) {
                codes.add("KB-AIR-002");
            }
            if (type == CommerceDtos.CommerceType.BOOKING_DETAILS_REQUIRED
                    || type == CommerceDtos.CommerceType.FLIGHT_OPTIONS
                    || type == CommerceDtos.CommerceType.CHECKOUT_READY
                    || type == CommerceDtos.CommerceType.TICKET_CONFIRMED
                    || type == CommerceDtos.CommerceType.OWNED_BOOKING_OPTIONS) {
                codes.add("KB-AIR-001");
            }
            return List.copyOf(codes);
        }

        private static List<String> commerceFollowups(
                String answer,
                CommerceDtos.CommercePayload payload) {
            CommerceDtos.CommerceType type = payload == null ? null : payload.type();
            if (type == CommerceDtos.CommerceType.FLIGHT_OPTIONS) {
                return List.of(
                        "As a Passenger, would you like to compare the baggage included with each fare?",
                        "As a Passenger, would you like to compare refundable fare options?");
            }
            if (type == CommerceDtos.CommerceType.CANCELLATION_QUOTE) {
                return List.of(
                        "As a Passenger, would you like to review the refund timeline?",
                        "As a Passenger, would you like to keep the booking unchanged?");
            }
            return List.of(
                    "As a Passenger, would you like to review the next booking step?",
                    "As a Passenger, would you like to review the governing fare conditions?");
        }
    }
}
