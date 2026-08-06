package com.unitedair.ai.tools;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.notifications.FlightNotificationService;
import com.unitedair.ai.notifications.NotificationDtos;
import com.unitedair.ai.providers.CheckInProvider;
import com.unitedair.ai.providers.ProviderCapability;
import com.unitedair.ai.providers.StatusProvider;
import com.unitedair.ai.providers.simulator.SimulatorBookingProvider;
import com.unitedair.ai.providers.simulator.SimulatorStatusProvider;
import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SRS 2.5 CheckInStatusTool - live flight status and online check-in.
 *
 * <p>Serves FR-005 and FR-014.
 *
 * <p>Reporting status is a read and happens immediately. Performing check-in issues a
 * boarding pass, so it goes through the two-phase action flow; this tool reports
 * eligibility, and {@code ActionService} performs the check-in once confirmed.
 */
@Component
public class CheckInStatusTool {

    public static final String NAME = "CheckInStatusTool";

    private final StatusProvider statusProvider;
    private final CheckInProvider checkInProvider;
    private final BookingManagementTool bookingTool;
    private final ToolInvocationLogger logger;
    private final CurrentUser currentUser;
    private final FlightNotificationService notifications;

    @Autowired
    public CheckInStatusTool(StatusProvider statusProvider,
                             CheckInProvider checkInProvider,
                             BookingManagementTool bookingTool,
                             ToolInvocationLogger logger,
                             CurrentUser currentUser,
                             FlightNotificationService notifications) {
        this.statusProvider = statusProvider;
        this.checkInProvider = checkInProvider;
        this.bookingTool = bookingTool;
        this.logger = logger;
        this.currentUser = currentUser;
        this.notifications = notifications;
    }

    public CheckInStatusTool(JdbcClient jdbc,
                             BookingRepository bookings,
                             BookingManagementTool bookingTool,
                             ToolInvocationLogger logger) {
        this(
                new SimulatorStatusProvider(jdbc),
                new SimulatorBookingProvider(bookings),
                bookingTool,
                logger,
                new CurrentUser(),
                null);
    }

    public CheckInStatusTool(JdbcClient jdbc,
                             BookingRepository bookings,
                             BookingManagementTool bookingTool,
                             ToolInvocationLogger logger,
                             CurrentUser currentUser,
                             FlightNotificationService notifications) {
        this(
                new SimulatorStatusProvider(jdbc),
                new SimulatorBookingProvider(bookings),
                bookingTool,
                logger,
                currentUser,
                notifications);
    }

    @Tool(name = NAME, description = """
            Use only for verified flight status, gate or terminal, check-in eligibility or
            proposal, or a gate or terminal subscription. Do not use for generic status.
            """)
    public ToolDtos.GovernedToolResult invoke(ToolDtos.CheckInStatusToolRequest request) {
        if (request == null || request.operation() == null) {
            throw new ApiExceptions.BadRequest("A check-in/status operation is required.");
        }
        CurrentUser.Authenticated actor = currentUser.require();
        BookingAccess access = new BookingAccess(actor.role(), actor.id());
        Map<String, Object> arguments = request.arguments();
        String operation = request.operation().name();
        String pnr = string(arguments, "pnr");
        return switch (request.operation()) {
            case GET_FLIGHT_STATUS -> ToolDtos.GovernedToolResult.from(
                    NAME,
                    operation,
                    flightStatus(
                            string(arguments, "flightNo"),
                            parseDate(string(arguments, "date")),
                            actor.role().name()).envelope());
            case GET_CHECKIN_ELIGIBILITY -> ToolDtos.GovernedToolResult.from(
                    NAME,
                    operation,
                    checkInEligibility(pnr, access).envelope());
            case PROPOSE_CHECK_IN -> {
                ToolDtos.CheckInEligibility eligibility =
                        checkInEligibility(pnr, access).data();
                if (!eligibility.eligible()) {
                    throw new ApiExceptions.Conflict(eligibility.reason());
                }
                yield ToolDtos.GovernedToolResult.proposal(
                        NAME,
                        operation,
                        Map.of(
                                "pnr", eligibility.pnr(),
                                "eligible", true,
                                "confirmationRequired", true),
                        "Check-in has only been proposed. A boarding pass is issued only "
                                + "after explicit authenticated confirmation.");
            }
            case SUBSCRIBE_GATE_TERMINAL -> {
                if (notifications == null) {
                    throw new ApiExceptions.BadRequest(
                            "Flight notifications are unavailable outside the application context.");
                }
                LocalDate date = parseDate(string(arguments, "date"));
                NotificationDtos.SubscriptionView subscription = notifications.subscribe(
                        actor,
                        new NotificationDtos.SubscriptionRequest(
                                string(arguments, "flightNo"), date));
                yield new ToolDtos.GovernedToolResult(
                        NAME,
                        operation,
                        "EXECUTED",
                        subscription,
                        "Gate and terminal notifications are active for "
                                + subscription.flightNo() + " on " + subscription.date() + ".",
                        false);
            }
        };
    }

