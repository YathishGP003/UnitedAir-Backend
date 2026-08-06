package com.unitedair.ai.tools;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.shared.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct tool endpoints (SRS 5).
 *
 * <p>These expose the same tools the orchestrator drives, so the frontend can render a
 * structured flight-results table or a seat map rather than asking the model to describe
 * one in prose. Every call still goes through {@code ToolInvocationLogger}, so a lookup made
 * from the UI appears in the audit trail exactly like one made during a conversation.
 */
@RestController
@RequestMapping("/tools")
@Tag(name = "Tools", description = "Direct access to the four registered airline tools")
public class ToolController {

    private final FlightSearchTool flightSearchTool;
    private final BookingManagementTool bookingTool;
    private final CheckInStatusTool checkInTool;
    private final EscalationTool escalationTool;
    private final SimulatorRepository simulator;
    private final CurrentUser currentUser;

    public ToolController(FlightSearchTool flightSearchTool,
                          BookingManagementTool bookingTool,
                          CheckInStatusTool checkInTool,
                          EscalationTool escalationTool,
                          SimulatorRepository simulator,
                          CurrentUser currentUser) {
        this.flightSearchTool = flightSearchTool;
        this.bookingTool = bookingTool;
        this.checkInTool = checkInTool;
        this.escalationTool = escalationTool;
        this.simulator = simulator;
        this.currentUser = currentUser;
    }

    private String role() {
        return currentUser.role().name();
    }

    private BookingAccess bookingAccess() {
        return BookingAccess.from(currentUser.require());
    }

    @PostMapping("/flights/search")
    @Operation(summary = "FlightSearchTool - availability, fares and seat counts")
    public ToolDtos.FlightSearchResult searchPost(@RequestBody ToolDtos.FlightSearchRequest request) {
        FlightSearchTool.Outcome outcome = flightSearchTool.search(request, role());
        return requireSuccess(outcome.envelope(), outcome.data());
    }

    /** GET form, matching the reference frontend's usage. */
    @GetMapping("/flights/search")
    @Operation(summary = "FlightSearchTool (query-parameter form)")
    public ToolDtos.FlightSearchResult searchGet(
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String cabin) {
        FlightSearchTool.Outcome outcome = flightSearchTool.search(
                new ToolDtos.FlightSearchRequest(origin, destination, date, cabin, 1, 0, 0), role());
        return requireSuccess(outcome.envelope(), outcome.data());
    }

    @GetMapping("/flights/{flightInstanceId}/seats")
    @Operation(summary = "Seat map for a departure (FR-009)")
    public List<ToolDtos.SeatOption> seatMap(@PathVariable Long flightInstanceId) {
        return flightSearchTool.seatMap(flightInstanceId);
    }

    @GetMapping("/flights/{flightNo}/meals")
    @Operation(summary = "FlightSearchTool - dated-flight meal availability")
    public ToolDtos.MealAvailability mealAvailability(
            @PathVariable String flightNo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String mealCode) {
        ToolDtos.ToolOutcome outcome = flightSearchTool.mealAvailabilityForFlight(
                flightNo, date, mealCode, role());
        ToolDtos.MealAvailability data =
                outcome.data() instanceof ToolDtos.MealAvailability value ? value : null;
        return requireSuccess(outcome, data);
    }

    @GetMapping("/baggage/excess-quote")
    @Operation(summary = "FlightSearchTool - effective excess-baggage simulator quote")
    public ToolDtos.ExcessBaggageQuote excessBaggageQuote(
            @RequestParam String routeType,
            @RequestParam String cabin,
            @RequestParam int excessKg) {
        ToolDtos.ToolOutcome outcome = flightSearchTool.quoteExcessBaggage(
                routeType, cabin, excessKg, role());
        ToolDtos.ExcessBaggageQuote data =
                outcome.data() instanceof ToolDtos.ExcessBaggageQuote value ? value : null;
        return requireSuccess(outcome, data);
    }

    @GetMapping("/booking/{pnr}")
    @Operation(summary = "BookingManagementTool - itinerary and fare conditions by PNR")
    public ToolDtos.BookingView booking(@PathVariable String pnr) {
        BookingManagementTool.Outcome<ToolDtos.BookingView> outcome =
                bookingTool.retrieve(pnr, bookingAccess());
        return requireSuccess(outcome.envelope(), outcome.data());
    }

    @GetMapping("/booking/{pnr}/refund-quote")
    @Operation(summary = "BookingManagementTool - refund eligibility and amount")
    public ToolDtos.RefundQuote refundQuote(@PathVariable String pnr) {
        BookingManagementTool.Outcome<ToolDtos.RefundQuote> outcome =
                bookingTool.refundQuote(pnr, bookingAccess());
        return requireSuccess(outcome.envelope(), outcome.data());
    }

    @GetMapping("/flightstatus/{flightNo}")
    @Operation(summary = "CheckInStatusTool - live flight status, gate and delay")
    public ToolDtos.FlightStatusView flightStatus(
            @PathVariable String flightNo,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CheckInStatusTool.Outcome<ToolDtos.FlightStatusView> outcome =
                checkInTool.flightStatus(flightNo, date, role());
        return requireSuccess(outcome.envelope(), outcome.data());
    }

    @GetMapping("/checkin/{pnr}")
    @Operation(summary = "CheckInStatusTool - check-in eligibility and window")
    public ToolDtos.CheckInEligibility checkInEligibility(@PathVariable String pnr) {
        CheckInStatusTool.Outcome<ToolDtos.CheckInEligibility> outcome =
                checkInTool.checkInEligibility(pnr, bookingAccess());
        return requireSuccess(outcome.envelope(), outcome.data());
    }

    @PostMapping("/escalation/create")
    @Operation(summary = "EscalationTool - route a case to a human queue")
    public ToolDtos.EscalationResult escalate(@RequestBody ToolDtos.EscalationRequest request) {
        EscalationTool.Outcome outcome = escalationTool.raise(request, role());
        return requireSuccess(outcome.envelope(), outcome.data());
    }

    @GetMapping("/airports")
    @Operation(summary = "Airports UnitedAir serves")
    public List<Map<String, Object>> airports() {
        return simulator.listAirports();
    }

    @GetMapping("/routes")
    @Operation(summary = "Scheduled routes")
    public List<Map<String, Object>> routes() {
        return simulator.listRoutes();
    }

    /**
     * A tool failure is a real error for a direct API call, unlike in the conversational
     * path where the orchestrator absorbs it and answers from the KB instead.
     */
    private <T> T requireSuccess(ToolDtos.ToolOutcome envelope, T data) {
        if (!envelope.success() || data == null) {
            throw new ApiExceptions.NotFound(
                    envelope.errorMessage() == null ? "The lookup returned no result." : envelope.errorMessage());
        }
        return data;
    }
}
