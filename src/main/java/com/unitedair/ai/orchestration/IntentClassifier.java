package com.unitedair.ai.orchestration;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.unitedair.ai.privacy.PiiType;
import com.unitedair.ai.privacy.RedactionResult;
import com.unitedair.ai.identity.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Routing pattern of SRS 2.2: decides whether a turn is answered from the Knowledge
 * Base, from a tool, from both, or escalated - and extracts the parameters a tool will need.
 *
 * <h2>Trusted extraction before semantic planning</h2>
 * This component extracts security-sensitive entities and supplies a conservative candidate
 * route. {@link AdaptiveRoutePlanner} remains the lead intent planner for normal turns and
 * can select all allowlisted tools and topics needed for a multi-part request.
 *
 * <p>Mandatory safety and human-review triggers remain deterministic backstops. They are
 * authorization boundaries, not a replacement for normal language understanding.
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final Pattern FLIGHT_NO = Pattern.compile("\\b([A-Z]{2}\\s?\\d{2,4})\\b");
    private static final Pattern ROUTE_ARROW = Pattern.compile(
            "(?i)\\bfrom\\s+([A-Za-z]{3,20}(?:\\s+[A-Za-z]{2,20})?)"
                    + "\\s+to\\s+([A-Za-z]{3,20}(?:\\s+"
                    + "(?!(?:today|tomorrow|on|next|in|for)\\b)[A-Za-z]{2,20})?)"
                    + "(?=\\s+(?:today|tomorrow|on|next|in|for)\\b|[?.!,]|$)");
    private static final Pattern ROUTE_REVERSED = Pattern.compile(
            "(?i)\\bto\\s+([A-Za-z ]{3,20}?)\\s+from\\s+([A-Za-z ]{3,20}?)"
                    + "(?=\\s+(?:today|tomorrow|on|next|in)\\b|[?.!,]|$)");
    private static final Pattern ROUTE_NATURAL = Pattern.compile(
            "(?i)(?:^|[.!?]\\s+)"
                    + "(?:(?:show|find|search)(?:\\s+me)?\\s+)?(?:flights?\\s+)?"
                    + "([A-Za-z]{3,20}(?:\\s+[A-Za-z]{2,20})?)"
                    + "\\s+to\\s+([A-Za-z]{3,20}(?:\\s+"
                    + "(?!(?:today|tomorrow|on|next|in|flight|flights)\\b)"
                    + "[A-Za-z]{2,20})?)(?:\\s+flights?)?"
                    + "(?=\\s+(?:today|tomorrow|on|next|in)\\b|[?.!,]|$)");
    private static final Pattern ROUTE_DASH =
            Pattern.compile("\\b([A-Z]{3})\\s*(?:-|to|->|→)\\s*([A-Z]{3})\\b");
    private static final Pattern ROUTE_SHORTHAND = Pattern.compile(
            "(?i)\\b(BLR|DEL|BOM|HYD|MAA|CCU|DXB|LHR|SIN)"
                    + "\\s+(?:2|to)\\s+([A-Za-z]{3,20})\\b");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final Pattern DMY_DATE =
            Pattern.compile("\\b(\\d{1,2})[/\\- ](\\d{1,2}|[A-Za-z]{3,9})[/\\- ](\\d{4})\\b");
    private static final Pattern DAY_MONTH =
            Pattern.compile("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+of?\\s*([A-Za-z]{3,9})\\b");
    private static final Pattern LOOSE_FROM = Pattern.compile(
            "(?i)\\bfrom\\s+([A-Za-z]{3,20}?)(?=\\s+(?:today|tomorrow|on|next|in)\\b|[?.!,]|$)");
    private static final Pattern LOOSE_TO = Pattern.compile(
            "(?i)\\bto\\s+([A-Za-z]{3,20}?)(?=\\s+(?:today|tomorrow|on|next|in)\\b|[?.!,]|$)");
    private static final Pattern EXCESS_BAGGAGE_KG = Pattern.compile(
            "(?i)\\b(?:excess|extra|over(?:\\s+the)?\\s+allowance(?:\\s+by)?)\\s*"
                    + "(\\d{1,2})\\s*(?:kg|kilograms?)\\b"
                    + "|\\b(\\d{1,2})\\s*(?:kg|kilograms?)\\s*"
                    + "(?:excess|extra|over(?:\\s+the)?\\s+allowance)\\b");
    private static final Pattern MEAL_CODE = Pattern.compile(
            "(?i)\\b(AVML|BBML|BLML|CHML|DBML|FPML|GFML|HNML|KSML|LCML|MOML|NLML|VGML|VLML)\\b");
    private static final Pattern UNSAFE_TRANSPORT_ACTION = Pattern.compile(
            "\\b(?:bring|carry|take|pack|transport|hide|smuggle)\\b");
    private static final Pattern WEAPON_OR_EXPLOSIVE = Pattern.compile(
            "\\b(?:bombs?|weapons?|explosive\\s+devices?|guns?)\\b");
    private static final Pattern AIRPORT_OR_FLIGHT_CONTEXT = Pattern.compile(
            "\\b(?:airport|flight|plane|aircraft|onboard|on\\s+board|baggage|luggage|cabin)\\b");

    // Escalation is checked first and wins outright.
    private static final List<Trigger> ESCALATION_TRIGGERS = List.of(
            new Trigger("PREGNANCY_POLICY_GAP", "pregnant", "pregnancy"),
            new Trigger("REGULATORY_INTERPRETATION",
                    "which law wins", "give me legal advice", "court jurisdiction",
                    "take the airline to court", "legally liable for my claim"),
            new Trigger("FRAUD_ALLEGATION", "fraud", "fraudulent", "unauthorised charge",
                    "unauthorized charge", "scam", "someone used my card",
                    "stolen card was used", "stolen card used"),
            new Trigger("REFUND_DENIAL", "refund was denied", "refund denied", "refused my refund",
                    "rejected my refund", "not refunded", "still no refund"),
            new Trigger("BOOKING_DISPUTE", "dispute", "double charged", "charged twice",
                    "wrong amount", "overcharged"),
            new Trigger("COMPLAINT", "complaint", "complain", "unacceptable", "speak to a manager",
                    "speak to a human", "human agent", "human assistance",
                    "escalate", "grievance", "consumer forum"),
            new Trigger("MEDICAL_EMERGENCY", "severe chest pain", "medical emergency",
                    "cannot breathe", "can't breathe", "unconscious", "collapsed",
                    "severe allergic reaction", "anaphylaxis"),
            new Trigger("SAFETY_SECURITY_THREAT", "bomb threat", "bomb on", "security threat",
                    "weapon on board", "hijack", "explosive device on",
                    "found an explosive device", "there is an explosive device"));

    private static final List<String> FLIGHT_SEARCH_WORDS = List.of(
            "flight", "flights", "fly", "available", "availability", "book a", "cheapest",
            "fare from", "how much", "price", "seats left", "departures");

    private static final List<String> STATUS_WORDS = List.of(
            "status", "delayed", "delay", "on time", "on-time", "cancelled", "canceled",
            "gate", "terminal", "boarding", "diverted", "landed", "departed");

    private static final List<String> CHECKIN_WORDS = List.of(
            "check in", "check-in", "checkin", "check me in", "boarding pass", "web check");

    private static final List<String> REFUND_WORDS = List.of(
            "refund", "refnd", "refundable", "cancel my", "cancel it", "cancel this",
            "cancellation fee", "cancel the booking", "money back",
            "how much will i get back", "how much would i get back",
            "reschedule", "change my flight", "change fee");

    private static final List<String> BOOKING_WORDS = List.of(
            "booking", "pnr", "itinerary", "reservation", "my ticket", "my flight",
            "record locator");

    private static final List<String> SEAT_WORDS = List.of(
            "seat map", "seat selection", "choose a seat", "select a seat", "window seat",
            "aisle seat", "extra legroom", "seat fee", "seat type", "selection fee",
            "upgrade path", "exit row");

    /** Words that mean the question is about policy, not about a specific booking. */
    private static final List<String> POLICY_WORDS = List.of(
            "policy", "rule", "rules", "allowance", "allowed", "procedure", "eligible",
            "eligibility", "what is the", "explain", "how do i", "requirement", "documents",
            "regulation", "dgca", "iata", "compliance", "baggage", "visa",
            "cancellation", "cancel", "refund", "refundable", "fare", "fee",
            "no-show", "no show", "overbooking", "waitlist", "denied boarding",
            "luggage", "oversold", "bump", "pregnant", "pregnancy",
            "power bank", "infant", "stroller", "special meal", "special service",
            "special assistance", "wheelchair",
            "cat", "dog", "pet in", "travel in the hold",
            "seat type", "seat selection", "selection fee", "upgrade path",
            "frequent-flyer", "frequent flyer", "ffp", "enrolment", "accrual",
            "redemption");

    public OrchestrationDtos.Classification classify(String query, RedactionResult redaction) {
        return classify(query, redaction, Role.PASSENGER);
    }

    public OrchestrationDtos.Classification classify(
            String query,
            RedactionResult redaction,
            Role actorRole) {
        String lower = query.toLowerCase(Locale.ROOT);
        Role role = actorRole == null ? Role.PASSENGER : actorRole;

        String pnr = redaction.first(PiiType.PNR).orElse(null);
        String flightNo = extractFlightNo(query);
        String[] route = extractRoute(query);
        LocalDate date = extractDate(query);
        String cabin = extractCabin(lower);
        String mealCode = extractMealCode(query);
        Integer excessBaggageKg = extractExcessBaggageKg(query);
        String routeType = extractRouteType(lower);

        // 1. Escalation wins outright.
        for (Trigger trigger : ESCALATION_TRIGGERS) {
            if (trigger.matches(lower)) {
                return build(OrchestrationDtos.Intent.ESCALATION, OrchestrationDtos.ToolTarget.NONE,
                        0.95, "Matched escalation trigger: " + trigger.reason(),
                        route, date, cabin, pnr, flightNo, trigger.reason(), List.of());
            }
        }

        if (isUnsafeTransportRequest(lower)) {
            OrchestrationDtos.Classification safetyPolicy = build(
                    OrchestrationDtos.Intent.KB_LOOKUP,
                    OrchestrationDtos.ToolTarget.NONE,
                    1.0,
                    "Grounded prohibited-items safety policy",
                    route, date, cabin, pnr, flightNo,
                    "SAFETY_POLICY", List.of("baggage", "safety"));
            return withMetadata(
                    safetyPolicy, List.of(), Set.of("KB-AIR-003"));
        }

        if (isSmallTalk(lower)) {
            return build(OrchestrationDtos.Intent.SMALL_TALK, OrchestrationDtos.ToolTarget.NONE,
                    0.98, "Conversational greeting or acknowledgement",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        if (isClearlyOutOfScope(lower)) {
            return build(OrchestrationDtos.Intent.OUT_OF_SCOPE, OrchestrationDtos.ToolTarget.NONE,
                    0.96, "Clearly outside the UnitedAir travel scope",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        if (containsAny(lower, List.of(
                "forgot my password", "forgot password", "reset my password",
                "account password", "cannot sign in", "can't sign in"))) {
            OrchestrationDtos.Classification clarification = build(
                    OrchestrationDtos.Intent.CLARIFICATION,
                    OrchestrationDtos.ToolTarget.NONE,
                    0.97,
                    "Account access help is handled without collecting credentials",
                    route, date, cabin, pnr, flightNo, null, List.of());
            return withMetadata(clarification, List.of("account"), Set.of());
        }

        if (ambiguityDecision(lower) == AnswerRequirements.AmbiguityDecision.GENUINELY_AMBIGUOUS) {
            return withMetadata(build(
                    OrchestrationDtos.Intent.CLARIFICATION,
                    OrchestrationDtos.ToolTarget.NONE,
                    0.95,
                    "Multiple airline meanings remain plausible; ask the user to choose",
                    route, date, cabin, pnr, flightNo, null, List.of()),
                    List.of("request"), Set.of());
        }

        if (role == Role.ADMIN && isSystemAdministrationQuestion(lower)) {
            return build(OrchestrationDtos.Intent.OUT_OF_SCOPE, OrchestrationDtos.ToolTarget.NONE,
                    0.98,
                    "System-governance questions are handled in the Admin workspace, not "
                            + "the passenger-policy knowledge base",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        boolean mentionsPolicy = containsAny(lower, POLICY_WORDS);

        if (pnr != null
                && containsAny(lower, List.of(
                        "estimated refund", "cancellation fee", "amount paid"))
                && !isRefundStatus(lower)) {
            return build(
                    OrchestrationDtos.Intent.TOOL_PLUS_KB,
                    OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                    0.96,
                    "Specific booking refund arithmetic uses the fixed quote workflow",
                    route, date, cabin, pnr, flightNo, null, List.of("fare-rule"));
        }

        if (pnr == null
                && containsAny(lower, STATUS_WORDS)
                && containsAny(lower, List.of(
                        "booking reference", "booking refernce",
                        "record locator", "the pnr", "my pnr"))) {
            boolean refundContext = containsAny(lower, List.of(
                    "refund", "refnd", "pending refunds", "refund cases"));
            return missingPnr(
                    refundContext
                            ? OrchestrationDtos.ToolTarget.REFUND_STATUS
                            : OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
                    refundContext
                            ? "Refund status requires a resolved booking"
                            : "Booking status requires a resolved booking",
                    route, date, cabin, flightNo, List.of());
        }

        if (isCompoundOperationalDataQuery(lower)) {
            return build(
                    OrchestrationDtos.Intent.TOOL_CALL,
                    OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY,
                    0.94,
                    "Multi-topic request for current authorized operational state",
                    route, date, cabin, pnr, flightNo, null, categoryHints(lower));
        }

        // Refund operations are classified before generic booking and policy rules.
        // "Status" on its own is never enough to invoke flight status.
        if (isRefundCaseList(lower)) {
            if (!role.atLeast(Role.AIRLINE_STAFF)) {
                return build(
                        OrchestrationDtos.Intent.OUT_OF_SCOPE,
                        OrchestrationDtos.ToolTarget.NONE,
                        0.99,
                        "Passenger attempted to list refund cases",
                        route, date, cabin, pnr, flightNo, null, List.of());
            }
            LocalDate dueDate = extractRefundDueDate(lower);
            List<String> refundFilters = new ArrayList<>();
            if (containsAny(lower, List.of("pending", "still pending"))) {
                refundFilters.add("status:PENDING");
            } else if (containsAny(lower, List.of("processing", "in progress"))) {
                refundFilters.add("status:PROCESSING");
            } else if (containsAny(lower, List.of("contact needed", "need contact"))) {
                refundFilters.add("status:CONTACT_NEEDED");
            } else if (containsAny(lower, List.of("failed", "failure"))) {
                refundFilters.add("status:FAILED");
            } else if (containsAny(lower, List.of("completed", "settled"))) {
                refundFilters.add("status:COMPLETED");
            }
            return build(
                    OrchestrationDtos.Intent.TOOL_CALL,
                    OrchestrationDtos.ToolTarget.REFUND_CASES,
                    0.98,
                    "Authorized refund case queue request",
                    route, dueDate, cabin, pnr, flightNo, null, refundFilters);
        }

        if (isOperationalDecisionQuery(lower)) {
            if (!role.atLeast(Role.AIRLINE_STAFF)) {
                return build(
                        OrchestrationDtos.Intent.OUT_OF_SCOPE,
                        OrchestrationDtos.ToolTarget.NONE,
                        0.99,
                        "Passenger attempted to query internal operational decisions",
                        route, date, cabin, pnr, flightNo, null, List.of());
            }
            List<String> decisionHints = new ArrayList<>();
            if (containsAny(lower, List.of("refund", "cancellation"))) {
                decisionHints.add("decision:REFUND_APPROVAL");
            }
            if (containsAny(lower, List.of("upgrade", "seat authorization"))) {
                decisionHints.add("decision:UPGRADE_AUTHORIZATION");
            }
            if (containsAny(lower, List.of("boarding override", "check-in override"))) {
                decisionHints.add("decision:BOARDING_OVERRIDE");
            }
            if (containsAny(lower, List.of(
                    "special service exception", "special service exceptions",
                    "special-service exception", "special-service exceptions",
                    "assistance exception", "assistance exceptions"))) {
                decisionHints.add("decision:SPECIAL_SERVICE_EXCEPTION");
            }
            if (containsAny(lower, List.of("approved", "confirmed"))) {
                decisionHints.add("outcome:APPROVED");
            } else if (containsAny(lower, List.of("rejected", "denied"))) {
                decisionHints.add("outcome:REJECTED");
            } else if (lower.contains("proposed")) {
                decisionHints.add("outcome:PROPOSED");
            }
            return build(
                    OrchestrationDtos.Intent.TOOL_CALL,
                    OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS,
                    0.97,
                    "Authorized operational decision audit request",
                    route, date, cabin, pnr, flightNo, null, decisionHints);
        }

        if (isRefundStatus(lower)) {
            if (pnr == null) {
                return missingPnr(
                        OrchestrationDtos.ToolTarget.REFUND_STATUS,
                        "Refund status requires a resolved booking",
                        route, date, cabin, flightNo, List.of());
            }
            return build(
                    OrchestrationDtos.Intent.TOOL_CALL,
                    OrchestrationDtos.ToolTarget.REFUND_STATUS,
                    0.97,
                    "Refund status request",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        if (pnr == null && referencesPersonalBooking(lower)) {
            if (containsAny(lower, REFUND_WORDS)) {
                return missingPnr(
                        OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                        "A personal refund or cancellation request needs a resolved booking",
                        route, date, cabin, flightNo, List.of("fare-rule"));
            }
            if (containsAny(lower, CHECKIN_WORDS)) {
                return missingPnr(
                        OrchestrationDtos.ToolTarget.CHECK_IN,
                        "A personal check-in request needs a resolved booking",
                        route, date, cabin, flightNo, List.of("sop"));
            }
            return missingPnr(
                    OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
                    "A personal booking lookup needs an explicitly resolved booking",
                    route, date, cabin, flightNo, List.of());
        }

        if (pnr == null && lower.contains("[air-pnr-redacted]")) {
            if (containsAny(lower, REFUND_WORDS)) {
                return missingPnr(
                        OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                        "A prior PNR was redacted and must be supplied again for a refund lookup",
                        route, date, cabin, flightNo, List.of("fare-rule"));
            }
            if (containsAny(lower, CHECKIN_WORDS)) {
                return missingPnr(
                        OrchestrationDtos.ToolTarget.CHECK_IN,
                        "A prior PNR was redacted and must be supplied again for check-in",
                        route, date, cabin, flightNo, List.of("sop"));
            }
            if (containsAny(lower, BOOKING_WORDS) || lower.contains("it")) {
                return missingPnr(
                        OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
                        "A prior PNR was redacted and must be supplied again for a booking lookup",
                        route, date, cabin, flightNo, List.of());
            }
        }

        if (pnr == null && isCancellationAction(lower)) {
            return missingPnr(
                    OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                    "A cancellation action needs an explicitly resolved booking",
                    route, date, cabin, flightNo, List.of("fare-rule"));
        }

        // A status follow-up can use the flight/date safely resolved from the current
        // session's booking relationship.
        if (pnr != null && flightNo != null && mentionsFlightStatusContext(lower)) {
            return build(OrchestrationDtos.Intent.TOOL_CALL,
                    OrchestrationDtos.ToolTarget.FLIGHT_STATUS,
                    0.94, "Resolved booking flight with a live-status question",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        if (containsAny(lower, List.of(
                "book a ticket", "book ticket", "book a flight", "book flight",
                "book tickets", "book me a ticket", "book me a flight",
                "i want to book", "i would like to book", "i'd like to book",
                "i need to book", "buy a ticket", "purchase a ticket",
                "reserve a flight", "make a booking"))) {
            List<String> missing = new ArrayList<>();
            if (route == null || route[0] == null) {
                missing.add("origin");
            }
            if (route == null || route[1] == null) {
                missing.add("destination");
            }
            if (date == null) {
                missing.add("travelDate");
            }
            OrchestrationDtos.Classification booking = build(
                    OrchestrationDtos.Intent.BOOK_FLIGHT,
                    OrchestrationDtos.ToolTarget.BOOKING_CREATE,
                    0.98,
                    "Explicit passenger booking request",
                    route, date, cabin, pnr, flightNo, null, List.of("fare-rule"));
            return withMetadata(booking, missing, Set.of());
        }

        if (pnr != null && containsAny(lower, List.of(
                "find alternatives", "alternative flight", "rebook because",
                "flight was cancelled", "flight was canceled", "disruption options"))) {
            return build(OrchestrationDtos.Intent.TOOL_CALL,
                    OrchestrationDtos.ToolTarget.DISRUPTION_RECOVERY,
                    0.96, "Disrupted booking with a recovery request",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        // 2. A PNR plus a refund or cancellation question: quote the real numbers, and
        //    ground them in the fee matrix. This is the archetypal TOOL_PLUS_KB turn.
        if (pnr != null && containsAny(lower, REFUND_WORDS)) {
            return build(OrchestrationDtos.Intent.TOOL_PLUS_KB, OrchestrationDtos.ToolTarget.REFUND_QUOTE,
                    0.92, "PNR present with a refund or change question",
                    route, date, cabin, pnr, flightNo, null, List.of("fare-rule"));
        }

        // 3. Check-in for a specific booking.
        if (pnr != null && containsAny(lower, CHECKIN_WORDS)) {
            return build(OrchestrationDtos.Intent.TOOL_PLUS_KB, OrchestrationDtos.ToolTarget.CHECK_IN,
                    0.9, "PNR present with a check-in question",
                    route, date, cabin, pnr, flightNo, null, List.of("sop"));
        }

        // 4. Any other question about a specific booking.
        if (pnr != null) {
            OrchestrationDtos.Classification bookingPolicy = build(
                    OrchestrationDtos.Intent.TOOL_PLUS_KB,
                    OrchestrationDtos.ToolTarget.BOOKING_LOOKUP,
                    0.88, "PNR present",
                    route, date, cabin, pnr, flightNo, null, categoryHints(lower));
            return withMetadata(
                    bookingPolicy, List.of(), documentCodeHints(lower));
        }

        // Check-in without a booking reference is a policy question about timings,
        // documents or procedure. It must be resolved before the generic word "flight"
        // is allowed to trigger an availability search.
        if (containsAny(lower, CHECKIN_WORDS) && !isSpecialServiceQuery(lower)) {
            OrchestrationDtos.Classification checkInPolicy = build(
                    OrchestrationDtos.Intent.KB_LOOKUP, OrchestrationDtos.ToolTarget.NONE,
                    0.9, "Check-in policy question without a specific booking",
                    route, date, cabin, pnr, flightNo, null, List.of("sop"));
            return withMetadata(checkInPolicy, List.of(), Set.of("KB-AIR-002"));
        }

        // 5. Flight status by flight number.
        if (flightNo != null && mentionsFlightStatusContext(lower)) {
            return build(OrchestrationDtos.Intent.TOOL_CALL, OrchestrationDtos.ToolTarget.FLIGHT_STATUS,
                    0.9, "Flight number with a status question",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        // Flight-specific meal availability is operational state. The policy can explain
        // meal codes, but only the dated simulator/provider row can say whether a code is
        // actually offered on this flight.
        if (flightNo != null && isMealAvailabilityQuery(lower, mealCode)) {
            OrchestrationDtos.Classification meal = build(
                    date == null
                            ? OrchestrationDtos.Intent.CLARIFICATION
                            : OrchestrationDtos.Intent.TOOL_PLUS_KB,
                    OrchestrationDtos.ToolTarget.MEAL_AVAILABILITY,
                    0.94,
                    "Dated flight meal availability request",
                    route, date, cabin, pnr, flightNo, null, List.of("sop"));
            meal = withOperationalFields(meal, mealCode, null, null);
            return withMetadata(
                    meal,
                    date == null ? List.of("travelDate") : List.of(),
                    Set.of("KB-AIR-006"));
        }

        // A concrete excess-baggage amount must use the approved effective tariff. Missing
        // tariff dimensions are collected explicitly rather than guessed from a nearby KB
        // table or a stale prior route.
        if (isExcessBaggageQuoteQuery(lower)) {
            List<String> missing = new ArrayList<>();
            if (excessBaggageKg == null) {
                missing.add("excessBaggageKg");
            }
            if (routeType == null) {
                missing.add("routeType");
            }
            if (cabin == null) {
                missing.add("cabin");
            }
            OrchestrationDtos.Classification baggage = build(
                    missing.isEmpty()
                            ? OrchestrationDtos.Intent.TOOL_PLUS_KB
                            : OrchestrationDtos.Intent.CLARIFICATION,
                    OrchestrationDtos.ToolTarget.EXCESS_BAGGAGE_QUOTE,
                    0.94,
                    "Concrete excess-baggage tariff request",
                    route, date, cabin, pnr, flightNo, null, List.of("fare-rule"));
            baggage = withOperationalFields(
                    baggage, null, excessBaggageKg, routeType);
            return withMetadata(baggage, missing, Set.of("KB-AIR-003"));
        }

        // 6. Availability search: a route was given, or clear search wording.
        if (route != null || (containsAnyWholePhrase(lower, FLIGHT_SEARCH_WORDS) && !mentionsPolicy)) {
            if (route != null) {
                if (date == null) {
                    OrchestrationDtos.Classification clarification = build(
                            OrchestrationDtos.Intent.CLARIFICATION,
                            OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                            0.9,
                            "Flight search is missing a required travel date",
                            route, null, cabin, pnr, flightNo, null, List.of("fare-rule"));
                    return withMetadata(
                            clarification, List.of("travelDate"), Set.of());
                }
                return build(OrchestrationDtos.Intent.TOOL_PLUS_KB, OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                        0.9, "Route detected in the question",
                        route, date, cabin, pnr, flightNo, null, List.of("fare-rule"));
            }
            String looseOrigin = extractLoose(LOOSE_FROM, query);
            String looseDestination = extractLoose(LOOSE_TO, query);
            List<String> missing = new ArrayList<>();
            if (looseOrigin == null) {
                missing.add("origin");
            }
            if (looseDestination == null) {
                missing.add("destination");
            }
            OrchestrationDtos.Classification clarification = build(
                    OrchestrationDtos.Intent.CLARIFICATION,
                    OrchestrationDtos.ToolTarget.FLIGHT_SEARCH,
                    0.9,
                    "Flight search is missing required route information",
                    new String[]{looseOrigin, looseDestination},
                    date, cabin, pnr, flightNo, null, List.of("fare-rule"));
            return withMetadata(clarification, missing, Set.of());
        }

        if (flightNo != null && containsAny(lower, SEAT_WORDS)) {
            return build(OrchestrationDtos.Intent.TOOL_CALL, OrchestrationDtos.ToolTarget.SEAT_MAP,
                    0.92, "Flight number with a seat-map request",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        // 7. Seat questions without a booking are policy questions about seat products.
        if (containsAny(lower, SEAT_WORDS)) {
            OrchestrationDtos.Classification seatPolicy = build(
                    OrchestrationDtos.Intent.KB_LOOKUP, OrchestrationDtos.ToolTarget.NONE,
                    0.8, "Seat selection question without a specific booking",
                    route, date, cabin, pnr, flightNo, null, List.of("fare-rule", "policy-manual"));
            return withMetadata(
                    seatPolicy, List.of(), documentCodeHints(lower));
        }

        // 8. A booking question with no PNR: ask the KB how bookings work, and the
        //    follow-up will collect the reference.
        if (containsAny(lower, BOOKING_WORDS) && !mentionsPolicy) {
            return build(OrchestrationDtos.Intent.KB_LOOKUP, OrchestrationDtos.ToolTarget.NONE,
                    0.7, "Booking question with no PNR supplied",
                    route, date, cabin, pnr, flightNo, null, List.of());
        }

        // 9. Everything else is a policy question - the largest category by far, and the
        //    one the Knowledge Base exists to serve.
        OrchestrationDtos.Classification policy = build(
                OrchestrationDtos.Intent.KB_LOOKUP, OrchestrationDtos.ToolTarget.NONE,
                mentionsPolicy ? 0.85 : 0.6,
                mentionsPolicy ? "Policy wording detected" : "Default route: Knowledge Base lookup",
                route, date, cabin, pnr, flightNo, null, categoryHints(lower));
        return withMetadata(policy, List.of(), documentCodeHints(lower));
    }

    // --------------------------------------------------------------- extraction ---

    private static String extractFlightNo(String query) {
        Matcher m = FLIGHT_NO.matcher(query.toUpperCase(Locale.ROOT));
        while (m.find()) {
            String candidate = m.group(1).replace(" ", "");
            // Exclude regulation references such as CAR-7 and document codes.
            if (!candidate.startsWith("CAR") && !candidate.startsWith("KB")) {
                return candidate;
            }
        }
        return null;
    }

    private static String[] extractRoute(String query) {
        Matcher dash = ROUTE_DASH.matcher(query.toUpperCase(Locale.ROOT));
        if (dash.find()) {
            return new String[]{dash.group(1), dash.group(2)};
        }
        Matcher shorthand = ROUTE_SHORTHAND.matcher(query);
        if (shorthand.find()) {
            return new String[]{
                    shorthand.group(1).toUpperCase(Locale.ROOT),
                    shorthand.group(2)
            };
        }
        Matcher arrow = ROUTE_ARROW.matcher(query);
        if (arrow.find()) {
            String from = arrow.group(1).trim();
            String to = arrow.group(2).trim();
            // "from Monday to Friday" is not a route.
            if (!isDayName(from) && !isDayName(to)) {
                return new String[]{from, to};
            }
        }
        Matcher reversed = ROUTE_REVERSED.matcher(query);
        if (reversed.find()) {
            String destination = reversed.group(1).trim();
            int nestedTo = destination.toLowerCase(Locale.ROOT).lastIndexOf(" to ");
            if (nestedTo >= 0) {
                destination = destination.substring(nestedTo + 4).trim();
            }
            return new String[]{reversed.group(2).trim(), destination};
        }
        Matcher natural = ROUTE_NATURAL.matcher(query);
        if (natural.find()) {
            String from = natural.group(1).trim();
            String to = natural.group(2).trim();
            if (!isQuestionLead(from) && !isDayName(from) && !isDayName(to)) {
                return new String[]{from, to};
            }
        }
        return null;
    }

    private static boolean isQuestionLead(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return Set.of(
                "how", "what", "when", "where", "who", "why", "can i", "do i",
                "show", "show me", "find", "find me", "search")
                .contains(lower);
    }

    private static boolean isDayName(String value) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate extractDate(String query) {
        Matcher iso = ISO_DATE.matcher(query);
        if (iso.find()) {
            try {
                return LocalDate.parse(iso.group(1));
            } catch (Exception ignored) {
                // fall through to the other formats
            }
        }

        Matcher dmy = DMY_DATE.matcher(query);
        if (dmy.find()) {
            LocalDate parsed = tryFormats(dmy.group(1) + " " + dmy.group(2) + " " + dmy.group(3));
            if (parsed != null) {
                return parsed;
            }
        }

        Matcher dayMonth = DAY_MONTH.matcher(query);
        if (dayMonth.find()) {
            LocalDate parsed = tryFormats(dayMonth.group(1) + " " + dayMonth.group(2) + " "
                    + LocalDate.now().getYear());
            // A bare "14 March" late in the year means next year's March.
            if (parsed != null) {
                return parsed.isBefore(LocalDate.now()) ? parsed.plusYears(1) : parsed;
            }
        }

        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("tomorrow") || lower.matches(".*\\btmrw\\b.*")) {
            return LocalDate.now().plusDays(1);
        }
        if (lower.contains("today") || lower.contains("tonight")) {
            return LocalDate.now();
        }
        if (lower.contains("next week")) {
            return LocalDate.now().plusWeeks(1);
        }
        if (lower.contains("next month")) {
            return LocalDate.now().plusMonths(1);
        }
        Matcher relativeDays = Pattern.compile(
                "(?i)(?:\\bin\\s+(\\d+|one|two|three|four|five|six|seven)\\s+days?\\b"
                        + "|\\b(\\d+|one|two|three|four|five|six|seven)\\s+days?"
                        + "\\s+from\\s+now\\b)")
                .matcher(query);
        if (relativeDays.find()) {
            String value = relativeDays.group(1) != null
                    ? relativeDays.group(1) : relativeDays.group(2);
            int days = switch (value.toLowerCase(Locale.ROOT)) {
                case "one" -> 1;
                case "two" -> 2;
                case "three" -> 3;
                case "four" -> 4;
                case "five" -> 5;
                case "six" -> 6;
                case "seven" -> 7;
                default -> Integer.parseInt(value);
            };
            return LocalDate.now().plusDays(days);
        }
        return null;
    }

    private static String extractMealCode(String query) {
        Matcher matcher = MEAL_CODE.matcher(query);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static Integer extractExcessBaggageKg(String query) {
        Matcher matcher = EXCESS_BAGGAGE_KG.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        try {
            int kilograms = Integer.parseInt(value);
            return kilograms > 0 ? kilograms : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String extractRouteType(String lower) {
        if (containsAny(lower, List.of("international", "overseas", "cross-border"))) {
            return "INTERNATIONAL";
        }
        if (containsAny(lower, List.of("domestic", "within india", "india flight"))) {
            return "DOMESTIC";
        }
        return null;
    }

    private static boolean isMealAvailabilityQuery(String lower, String mealCode) {
        return mealCode != null || containsAny(lower, List.of(
                "meal available", "meal availability", "meal service",
                "special meal on", "food on this flight"));
    }

    private static boolean isExcessBaggageQuoteQuery(String lower) {
        boolean mentionsExcess = containsAny(lower, List.of(
                "excess baggage", "extra baggage", "extra luggage",
                "over the allowance", "over my allowance"));
        boolean asksForPrice = containsAny(lower, List.of(
                "fee", "cost", "charge", "quote", "how much",
                "price", "pay for", "rate"));
        return mentionsExcess
                && (asksForPrice || extractExcessBaggageKg(lower) != null);
    }

    private static boolean isRefundCaseList(String lower) {
        if (containsAny(lower, List.of(
                "refund cases",
                "refund case queue",
                "refunds due",
                "refund queue",
                "passenger refunds"))) {
            return true;
        }
        boolean collectionLanguage = lower.contains("refunds")
                || lower.contains("refund requests")
                || lower.contains("refund request");
        return collectionLanguage
                && containsAny(lower, List.of(
                        "pending", "open", "still", "any", "which", "show", "list"));
    }

    private static boolean isCompoundOperationalDataQuery(String lower) {
        /*
         * Named approval/override/exception audit requests have a dedicated,
         * allowlisted decision worker. Do not let their multiple nouns promote
         * them to the broader semantic data agent.
         */
        if (isOperationalDecisionQuery(lower)) {
            return false;
        }
        if (containsAny(lower, List.of(
                "policy", "procedure", "rules", "guidance", "explain",
                "allowance", "eligibility", "refundable", "fare",
                "business class", "economy class", "premium economy",
                "cabin class"))) {
            return false;
        }
        boolean asksForCurrentState = containsAny(lower, List.of(
                "show", "list", "which", "current", "pending", "open",
                "status", "available", "tomorrow", "today", "need attention",
                "how many"));
        if (!asksForCurrentState) {
            return false;
        }
        int topics = 0;
        boolean refundState = containsAny(lower, List.of("refund", "refunds"));
        boolean escalationState = lower.contains("escalation");
        boolean bookingState = containsAny(lower, List.of("booking", "bookings"))
                && containsAny(lower, List.of(
                        "show", "list", "current", "status", "active"))
                && !containsAny(lower, List.of(
                        "booking reference", "booking refernce",
                        "record locator", "pnr"));
        boolean checkInState = containsAny(lower, List.of(
                "check-in state", "check in state", "checkin state",
                "checked in", "check-in status", "checkin status"));
        boolean flightState = containsAny(lower, List.of("flight", "flights"))
                && containsAny(lower, List.of(
                        "today", "tomorrow", "status", "scheduled", "departures"));
        boolean seatState = containsAny(lower, List.of(
                "available seats", "seats available", "seats left",
                "seat inventory", "open seats"));
        boolean paymentState = lower.contains("payment")
                && containsAny(lower, List.of(
                        "status", "pending", "completed", "failed"));
        boolean mealState = lower.contains("meal")
                && containsAny(lower, List.of(
                        "available on", "availability on", "for flight"));
        boolean specialServiceState = containsAny(lower, List.of(
                "special service request", "assistance request"))
                && containsAny(lower, List.of(
                        "status", "pending", "open", "current"));
        boolean auditState = lower.contains("audit")
                && containsAny(lower, List.of("show", "list", "events", "today"));
        topics += refundState ? 1 : 0;
        topics += escalationState ? 1 : 0;
        topics += bookingState ? 1 : 0;
        topics += checkInState ? 1 : 0;
        topics += flightState ? 1 : 0;
        topics += seatState ? 1 : 0;
        topics += paymentState ? 1 : 0;
        topics += mealState ? 1 : 0;
        topics += specialServiceState ? 1 : 0;
        topics += auditState ? 1 : 0;
        return topics >= 2;
    }

    private static boolean isOperationalDecisionQuery(String lower) {
        boolean namesDecision = containsAny(lower, List.of(
                "refund approval", "refund approvals",
                "upgrade authorization", "upgrade authorizations",
                "seat authorization", "boarding override", "boarding overrides",
                "check-in override", "special service exception",
                "special service exceptions", "special-service exception",
                "special-service exceptions", "assistance exception",
                "assistance exceptions", "operational decision",
                "operational decisions"));
        if (!namesDecision) {
            return false;
        }
        // A question about the governing procedure belongs to the KB. The deterministic
        // decision worker is only for querying persisted decision records.
        if (containsAny(lower, List.of(
                "procedure", "policy", "rule", "rules", "how does",
                "how should", "what is the"))) {
            return false;
        }
        return containsAny(lower, List.of(
                "show", "list", "find", "search", "audit", "log", "history",
                "today", "yesterday", "approved", "rejected", "proposed",
                "decision", "decisions"));
    }

    private static boolean isRefundStatus(String lower) {
        boolean mentionsRefund = Pattern.compile("\\b(?:refund(?:ed)?|refnd)\\b")
                .matcher(lower)
                .find();
        boolean asksForSettlementState = containsAny(lower, List.of(
                "status", "state", "progress", "pending", "processing",
                "processed", "completed", "complete", "where is",
                "arrived", "received", "did i get", "have i got",
                "have i received", "was refunded", "been refunded",
                "refund paid"))
                || (lower.contains("refunded")
                        && containsAny(lower, List.of("was ", "has ", "already")));
        boolean asksForAQuoteOrRule = containsAny(lower, List.of(
                "if i cancel", "what would", "how much", "estimate",
                "cancellation fee", "refund policy", "refundable"));
        return mentionsRefund && asksForSettlementState && !asksForAQuoteOrRule;
    }

    private static boolean isCancellationAction(String lower) {
        boolean cancellationVerb = Pattern.compile("\\b(?:cancel|cancle)\\b")
                .matcher(lower)
                .find();
        boolean bookingObject = Pattern.compile("\\b(?:flight|booking|trip|ticket|it)\\b")
                .matcher(lower)
                .find();
        boolean imperative = cancellationVerb
                && (bookingObject || containsAny(lower, List.of("please cancel", "want to cancel")));
        boolean asksForInformation = containsAny(lower, List.of(
                "policy", "rule", "fee", "charge", "what is", "how does",
                "how much", "if i cancel", "would i", "can i cancel"));
        return imperative && !asksForInformation;
    }

    private static boolean mentionsFlightStatusContext(String lower) {
        if (lower.contains("refund")) {
            return false;
        }
        return containsAny(lower, STATUS_WORDS);
    }

    private static LocalDate extractRefundDueDate(String lower) {
        Matcher matcher = Pattern.compile(
                "\\b(?:due\\s+)?(?:within|in)\\s+(\\d+|one|two|three|four|five|six|seven)\\s+days?\\b")
                .matcher(lower);
        if (!matcher.find()) {
            return null;
        }
        int days = switch (matcher.group(1)) {
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            default -> Integer.parseInt(matcher.group(1));
        };
        return LocalDate.now().plusDays(days);
    }

    private static LocalDate tryFormats(String value) {
        List<String> patterns = List.of("d M yyyy", "d MMM yyyy", "d MMMM yyyy");
        for (String pattern : patterns) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
            } catch (Exception ignored) {
                // try the next layout
            }
        }
        return null;
    }

    private static String extractCabin(String lower) {
        if (lower.contains("business")) {
            return "Business";
        }
        if (lower.contains("premium economy")) {
            return "Premium Economy";
        }
        if (lower.contains("economy")) {
            return "Economy";
        }
        if (lower.contains("first class")) {
            return "First";
        }
        return null;
    }

    private static String extractLoose(Pattern pattern, String query) {
        Matcher matcher = pattern.matcher(query);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static boolean isSmallTalk(String lower) {
        String normalised = lower.replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
        return normalised.matches("(?:hi+|he+y+|hello+)(?: there)?")
                || normalised.matches(
                        "(?:sup|wassup|wazzup|yo|what s up|whats up"
                                + "|how s it going|hows it going|how are things)")
                || normalised.matches("what are you do(?:p)?ing")
                || normalised.matches(
                        "(?:sup|wassup|wazzup|yo|what s up|whats up)[ ,]*"
                                + "(?:what are you do(?:p)?ing|how are you|how s it going)")
                || normalised.matches(
                "(?:hi|hello|hello there|hey|hey there|good morning|good afternoon|good evening|thanks|thank you"
                        + "|thank you very much|bye|goodbye|see you|how are you"
                        + "|what can you do|who are you)")
                || normalised.matches("(?:thanks|thank you)\\b.*")
                || normalised.matches("(?:good morning|good afternoon|good evening)\\b.*")
                || normalised.matches("(?:cheers|got it|understood|all right)(?:\\b.*)?")
                || normalised.matches("what can you (?:do|help(?: me)? with).*")
                || normalised.matches(
                        "(?:(?:hi|hello|hey)(?: there)? )?(?:can|could|would|please)"
                                + "(?: you)? (?:briefly )?introduce yourself(?: briefly)?");
    }

    private static boolean isSystemAdministrationQuestion(String lower) {
        return containsAny(lower, List.of(
                "knowledge document version", "knowledge base version",
                "ingested, approved", "approved, activated", "activated and audited",
                "retrieval quality", "embedding provenance", "audience isolation",
                "knowledge ingestion governance", "admin governance"));
    }

    static AnswerRequirements.AmbiguityDecision ambiguityDecision(String lower) {
        String normalised = lower.replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
        if (normalised.matches("(?:help|help me|something went wrong|it is not working"
                + "|this is not working|i need help|i need operations help"
                + "|hello i need operations help|can u help pls|book me something"
                + "|my trip has a problem|(?:give me )?all policies"
                + "|(?:give me )?all types of policy"
                + "|all types of policies|tell me everything|i have a problem"
                + "|there is an issue|i have an issue)")) {
            return AnswerRequirements.AmbiguityDecision.GENUINELY_AMBIGUOUS;
        }
        return AnswerRequirements.AmbiguityDecision.COMPLETE_INTENT;
    }

    private static boolean isClearlyOutOfScope(String lower) {
        return lower.matches("\\s*(?:what\\s+is\\s+|solve\\s+)?"
                        + "\\d+(?:\\.\\d+)?\\s*[-+*/]\\s*\\d+(?:\\.\\d+)?"
                        + "\\s*[?.!]*\\s*")
                || lower.matches(".*\\b(?:trig(?:onometry)?|trignom[a-z]*"
                        + "|algebra|calculus|geometry)\\b.*")
                || lower.matches(".*\\b(?:solve|calculate)\\b.*[0-9].*")
                || lower.matches(".*\\b(?:tell|write)\\b.*\\b(?:joke|poem|story)\\b.*")
                || lower.matches(".*\\b(?:zero|one|two|three|four|five|six|seven|eight|nine"
                        + "|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen"
                        + "|eighteen|nineteen|twenty)\\b\\s+"
                        + "(?:plus|minus|times|multiplied by|divided by)\\s+"
                        + "\\b(?:zero|one|two|three|four|five|six|seven|eight|nine"
                        + "|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen"
                        + "|eighteen|nineteen|twenty)\\b.*")
                || lower.matches(".*\\b(?:python|javascript|java|coding|write code|programming)\\b.*")
                || lower.matches(".*\\b(?:election|president|prime minister|photosynthesis"
                        + "|cricket score|stock price|weather forecast|earth is flat"
                        + "|flat earth|logic gate|linux terminal|terminal.*linux|medical advice"
                        + "|legal advice)\\b.*");
    }

    private static Set<String> documentCodeHints(String lower) {
        Set<String> hints = new LinkedHashSet<>();
        boolean specialServices = containsAny(lower, List.of(
                "wchr", "wheelchair", "unaccompanied minor", "unaccompanied-minor",
                "petc", "avih", "meda",
                "pet in", "travel in the hold", "special service", "special assistance"))
                || containsAnyWholePhrase(lower, List.of("cat", "dog"))
                || isNaturalUnaccompaniedMinorQuery(lower);
        if (containsAny(lower, List.of(
                "cancellation", "cancel", "refund", "refundable",
                "money back", "cancellation fee", "change fee"))) {
            hints.add("KB-AIR-004");
        }
        if (containsAny(lower, List.of(
                "booking workflow", "flight search results", "fare selection",
                "pnr issuance", "booking lifecycle"))) {
            hints.add("KB-AIR-001");
        }
        if (containsAny(lower, List.of(
                "baggage", "checked bag", "cabin bag", "luggage",
                "power bank", "lithium", "battery", "restricted item",
                "dangerous goods", "bomb", "explosive", "weapon", "knife",
                "gun", "firearm", "sharp object"))) {
            hints.add("KB-AIR-003");
        }
        if (containsAny(lower, List.of(
                "worldtracer", "property irregularity report", "pir filing",
                "mishandled baggage", "missing bag", "damaged bag",
                "montreal convention", "baggage liability"))) {
            hints.add("KB-AIR-009");
        }
        if (containsAny(lower, List.of(
                "cross-border", "cross border", "consumer protection",
                "conditions of carriage", "which jurisdiction",
                "international passenger rights"))) {
            hints.add("KB-AIR-010");
        }
        if (containsAny(lower, List.of(
                "special meal", "meal code", "vgml", "ksml", "dbml", "blml", "chml"))) {
            hints.add("KB-AIR-006");
        }
        if (containsAny(lower, SEAT_WORDS)) {
            hints.add("KB-AIR-005");
        }
        if (containsAny(lower, List.of(
                "fare class", "fare classes", "booking class",
                "business class", "economy class", "premium economy",
                "cabin class", "revenue band", "revenue bands",
                "yield rule", "yield rules"))) {
            hints.add("KB-AIR-005");
        }
        if (specialServices) {
            hints.add("KB-AIR-006");
        }
        if (!specialServices && containsAny(lower, List.of(
                "travel document", "documents are needed", "which documents",
                "documents do i need", "passport", "visa", "photo id"))) {
            hints.add("KB-AIR-002");
        }
        if (containsAny(lower, List.of(
                "frequent-flyer", "frequent flyer", "ffp", "enrolment", "accrual",
                "redemption", "loyalty tier"))) {
            hints.add("KB-AIR-006");
        }
        if (containsAny(lower, List.of("dgca", "car-7", "flight duty", "duty-time"))) {
            hints.add("KB-AIR-007");
        }
        if (containsAny(lower, List.of(
                "boarding override procedure", "gate-change sla", "gate change sla",
                "late-passenger rule", "late passenger rule"))) {
            hints.add("KB-AIR-007");
        }
        if (containsAny(lower, List.of(
                "no-show", "no show", "overbooking", "oversold", "bump",
                "waitlist", "denied boarding", "missed the first leg",
                "missed my first leg", "return sector", "return leg"))) {
            boolean staffHandling = containsAny(lower, List.of(
                    "handling", "override", "who approves", "approval procedure"));
            if (!staffHandling) {
                hints.add("KB-AIR-002");
            }
            hints.add("KB-AIR-004");
        }
        return Set.copyOf(hints);
    }

    /**
     * Narrows retrieval to the document categories a question is plainly about. This is the
     * Document Category filter of SRS 4.3.2 being driven by intent rather than by the user
     * having to name a category.
     */
    private static List<String> categoryHints(String lower) {
        List<String> hints = new ArrayList<>();
        if (containsAny(lower, List.of("dgca", "iata", "car-7", "regulation", "compliance",
                "montreal convention", "conditions of carriage"))) {
            hints.add("regulatory-circular");
        }
        if (containsAny(lower, List.of("fare", "refund", "cancellation", "price", "pricing",
                "booking class", "yield", "upgrade"))) {
            hints.add("fare-rule");
        }
        if (containsAny(lower, List.of("procedure", "check-in", "boarding", "handling",
                "wheelchair", "unaccompanied", "escort", "sop"))) {
            hints.add("sop");
        }
        return hints;
    }

    private static boolean isSpecialServiceQuery(String lower) {
        return containsAny(lower, List.of(
                "wchr", "wheelchair", "unaccompanied minor", "petc", "avih", "meda",
                "pet in", "travel in the hold"))
                || containsAnyWholePhrase(lower, List.of("cat", "dog"))
                || isNaturalUnaccompaniedMinorQuery(lower);
    }

    private static boolean isNaturalUnaccompaniedMinorQuery(String lower) {
        boolean child = containsAny(lower, List.of(
                "child", "children", "minor", "year-old", "year old",
                "son", "daughter"));
        boolean alone = containsAny(lower, List.of(
                "travelling alone", "traveling alone", "travel alone",
                "travels alone", "flying alone", "fly alone",
                "without an adult", "unaccompanied"));
        return child && alone;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsafeTransportRequest(String lower) {
        return UNSAFE_TRANSPORT_ACTION.matcher(lower).find()
                && WEAPON_OR_EXPLOSIVE.matcher(lower).find()
                && AIRPORT_OR_FLIGHT_CONTEXT.matcher(lower).find();
    }

    private static boolean containsAnyWholePhrase(String haystack, List<String> needles) {
        for (String needle : needles) {
            Pattern phrase = Pattern.compile(
                    "(?<![a-z0-9])" + Pattern.quote(needle) + "(?![a-z0-9])");
            if (phrase.matcher(haystack).find()) {
                return true;
            }
        }
        return false;
    }

    private OrchestrationDtos.Classification build(OrchestrationDtos.Intent intent,
                                                   OrchestrationDtos.ToolTarget tool,
                                                   double confidence,
                                                   String rationale,
                                                   String[] route,
                                                   LocalDate date,
                                                   String cabin,
                                                   String pnr,
                                                   String flightNo,
                                                   String escalationReason,
                                                   List<String> categoryHints) {
        log.debug("Intent {} tool={} ({})", intent, tool, rationale);
        return new OrchestrationDtos.Classification(
                intent, tool, confidence, rationale,
                route == null ? null : route[0],
                route == null ? null : route[1],
                date, cabin, pnr, flightNo, escalationReason, categoryHints,
                List.of(), Set.of());
    }

    private OrchestrationDtos.Classification withMetadata(
            OrchestrationDtos.Classification source,
            List<String> missingParameters,
            Set<String> documentCodeHints) {
        return new OrchestrationDtos.Classification(
                source.intent(), source.tool(), source.confidence(), source.rationale(),
                source.origin(), source.destination(), source.travelDate(), source.cabin(),
                source.pnr(), source.flightNo(), source.escalationReason(), source.categoryHints(),
                List.copyOf(missingParameters), Set.copyOf(documentCodeHints),
                source.mealCode(), source.excessBaggageKg(), source.routeType());
    }

    private OrchestrationDtos.Classification withOperationalFields(
            OrchestrationDtos.Classification source,
            String mealCode,
            Integer excessBaggageKg,
            String routeType) {
        return new OrchestrationDtos.Classification(
                source.intent(), source.tool(), source.confidence(), source.rationale(),
                source.origin(), source.destination(), source.travelDate(), source.cabin(),
                source.pnr(), source.flightNo(), source.escalationReason(), source.categoryHints(),
                source.missingParameters(), source.documentCodeHints(),
                mealCode, excessBaggageKg, routeType);
    }

    private OrchestrationDtos.Classification missingPnr(
            OrchestrationDtos.ToolTarget tool,
            String rationale,
            String[] route,
            LocalDate date,
            String cabin,
            String flightNo,
            List<String> categoryHints) {
        OrchestrationDtos.Classification clarification = build(
                OrchestrationDtos.Intent.CLARIFICATION, tool, 0.96, rationale,
                route, date, cabin, null, flightNo, null, categoryHints);
        return withMetadata(clarification, List.of("pnr"), Set.of());
    }

    private static boolean referencesPersonalBooking(String lower) {
        return containsAnyWholePhrase(lower, List.of(
                "my booking", "my current booking", "my active booking",
                "my upcoming booking", "my reservation", "my active reservation",
                "my upcoming reservation", "my ticket", "my current flight",
                "my active flight", "my upcoming flight"));
    }

    private record Trigger(String reason, String... phrases) {
        boolean matches(String lower) {
            for (String phrase : phrases) {
                if (lower.contains(phrase)) {
                    return true;
                }
            }
            return false;
        }
    }
}