    // -------------------------------------------------------------- direct API ---

    public Outcome<ToolDtos.FlightStatusView> flightStatus(String flightNo, LocalDate date, String actorRole) {
        String normalised = flightNo == null ? null : flightNo.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        boolean defaultedToToday = date == null;
        LocalDate flightDate = date == null ? LocalDate.now() : date;

        Map<String, Object> logged = new LinkedHashMap<>();
        logged.put("operation", "GET_FLIGHT_STATUS");
        logged.put("flightNo", normalised);
        logged.put("flightDate", flightDate.toString());
        disclose(logged, statusProvider.capability());

        ToolDtos.ToolOutcome outcome = logger.invoke(NAME, actorRole, "ORCHESTRATOR_WORKER", logged, () -> {
            if (normalised == null || normalised.isBlank()) {
                throw new ApiExceptions.BadRequest("Please tell me the flight number, for example UA101.");
            }

            ToolDtos.FlightStatusView status =
                    statusProvider.status(normalised, flightDate);

            return new ToolInvocationLogger.ToolResult(
                    status, summarise(status, defaultedToToday));
        });

        ToolDtos.FlightStatusView data =
                outcome.data() instanceof ToolDtos.FlightStatusView view ? view : null;
        return new Outcome<>(outcome, data);
    }

    public Outcome<ToolDtos.CheckInEligibility> checkInEligibility(String pnr, String actorRole) {
        return checkInEligibility(pnr, new BookingAccess(Role.fromString(actorRole), null));
    }

    public Outcome<ToolDtos.CheckInEligibility> checkInEligibility(
            String pnr, BookingAccess access) {
        Map<String, Object> logged = new LinkedHashMap<>();
        logged.put("pnrProvided", pnr != null && !pnr.isBlank());
        logged.put("operation", "GET_CHECKIN_ELIGIBILITY");
        disclose(logged, checkInProvider.capability());

        ToolDtos.ToolOutcome outcome = logger.invoke(
                NAME, access.role().name(), "ORCHESTRATOR_WORKER", logged, () -> {
            ToolDtos.BookingView booking = bookingTool.find(pnr, access)
                    .orElseThrow(() -> new ApiExceptions.NotFound(
                            "No booking found for that reference."));
            ToolDtos.CheckInEligibility eligibility =
                    checkInProvider.eligibility(booking);

            String summary = eligibility.eligible()
                    ? "Check-in is open for " + eligibility.pnr() + "."
                    : "Check-in unavailable for " + eligibility.pnr() + ": " + eligibility.reason();
            return new ToolInvocationLogger.ToolResult(eligibility, summary);
        });

        ToolDtos.CheckInEligibility data =
                outcome.data() instanceof ToolDtos.CheckInEligibility eligibility
                        ? eligibility : null;
        return new Outcome<>(outcome, data);
    }

    private static String summarise(
            ToolDtos.FlightStatusView s,
            boolean defaultedToToday) {
        String base = "%s %s-%s on %s is %s".formatted(
                s.flightNo(), s.origin(), s.destination(), s.flightDate(), s.status().toLowerCase(Locale.ROOT));
        if (s.delayMinutes() > 0) {
            base += " by " + s.delayMinutes() + " minutes (now departing " + s.estimatedDeparture() + ")";
        }
        if (s.gate() != null) {
            base += ", terminal " + s.terminal() + " gate " + s.gate();
        }
        if (defaultedToToday) {
            base += " (today was used because no flight date was supplied)";
        }
        return base + ".";
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception invalid) {
            throw new ApiExceptions.BadRequest(
                    "Use a valid flight date in yyyy-MM-dd format.");
        }
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null || value.toString().isBlank()
                ? null : value.toString().trim();
    }

    public ProviderCapability statusCapability() {
        return statusProvider.capability();
    }

    public ProviderCapability checkInCapability() {
        return checkInProvider.capability();
    }

    private static void disclose(
            Map<String, Object> request, ProviderCapability capability) {
        request.put("provider", capability.provider());
        request.put("providerLive", capability.live());
    }

    public record Outcome<T>(ToolDtos.ToolOutcome envelope, T data) { }
}
