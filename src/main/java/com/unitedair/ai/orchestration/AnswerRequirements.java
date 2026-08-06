package com.unitedair.ai.orchestration;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Structured obligations extracted from one user turn.
 *
 * <p>Routing chooses where an answer comes from. Requirements describe everything the
 * answer must cover. Keeping these separate prevents a multi-part request from being
 * reduced to whichever single document happened to score highest.
 */
public record AnswerRequirements(
        Set<RequestedTopic> topics,
        Scope scope,
        Set<String> requiredCategories,
        boolean operationalStateRequired,
        boolean reviewOnly,
        boolean domesticOnly) {

    public AnswerRequirements(
            Set<RequestedTopic> topics,
            Scope scope,
            Set<String> requiredCategories,
            boolean operationalStateRequired,
            boolean reviewOnly) {
        this(topics, scope, requiredCategories, operationalStateRequired, reviewOnly, false);
    }

    public AnswerRequirements {
        topics = topics == null ? Set.of() : Set.copyOf(topics);
        scope = scope == null ? Scope.GENERAL_POLICY : scope;
        requiredCategories = requiredCategories == null
                ? Set.of() : Set.copyOf(requiredCategories);
    }

    /** Adds only allowlisted topics from the validated semantic route. */
    public AnswerRequirements withTopics(Set<RequestedTopic> semanticTopics) {
        if (semanticTopics == null || semanticTopics.isEmpty()
                || !topics.isEmpty()
                || !requiredCategories.isEmpty()) {
            return this;
        }
        Set<RequestedTopic> merged = new LinkedHashSet<>(topics);
        merged.addAll(semanticTopics);
        Set<String> categories = new LinkedHashSet<>(requiredCategories);
        if (semanticTopics.contains(RequestedTopic.BAGGAGE)
                && semanticTopics.contains(RequestedTopic.COMPLIANCE)) {
            categories.add("RESTRICTED_ITEMS");
        }
        return new AnswerRequirements(
                merged, scope, categories,
                operationalStateRequired, reviewOnly, domesticOnly);
    }

    public static AnswerRequirements from(
            String query,
            OrchestrationDtos.Classification classification) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT)
                .replace("[air-pnr-redacted]", " ")
                .replace('-', ' ')
                .replace("lithum", "lithium");
        Set<RequestedTopic> topics = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();

        if (classification != null) {
            switch (classification.tool()) {
                case FLIGHT_SEARCH, BOOKING_CREATE -> topics.add(RequestedTopic.FLIGHT_SEARCH);
                case BOOKING_LOOKUP -> topics.add(RequestedTopic.BOOKING);
                case REFUND_QUOTE -> {
                    topics.add(RequestedTopic.REFUND);
                    topics.add(RequestedTopic.CANCELLATION);
                }
                case REFUND_CASES, REFUND_STATUS -> topics.add(RequestedTopic.REFUND);
                case ESCALATION_QUEUE -> topics.add(RequestedTopic.AUDIT);
                case FLIGHT_STATUS -> topics.add(RequestedTopic.STATUS);
                case CHECK_IN -> topics.add(RequestedTopic.CHECK_IN);
                case SEAT_MAP -> topics.add(RequestedTopic.SEATS);
                case MEAL_AVAILABILITY -> topics.add(RequestedTopic.MEALS);
                case EXCESS_BAGGAGE_QUOTE -> {
                    topics.add(RequestedTopic.BAGGAGE);
                    topics.add(RequestedTopic.FARES);
                }
                case OPERATIONAL_DECISIONS -> topics.add(RequestedTopic.AUDIT);
                case OPERATIONAL_DATA_QUERY -> {
                    // Text-derived topics remain authoritative for flexible data reads.
                }
                case DISRUPTION_RECOVERY -> topics.add(RequestedTopic.DISRUPTION);
                case NONE -> {
                    // Text-derived topics below are authoritative for policy-only turns.
                }
            }
            if (classification.tool() == OrchestrationDtos.ToolTarget.BOOKING_LOOKUP) {
                categories.add("BOOKING_RESULT");
            }
            if (classification.tool()
                    == OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS
                    && classification.intent() == OrchestrationDtos.Intent.TOOL_CALL) {
                /*
                 * The decision names in an FR-030 audit query are structured
                 * filters, not requests for the complete refund, boarding and
                 * special-service policy chapters. Expanding them into policy
                 * obligations makes a valid audit result impossible to validate.
                 */
                return new AnswerRequirements(
                        topics,
                        Scope.OPERATIONAL,
                        categories,
                        true,
                        false,
                        lower.contains("domestic"));
            }
        }

        if (containsAny(
                lower,
                "booking process",
                "booking workflow",
                "flight booking",
                "book a flight",
                "make a booking",
                "create a booking",
                "reservation process",
                "reservation workflow",
                "record locator")) {
            topics.add(RequestedTopic.BOOKING);
        }
        boolean pnrIsTheRequestedSubject = classification == null
                || classification.tool() == OrchestrationDtos.ToolTarget.NONE
                || classification.tool() == OrchestrationDtos.ToolTarget.BOOKING_LOOKUP;
        if (pnrIsTheRequestedSubject && lower.contains("pnr")) {
            topics.add(RequestedTopic.BOOKING);
        }
        addIf(topics, RequestedTopic.REFUND, lower,
                "refund", "money back", "reimbursement");
        addIf(topics, RequestedTopic.CANCELLATION, lower,
                "cancel", "cancellation", "no-show", "no show");
        addIf(topics, RequestedTopic.CHECK_IN, lower,
                "check-in", "check in", "checkin", "boarding pass");
        if (containsAny(lower, "flight status", "on time", "delayed")
                || (containsAny(lower, "gate", "terminal")
                    && !containsAny(lower,
                            "gate-change", "gate change", "procedure", "policy", "rule"))) {
            topics.add(RequestedTopic.STATUS);
        }
        addIf(topics, RequestedTopic.BAGGAGE, lower,
                "baggage", "luggage", "cabin bag", "checked bag",
                "missing bag", "lost bag", "mishandled bag",
                "power bank", "restricted item", "dangerous goods",
                "bomb", "explosive", "weapon", "knife", "gun", "firearm",
                "sharp object");
        addIf(topics, RequestedTopic.SEATS, lower,
                "seat", "aisle", "window", "extra legroom");
        addIf(topics, RequestedTopic.FARES, lower,
                "fare", "price", "fee", "cost", "charge");
        addIf(topics, RequestedTopic.MEALS, lower,
                "meal", "food", "vegetarian", "vegan");
        addIf(topics, RequestedTopic.SPECIAL_SERVICES, lower,
                "special service", "special assistance", "wheelchair", "wchr",
                "unaccompanied minor", "meda", "petc", "avih", "pet");
        if (topics.contains(RequestedTopic.SPECIAL_SERVICES)
                && containsAny(lower, "from check-in to", "from check in to")) {
            topics.remove(RequestedTopic.CHECK_IN);
        }
        addIf(topics, RequestedTopic.DOCUMENTS, lower,
                "document", "passport", "visa", "photo id", "identification");
        addIf(topics, RequestedTopic.FFP, lower,
                "frequent flyer", "frequent-flyer", "ffp", "loyalty",
                "accrual", "redemption");
        addIf(topics, RequestedTopic.COMPLIANCE, lower,
                "dgca", "iata", "regulation", "compliance",
                "conditions of carriage");
        addIf(topics, RequestedTopic.AUDIT, lower,
                "audit", "decision trail", "reasoning trace");
        addIf(topics, RequestedTopic.DISRUPTION, lower,
                "disruption", "cancelled flight", "canceled flight",
                "rebook", "alternative flight");

        boolean asksSeatCatalogue = topics.contains(RequestedTopic.SEATS)
                && (lower.contains("domestic")
                    || lower.contains("seat type")
                    || (lower.contains("seat selection")
                        && containsAny(lower, "fare", "price", "cost", "fee")));
        if (asksSeatCatalogue) {
            categories.addAll(Set.of(
                    "STANDARD_ECONOMY",
                    "PREFERRED_ECONOMY",
                    "COMFORT",
                    "BUSINESS_WINDOW",
                    "BUSINESS_AISLE"));
        }
        if (containsAny(lower,
                "upgrade eligibility", "upgrade payment", "upgrade fee",
                "upgrade condition", "paid upgrade", "bid upgrade",
                "points upgrade", "eligible fare class")) {
            categories.add("UPGRADE_PATHWAYS");
        }
        if (topics.contains(RequestedTopic.FFP)) {
            addCategoryIf(categories, "FFP_EARNING", lower,
                    "earn", "earning", "accrual", "accrue", "miles earned");
            addCategoryIf(categories, "FFP_TIERS", lower,
                    "tier", "qualification", "qualify", "status miles");
            addCategoryIf(categories, "FFP_REDEMPTION", lower,
                    "redeem", "redemption", "award flight", "points required");
        }
        if (containsAny(lower,
                "fare class", "booking class", "revenue band", "yield band")) {
            categories.add("FARE_CLASS_REVENUE_BANDS");
        }
        if (containsAny(lower,
                "cabin seat configuration", "seat configuration",
                "cabin configuration", "cabin layout")) {
            categories.add("CABIN_CONFIGURATION");
        }
        if (containsAny(lower,
                "blocked seat", "blocked-seat", "seat block", "seat blocking")) {
            categories.add("SEAT_BLOCKING_RULES");
        }
        boolean bookingSpecificOperation = classification != null
                && classification.needsTool()
                && classification.pnr() != null;
        boolean explicitlyRequestsAllFarePolicy = containsAny(
                lower,
                "general",
                "overall policy",
                "all fare",
                "every fare",
                "policy matrix",
                "full cancellation policy");
        if (topics.contains(RequestedTopic.REFUND)
                && topics.contains(RequestedTopic.CANCELLATION)
                && (!bookingSpecificOperation || explicitlyRequestsAllFarePolicy)
                && containsAny(lower,
                        "partial refund", "partial-refund", "fare rule",
                        "cancellation rule", "cancellation and refund")) {
            categories.add("REFUND_FARE_RULES");
        }
        if (containsAny(lower, "no-show", "no show")) {
            categories.add("NO_SHOW_RULES");
        }
        if (containsAny(lower, "overbooking", "overbooked", "oversold")) {
            categories.add("OVERBOOKING_THRESHOLDS");
        }
        if (containsAny(lower, "waitlist", "wait list", "waitlisted")) {
            categories.add("WAITLIST_RULES");
        }
        if (containsAny(lower, "denied boarding", "denied-boarding", "bumped")) {
            categories.add("DENIED_BOARDING_RULES");
        }
        if (lower.contains("boarding override")) {
            categories.add("BOARDING_OVERRIDE_RULES");
        }
        if (containsAny(lower, "gate change sla", "gate change notification")) {
            categories.add("GATE_CHANGE_SLA");
        }
        if (containsAny(lower, "late passenger", "late-passenger")) {
            categories.add("LATE_PASSENGER_RULES");
        }
        if (lower.contains("codeshare") || lower.contains("interline")) {
            categories.add("CODESHARE_INTERLINE_RULES");
        }
        if (lower.contains("proration")) {
            categories.add("REVENUE_PRORATION_RULES");
        }
        if (containsAny(lower,
                "upgrade inventory", "upgrade pool", "award inventory")) {
            categories.add("FFP_UPGRADE_INVENTORY");
        }
        if (containsAny(lower,
                "retro credit", "retrocredit", "retroactive claim",
                "missing mileage", "missing miles")) {
            categories.add("FFP_RETRO_CREDIT");
        }
        if (lower.contains("dgca")
                && containsAny(lower, "obligation", "daily operation", "compliance")) {
            categories.add("DGCA_DAILY_OBLIGATIONS");
        }
        if (lower.contains("iata")
                && containsAny(lower, "obligation", "resolution", "compliance")) {
            categories.add("IATA_OPERATIONAL_RESOLUTIONS");
        }
        if (containsAny(lower,
                "sms obligation", "safety management system", "sms compliance")) {
            categories.add("SMS_OBLIGATIONS");
        }
        if (containsWholeWord(lower, "mel")
                || lower.contains("minimum equipment list")) {
            categories.add("MEL_PROCEDURE");
        }
        if (containsAny(lower,
                "aircraft substitution", "substitute aircraft",
                "disruption procedure", "disruption handling")) {
            categories.add("DISRUPTION_SUBSTITUTION_RULES");
        }
        if (containsAny(lower,
                "agent commission", "travel agent commission", "agency commission")) {
            categories.add("AGENT_COMMISSION_RULES");
        }
        if ((containsWholeWord(lower, "gds") && containsWholeWord(lower, "bsp"))
                || lower.contains("bsp reconciliation")) {
            categories.add("GDS_BSP_RULES");
        }
        if (lower.contains("conditions of carriage")) {
            categories.add("CONDITIONS_OF_CARRIAGE_BOUNDARY");
        }
        if (containsAny(lower, "cross border", "cross-border")) {
            categories.add("CROSS_BORDER_SOURCE_RULES");
        }
        if (containsAny(lower, "car 7", "ftl", "dtl", "flight time limit")) {
            categories.add("FTL_DTL_LIMITS");
        }
        if (containsAny(lower,
                "augmented crew", "minimum rest", "crew rest",
                "rest and augmented", "rest or augmented")) {
            categories.add("REST_AUGMENTED_CREW_RULES");
        }
        if (topics.contains(RequestedTopic.BAGGAGE)
                && containsAny(lower,
                        "pir", "property irregularity report",
                        "missing bag", "lost bag", "mishandled baggage")) {
            categories.add("PIR_FILING_RULES");
        }
        if (lower.contains("worldtracer")
                || (topics.contains(RequestedTopic.BAGGAGE)
                    && containsAny(lower, "tracing milestone", "tracing timeline"))) {
            categories.add("WORLDTRACER_MILESTONES");
        }
        if (topics.contains(RequestedTopic.BAGGAGE)
                && containsAny(lower,
                        "claim document", "supporting document",
                        "what documents", "documents for claim")) {
            categories.add("BAGGAGE_CLAIM_DOCUMENTS");
        }
        if (topics.contains(RequestedTopic.BAGGAGE)
                && containsAny(lower,
                        "montreal", "liability limit", "sdr")) {
            categories.add("MONTREAL_LIABILITY_BOUNDARY");
        }
        if (topics.contains(RequestedTopic.BAGGAGE)
                && containsAny(lower, "all baggage", "baggage types", "baggage categories")) {
            categories.addAll(Set.of(
                    "CABIN_BAGGAGE", "CHECKED_BAGGAGE", "EXCESS_BAGGAGE",
                    "RESTRICTED_ITEMS", "SPORTS_EQUIPMENT", "MOBILITY_AIDS"));
        }
        if (topics.contains(RequestedTopic.BAGGAGE)) {
            boolean asksRestrictedItemPlacement = containsAny(
                    lower,
                    "power bank", "lithium", "battery", "restricted item",
                    "dangerous goods", "bomb", "explosive", "weapon", "knife",
                    "gun", "firearm", "sharp object");
            boolean asksBaggageCapacity = containsAny(
                    lower,
                    "allowance", "how much", "how many", "weight", " kg", "piece",
                    "baggage limit", "baggage limits", "bag limit", "bag limits",
                    "luggage limit", "luggage limits");
            boolean asksMishandledBaggage = categories.contains("PIR_FILING_RULES")
                    || categories.contains("WORLDTRACER_MILESTONES")
                    || categories.contains("BAGGAGE_CLAIM_DOCUMENTS")
                    || categories.contains("MONTREAL_LIABILITY_BOUNDARY");
            if (!asksRestrictedItemPlacement || asksBaggageCapacity) {
                addCategoryIf(categories, "CABIN_BAGGAGE", lower,
                        "cabin baggage", "cabin bag", "cabin and checked baggage",
                        "carry-on", "carry on");
                if (!asksMishandledBaggage || asksBaggageCapacity) {
                    addCategoryIf(categories, "CHECKED_BAGGAGE", lower,
                            "checked baggage", "checked bag",
                            "checked and cabin baggage");
                }
            }
            addCategoryIf(categories, "EXCESS_BAGGAGE", lower,
                    "excess baggage", "excess rule", "extra baggage",
                    "overweight", "over the allowance");
            addCategoryIf(categories, "RESTRICTED_ITEMS", lower,
                    "power bank", "power-bank", "lithium", "battery", "restricted item",
                    "dangerous goods", "bomb", "explosive", "weapon", "knife",
                    "gun", "firearm", "sharp object");
        }
        if (topics.contains(RequestedTopic.SPECIAL_SERVICES)) {
            categories.add("SPECIAL_SERVICES");
            boolean asksServiceCatalogue = containsAny(
                    lower, "special service", "special services", "special assistance")
                    && containsAny(
                            lower, "provided", "available", "types", "options", "offer");
            if (asksServiceCatalogue) {
                categories.addAll(Set.of(
                        "WHEELCHAIR", "UNACCOMPANIED_MINOR",
                        "PETC", "AVIH", "MEDA"));
            }
            addCategoryIf(categories, "WHEELCHAIR", lower, "wheelchair", "wchr");
            addCategoryIf(categories, "UNACCOMPANIED_MINOR", lower,
                    "unaccompanied minor", "unaccompanied-minor");
            if (containsWholeWord(lower, "um")) {
                categories.add("UNACCOMPANIED_MINOR");
            }
            addCategoryIf(categories, "PETC", lower, "petc", "pet in cabin");
            addCategoryIf(categories, "AVIH", lower, "avih", "animal in hold");
            addCategoryIf(categories, "MEDA", lower, "meda", "medical clearance");
            boolean asksDetailedRules = containsAny(
                    lower,
                    "eligibility", "eligible", "deadline", "deadlines",
                    "required document", "required documents",
                    "what documents", "procedure");
            if (asksDetailedRules) {
                if (categories.contains("WHEELCHAIR")) {
                    categories.add("WHEELCHAIR_RULES");
                }
                if (categories.contains("UNACCOMPANIED_MINOR")) {
                    categories.add("UNACCOMPANIED_MINOR_RULES");
                }
                if (categories.contains("PETC")) {
                    categories.add("PETC_RULES");
                }
                if (categories.contains("AVIH")) {
                    categories.add("AVIH_RULES");
                }
                if (categories.contains("MEDA")) {
                    categories.add("MEDA_RULES");
                }
            }
        }
        if (topics.contains(RequestedTopic.MEALS)) {
            addCategoryIf(categories, "MEAL_VGML", lower, "vgml");
            addCategoryIf(categories, "MEAL_KSML", lower, "ksml");
        }
        if (lower.contains("policy")
                && topics.contains(RequestedTopic.CANCELLATION)
                && (!bookingSpecificOperation || explicitlyRequestsAllFarePolicy)) {
            categories.add("GENERAL_CANCELLATION_REFUND_POLICY");
        }
        if (classification != null
                && classification.needsTool()
                && (topics.contains(RequestedTopic.BOOKING) || lower.contains("pnr"))
                && containsAny(lower, "status", "show my booking", "booking details", "pnr")) {
            categories.add("BOOKING_RESULT");
        }

        boolean operational = classification != null && classification.needsTool();
        Scope scope = operational
                ? Scope.OPERATIONAL
                : classification != null && classification.pnr() != null
                        ? Scope.BOOKING_SPECIFIC : Scope.GENERAL_POLICY;
        boolean reviewOnly = containsAny(lower,
                "policy first", "only explain", "quote only", "if i cancel",
                "if i cancelled", "if i canceled", "what would happen",
                "would i get", "cancellation fee");
        return new AnswerRequirements(
                topics, scope, categories, operational, reviewOnly,
                lower.contains("domestic"));
    }

    public Set<String> documentCodeHints() {
        Set<String> documents = new LinkedHashSet<>();
        if (topics.contains(RequestedTopic.BOOKING)) {
            documents.add("KB-AIR-001");
        }
        if (topics.contains(RequestedTopic.CANCELLATION)
                || topics.contains(RequestedTopic.REFUND)
                || (topics.contains(RequestedTopic.FARES)
                    && !topics.contains(RequestedTopic.SEATS)
                    && !requiredCategories.contains("FARE_CLASS_REVENUE_BANDS"))) {
            documents.add("KB-AIR-004");
        }
        boolean mishandledBaggage = requiredCategories.contains("PIR_FILING_RULES")
                || requiredCategories.contains("WORLDTRACER_MILESTONES")
                || requiredCategories.contains("BAGGAGE_CLAIM_DOCUMENTS")
                || requiredCategories.contains("MONTREAL_LIABILITY_BOUNDARY");
        boolean asksGeneralBaggagePolicy =
                requiredCategories.contains("CABIN_BAGGAGE")
                || requiredCategories.contains("CHECKED_BAGGAGE")
                || requiredCategories.contains("EXCESS_BAGGAGE")
                || requiredCategories.contains("RESTRICTED_ITEMS")
                || requiredCategories.contains("SPORTS_EQUIPMENT")
                || requiredCategories.contains("MOBILITY_AIDS");
        if (topics.contains(RequestedTopic.BAGGAGE)
                && (!mishandledBaggage || asksGeneralBaggagePolicy)) {
            documents.add("KB-AIR-003");
        }
        if (topics.contains(RequestedTopic.SEATS)) {
            documents.add("KB-AIR-005");
        }
        if (requiredCategories.contains("FARE_CLASS_REVENUE_BANDS")
                || requiredCategories.contains("CABIN_CONFIGURATION")
                || requiredCategories.contains("SEAT_BLOCKING_RULES")) {
            documents.add("KB-AIR-005");
        }
        if (requiredCategories.contains("REFUND_FARE_RULES")
                || requiredCategories.contains("NO_SHOW_RULES")
                || requiredCategories.contains("OVERBOOKING_THRESHOLDS")
                || requiredCategories.contains("WAITLIST_RULES")
                || requiredCategories.contains("DENIED_BOARDING_RULES")) {
            documents.add("KB-AIR-004");
        }
        if (requiredCategories.contains("BOARDING_OVERRIDE_RULES")
                || requiredCategories.contains("GATE_CHANGE_SLA")
                || requiredCategories.contains("LATE_PASSENGER_RULES")
                || requiredCategories.contains("CODESHARE_INTERLINE_RULES")
                || requiredCategories.contains("REVENUE_PRORATION_RULES")) {
            documents.add("KB-AIR-007");
        }
        if (requiredCategories.contains("FFP_UPGRADE_INVENTORY")
                || requiredCategories.contains("FFP_RETRO_CREDIT")) {
            documents.add("KB-AIR-006");
        }
        if (requiredCategories.contains("DGCA_DAILY_OBLIGATIONS")
                || requiredCategories.contains("IATA_OPERATIONAL_RESOLUTIONS")
                || requiredCategories.contains("SMS_OBLIGATIONS")
                || requiredCategories.contains("MEL_PROCEDURE")
                || requiredCategories.contains("DISRUPTION_SUBSTITUTION_RULES")
                || requiredCategories.contains("AGENT_COMMISSION_RULES")
                || requiredCategories.contains("GDS_BSP_RULES")
                || requiredCategories.contains("CONDITIONS_OF_CARRIAGE_BOUNDARY")
                || requiredCategories.contains("FTL_DTL_LIMITS")
                || requiredCategories.contains("REST_AUGMENTED_CREW_RULES")) {
            documents.add("KB-AIR-007");
        }
        if (requiredCategories.contains("CROSS_BORDER_SOURCE_RULES")) {
            documents.add("KB-AIR-010");
        }
        if (requiredCategories.contains("PIR_FILING_RULES")
                || requiredCategories.contains("WORLDTRACER_MILESTONES")
                || requiredCategories.contains("BAGGAGE_CLAIM_DOCUMENTS")
                || requiredCategories.contains("MONTREAL_LIABILITY_BOUNDARY")) {
            documents.add("KB-AIR-009");
        }
        if (topics.contains(RequestedTopic.SPECIAL_SERVICES)
                || topics.contains(RequestedTopic.MEALS)
                || topics.contains(RequestedTopic.FFP)) {
            documents.add("KB-AIR-006");
        }
        if (topics.contains(RequestedTopic.CHECK_IN)
                || topics.contains(RequestedTopic.DOCUMENTS)) {
            documents.add("KB-AIR-002");
        }
        if (topics.contains(RequestedTopic.COMPLIANCE)) {
            documents.add("KB-AIR-007");
        }
        return Set.copyOf(documents);
    }

    private static void addIf(
            Set<RequestedTopic> topics,
            RequestedTopic topic,
            String value,
            String... terms) {
        if (containsAny(value, terms)) {
            topics.add(topic);
        }
    }

    private static void addCategoryIf(
            Set<String> categories,
            String category,
            String value,
            String... terms) {
        if (containsAny(value, terms)) {
            categories.add(category);
        }
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWholeWord(String value, String word) {
        return value != null
                && java.util.regex.Pattern.compile(
                        "\\b" + java.util.regex.Pattern.quote(word) + "\\b")
                    .matcher(value)
                    .find();
    }

    public enum RequestedTopic {
        FLIGHT_SEARCH,
        BOOKING,
        REFUND,
        CANCELLATION,
        CHECK_IN,
        STATUS,
        BAGGAGE,
        SEATS,
        FARES,
        MEALS,
        SPECIAL_SERVICES,
        DOCUMENTS,
        FFP,
        COMPLIANCE,
        AUDIT,
        DISRUPTION
    }

    public enum Scope {
        GENERAL_POLICY,
        BOOKING_SPECIFIC,
        OPERATIONAL
    }

    public enum AmbiguityDecision {
        COMPLETE_INTENT,
        MISSING_REQUIRED_SLOT,
        GENUINELY_AMBIGUOUS
    }
}
