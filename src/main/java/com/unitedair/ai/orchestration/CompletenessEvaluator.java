package com.unitedair.ai.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.grounding.CitationBuilder;
import org.springframework.stereotype.Component;

/**
 * Deterministic semantic coverage gate applied after generation.
 *
 * <p>Retrieval relevance answers "did we find useful evidence?" This class answers the
 * separate question "did the response actually address every part of the request?" It
 * uses the structured request contract, evidence metadata and typed tool outcomes; it
 * never asks a second model to judge the first one.
 */
@Component
public class CompletenessEvaluator {

    public Result evaluate(
            AnswerRequirements requirements,
            String answer,
            List<RetrievalDtos.Ranked> evidence,
            List<ToolDtos.ToolOutcome> toolOutcomes) {
        if (requirements == null) {
            return Result.success();
        }
        String lower = normalise(answer);
        Set<AnswerRequirements.RequestedTopic> missingTopics = new LinkedHashSet<>();
        Set<String> missingCategories = new LinkedHashSet<>();
        List<String> gates = new ArrayList<>();

        for (String category : requirements.requiredCategories()) {
            if (!coversCategory(lower, category)
                    && !("RESTRICTED_ITEMS".equals(category)
                        && citesCategoryEvidence(answer, evidence, category))) {
                missingCategories.add(category);
            }
        }
        for (AnswerRequirements.RequestedTopic topic : requirements.topics()) {
            boolean categoryGroundedRestrictedItems =
                    (topic == AnswerRequirements.RequestedTopic.COMPLIANCE
                            || topic == AnswerRequirements.RequestedTopic.BAGGAGE)
                            && requirements.requiredCategories()
                                    .contains("RESTRICTED_ITEMS")
                            && !missingCategories.contains("RESTRICTED_ITEMS");
            if (!coversTopic(lower, topic) && !categoryGroundedRestrictedItems) {
                missingTopics.add(topic);
            }
        }
        if (!missingTopics.isEmpty()) {
            gates.add("MISSING_REQUESTED_TOPIC");
        }
        if (!missingCategories.isEmpty()) {
            gates.add("MISSING_REQUIRED_CATEGORY");
        }

        boolean hasSuccessfulTool = toolOutcomes != null
                && toolOutcomes.stream().anyMatch(ToolDtos.ToolOutcome::success);
        if (requirements.operationalStateRequired() && !hasSuccessfulTool) {
            gates.add("OPERATIONAL_STATE_NOT_ANSWERED");
        }
        boolean hasFailedTool = toolOutcomes != null
                && toolOutcomes.stream().anyMatch(outcome -> !outcome.success());
        if (hasFailedTool && contradictsFailedTool(lower)) {
            gates.add("FAILED_TOOL_CONTRADICTED");
        }
        if (requiresDomesticSeatScope(requirements)
                && (lower.contains("international fee")
                    || lower.contains("usd "))) {
            gates.add("OUT_OF_SCOPE_DETAIL");
        }
        if (looksTruncated(answer)) {
            gates.add("ANSWER_TRUNCATED");
        }
        if (!missingTopics.isEmpty() || !missingCategories.isEmpty()) {
            gates.add("INCOMPLETE_ANSWER");
        }
        return new Result(
                gates.isEmpty(),
                List.copyOf(gates),
                Set.copyOf(missingTopics),
                Set.copyOf(missingCategories));
    }

    /**
     * Compatibility inference for existing evaluator callers that predate the structured
     * request contract. A clearly complete seat-category evidence set creates the same
     * coverage obligation, which locks the reported partial-answer defect.
     */
    public AnswerRequirements infer(
            String answer,
            List<RetrievalDtos.Ranked> evidence) {
        String corpus = evidence == null ? "" : evidence.stream()
                .map(ranked -> ranked.chunk().content())
                .filter(java.util.Objects::nonNull)
                .reduce("", (left, right) -> left + "\n" + right);
        String normalisedCorpus = normalise(corpus);
        Set<String> seatCategories = Set.of(
                "STANDARD_ECONOMY",
                "PREFERRED_ECONOMY",
                "COMFORT",
                "BUSINESS_WINDOW",
                "BUSINESS_AISLE");
        boolean completeSeatEvidence = seatCategories.stream()
                .allMatch(category -> coversCategory(normalisedCorpus, category));
        if (completeSeatEvidence && normalise(answer).contains("business window")) {
            return new AnswerRequirements(
                    Set.of(
                            AnswerRequirements.RequestedTopic.SEATS,
                            AnswerRequirements.RequestedTopic.FARES),
                    AnswerRequirements.Scope.GENERAL_POLICY,
                    seatCategories,
                    false,
                    false);
        }
        return new AnswerRequirements(
                Set.of(),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of(),
                false,
                false);
    }

    private static boolean coversTopic(
            String answer,
            AnswerRequirements.RequestedTopic topic) {
        return switch (topic) {
            case FLIGHT_SEARCH -> containsAny(answer,
                    "flight", "departure", "no flights", "route");
            case BOOKING -> containsAny(answer,
                    "booking", "reservation", "pnr", "itinerary", "ticket");
            case REFUND -> containsAny(answer,
                    "refund", "money back", "refunded", "statutory taxes");
            case CANCELLATION -> containsAny(answer,
                    "cancel", "cancellation", "no-show", "forfeit");
            case CHECK_IN -> containsAny(answer,
                    "check-in", "check in", "boarding pass");
            case STATUS -> containsAny(answer,
                    "status", "on time", "on_time", "delayed", "cancelled",
                    "canceled", "departed", "landed", "gate", "terminal");
            case BAGGAGE -> containsAny(answer,
                    "baggage", "bag", "luggage");
            case SEATS -> containsAny(answer,
                    "seat", "aisle", "window", "legroom");
            case FARES -> containsAny(answer,
                    "fare", "fee", "cost", "charge", "inr", "included");
            case MEALS -> containsAny(answer,
                    "meal", "food", "vegetarian", "vegan");
            case SPECIAL_SERVICES -> containsAny(answer,
                    "special service", "special assistance", "wheelchair",
                    "wchr", "wchc", "unaccompanied", "meda", "petc", "avih");
            case DOCUMENTS -> containsAny(answer,
                    "document", "passport", "visa", "photo id",
                    "government id", "identification");
            case FFP -> containsAny(answer,
                    "frequent flyer", "ffp", "loyalty", "accrual", "redemption");
            case COMPLIANCE -> containsAny(answer,
                    "dgca", "iata", "regulation", "compliance",
                    "conditions of carriage");
            case AUDIT -> containsAny(answer,
                    "audit", "trace", "decision", "escalation", "case queue");
            case DISRUPTION -> containsAny(answer,
                    "disruption", "alternative", "rebook", "cancelled flight");
        };
    }

    private static boolean coversCategory(String answer, String category) {
        String phrase = category.toLowerCase(Locale.ROOT).replace('_', ' ');
        return switch (category) {
                    case "COMFORT" -> answer.contains("extra legroom");
                    case "CABIN_BAGGAGE" ->
                            containsAny(
                                    answer,
                                    "cabin bag",
                                    "cabin and checked baggage",
                                    "checked and cabin baggage")
                                    && containsAny(
                                            answer,
                                            "7 kg", "piece", "personal item",
                                            "maximum weight", "dimensions")
                                    || containsWeightNear(answer, "cabin bag");
                    case "CHECKED_BAGGAGE" ->
                            containsAny(
                                    answer,
                                    "checked bag",
                                    "cabin and checked baggage",
                                    "checked and cabin baggage")
                                    && containsAny(
                                            answer,
                                            "15 kg", "free allowance", "checked allowance",
                                            "per piece", "piece concept")
                                    || containsWeightNear(answer, "checked bag");
                    case "EXCESS_BAGGAGE" -> containsAny(
                            answer,
                            "excess bag",
                            "over free allowance",
                            "above free allowance",
                            "additional baggage charge",
                            "overweight baggage");
                    case "RESTRICTED_ITEMS" ->
                            containsAny(answer,
                                    "restricted item", "prohibited item",
                                    "lithium batter", "power bank");
                    case "SPORTS_EQUIPMENT" -> answer.contains("sports equipment");
                    case "MOBILITY_AIDS" -> answer.contains("mobility aid")
                            || answer.contains("wheelchair");
                    case "SPECIAL_SERVICES" -> coversTopic(
                            answer, AnswerRequirements.RequestedTopic.SPECIAL_SERVICES);
                    case "WHEELCHAIR" -> containsAny(answer, "wheelchair", "wchr", "wchc", "wchw");
                    case "WHEELCHAIR_RULES" ->
                            anyLocalWindow(answer, "wheelchair", window ->
                                    window.contains("48 hour")
                                            && containsAny(window,
                                                    "manage stairs", "long distance",
                                                    "completely immobile", "cannot climb"))
                                    || anyLocalWindow(answer, "wchr", window ->
                                            window.contains("48 hour")
                                                    && containsAny(window,
                                                            "manage stairs", "long distance",
                                                            "completely immobile",
                                                            "cannot climb"));
                    case "UNACCOMPANIED_MINOR" -> containsAny(
                            answer, "unaccompanied minor", "um service");
                    case "UNACCOMPANIED_MINOR_RULES" ->
                            anyLocalWindow(answer, "unaccompanied minor", window ->
                                    containsAny(window, "5", "age")
                                            && containsAny(window,
                                                    "um form", "photo id",
                                                    "pick-up", "pickup"));
                    case "PETC" -> answer.contains("petc");
                    case "PETC_RULES" ->
                            anyLocalWindow(answer, "petc", window ->
                                    window.contains("48 hour")
                                            && containsAny(window,
                                                    "health certificate", "veterinar")
                                            && containsAny(window,
                                                    "7 kg", "cat", "dog"));
                    case "AVIH" -> answer.contains("avih");
                    case "AVIH_RULES" ->
                            anyLocalWindow(answer, "avih", window ->
                                    window.contains("hold")
                                            && window.contains("72 hour")
                                            && containsAny(window,
                                                    "health certificate", "veterinar"));
                    case "MEDA" -> answer.contains("meda");
                    case "MEDA_RULES" ->
                            anyLocalWindow(answer, "meda", window ->
                                    window.contains("medif")
                                            && window.contains("48 hour"));
                    case "MEAL_VGML" ->
                            answer.contains("vgml")
                                    && containsAny(answer, "vegan", "vegetarian")
                                    && answer.contains("24 hour");
                    case "MEAL_KSML" ->
                            answer.contains("ksml")
                                    && answer.contains("kosher")
                                    && answer.contains("48 hour");
                    case "UPGRADE_PATHWAYS" ->
                            answer.contains("upgrade")
                                    && containsAny(answer,
                                            "eligible", "eligibility", "paid", "payment",
                                            "bid", "points", "condition", "availability");
                    case "FFP_EARNING" ->
                            containsAny(answer, "earn", "earning", "accrual", "accrue")
                                    && containsAny(answer,
                                            "point", "mile", "fare class", "distance");
                    case "FFP_TIERS" ->
                            containsAny(answer, "tier", "qualification", "qualify")
                                    && containsAny(answer,
                                            "blue", "silver", "gold", "platinum",
                                            "tier point", "status mile");
                    case "FFP_REDEMPTION" ->
                            containsAny(answer, "redeem", "redemption")
                                    && containsAny(answer,
                                            "point", "award", "flight", "upgrade");
                    case "FARE_CLASS_REVENUE_BANDS" ->
                            containsAny(answer, "y ", "y (", "class y")
                                    && containsAny(answer, "w ", "w (", "class w")
                                    && containsAny(answer,
                                            "revenue band", "yield", "full flex",
                                            "promo", "promotional");
                    case "CABIN_CONFIGURATION" ->
                            containsAny(answer, "seat pitch", "pitch")
                                    && containsAny(answer, "seat width", "width")
                                    && containsAny(answer, "recline", "lie flat");
                    case "SEAT_BLOCKING_RULES" ->
                            countPresent(answer,
                                    "cbbg", "exst", "stcr",
                                    "commercial block", "safety block") >= 2
                                    && containsAny(answer,
                                            "approval", "authorisation",
                                            "authorization", "duty manager",
                                            "revenue management");
                    case "REFUND_FARE_RULES" ->
                            anyLocalWindow(answer, "value", window ->
                                                window.contains("inr 2,000")
                                                    && window.contains("inr 3,000")
                                                    && window.contains("inr 4,000")
                                                    && containsAny(
                                                            window,
                                                            "balance after fee",
                                                            "balance refunded",
                                                            "balance of base fare refunded",
                                                            "balance of base fare is refunded",
                                                            "remaining base fare",
                                                            "base fare after the fee"))
                                        && anyLocalWindow(answer, "saver", window ->
                                                window.contains("base fare")
                                                    && containsAny(window,
                                                            "forfeit",
                                                            "non refundable",
                                                            "non-refundable")
                                                    && window.contains(
                                                            "statutory taxes"));
                    case "NO_SHOW_RULES" ->
                            containsAny(answer, "no show", "no-show")
                                    && containsAny(answer,
                                            "return leg", "return sector",
                                            "statutory taxes", "forfeited");
                    case "OVERBOOKING_THRESHOLDS" ->
                            answer.contains("overbook")
                                    && answer.contains("%")
                                    && containsAny(answer,
                                            "standard", "peak", "capacity")
                                    && containsAny(answer,
                                            "revenue management head",
                                            "vp revenue management", "authority");
                    case "WAITLIST_RULES" ->
                            answer.contains("waitlist")
                                    && containsAny(answer,
                                            "involuntarily rebooked",
                                            "involuntary rebooking")
                                    && containsAny(answer,
                                            "first", "priority", "clearance");
                    case "DENIED_BOARDING_RULES" ->
                            answer.contains("denied boarding")
                                    && containsAny(answer,
                                            "involuntary", "volunteer")
                                    && containsAny(answer,
                                            "inr 2,000", "inr 5,000",
                                            "inr 10,000", "compensation");
                    case "BOARDING_OVERRIDE_RULES" ->
                            anyLocalWindow(answer, "boarding override", window ->
                                    window.contains("duty manager")
                                            && window.contains("za ops 010"));
                    case "GATE_CHANGE_SLA" ->
                            anyLocalWindow(answer, "gate change", window ->
                                    window.contains("fids")
                                            && containsAny(window,
                                                    "staff escort", "escort")
                                            && window.contains("5 minute"));
                    case "LATE_PASSENGER_RULES" ->
                            anyLocalWindow(answer, "late passenger", window ->
                                    containsAny(window,
                                            "10 14 minute", "10 to 14 minute")
                                            && window.contains("duty manager")
                                            && window.contains("bag")
                                            && window.contains("offload"));
                    case "CODESHARE_INTERLINE_RULES" ->
                            containsAny(answer, "codeshare", "interline")
                                    && containsAny(answer,
                                            "operating carrier", "marketing carrier")
                                    && containsAny(answer,
                                            "through check", "through checks",
                                            "through check baggage");
                    case "REVENUE_PRORATION_RULES" ->
                            answer.contains("proration")
                                    && containsAny(answer,
                                            "iata", "bilateral", "equal split")
                                    && containsAny(answer,
                                            "bsp", "settlement", "rates");
                    case "FFP_UPGRADE_INVENTORY" ->
                            anyLocalWindow(answer, "upgrade pool", window ->
                                    window.contains("10 15%")
                                            && window.contains("platinum")
                                            && window.contains("gold")
                                            && window.contains("silver")
                                            && window.contains("2 hour"));
                    case "FFP_RETRO_CREDIT" ->
                            containsAny(answer,
                                    "retro credit", "retroactive claim",
                                    "missing mileage", "missing miles")
                                    && answer.contains("6 month")
                                    && answer.contains("boarding pass");
                    case "DGCA_DAILY_OBLIGATIONS" ->
                            answer.contains("dgca")
                                    && countPresent(answer,
                                            "mel", "passenger rights",
                                            "crew licensing", "ftl",
                                            "ground handling", "cabin safety") >= 3;
                    case "IATA_OPERATIONAL_RESOLUTIONS" ->
                            answer.contains("iata")
                                    && countPresent(answer,
                                            "722", "830e", "799", "780", "010f") >= 3;
                    case "SMS_OBLIGATIONS" ->
                            containsAny(answer,
                                    "safety management system", "sms")
                                    && containsAny(answer,
                                            "mor", "mandatory occurrence")
                                    && answer.contains("72 hour")
                                    && answer.contains("monthly")
                                    && containsAny(answer,
                                            "annual audit", "annual internal audit");
                    case "MEL_PROCEDURE" ->
                            containsAny(answer, "mel", "minimum equipment list")
                                    && answer.contains("lame")
                                    && answer.contains("occ")
                                    && containsAny(answer, "placard", "mel log")
                                    && answer.contains("aog");
                    case "DISRUPTION_SUBSTITUTION_RULES" ->
                            containsAny(answer,
                                    "substitute aircraft", "aircraft substitution")
                                    && containsAny(answer, "rebook", "rebooking")
                                    && answer.contains("2 hour")
                                    && containsAny(answer,
                                            "weather", "aog", "network",
                                            "diversion", "entitlement");
                    case "AGENT_COMMISSION_RULES" ->
                            containsAny(answer,
                                    "agent commission", "agency commission",
                                    "iata agenc")
                                    && answer.contains("0%")
                                    && answer.contains("plb")
                                    && containsAny(answer, "ota", "weekly");
                    case "GDS_BSP_RULES" ->
                            answer.contains("gds")
                                    && answer.contains("bsp")
                                    && answer.contains("adm")
                                    && answer.contains("acm")
                                    && containsAny(answer, "ndc", "dispute");
                    case "CONDITIONS_OF_CARRIAGE_BOUNDARY" ->
                            answer.contains("conditions of carriage")
                                    && answer.contains("ua coc 2026 01")
                                    && containsAny(answer,
                                            "disputed legal outcome",
                                            "must not decide", "may not decide",
                                            "do not decide");
                    case "CROSS_BORDER_SOURCE_RULES" ->
                            containsAny(answer, "cross border", "cross-border")
                                    && containsAny(answer, "route", "origin")
                                    && answer.contains("date")
                                    && answer.contains("carrier")
                                    && containsAny(answer,
                                            "effective source", "effective version")
                                    && containsAny(answer,
                                            "escalate", "escalation");
                    case "FTL_DTL_LIMITS" ->
                            containsAny(answer, "car 7", "ftl", "flight time")
                                    && answer.contains("8 hour")
                                    && answer.contains("40")
                                    && answer.contains("100")
                                    && answer.contains("1,000")
                                    && answer.contains("12 hour");
                    case "REST_AUGMENTED_CREW_RULES" ->
                            answer.contains("augmented crew")
                                    && answer.contains("16 hour")
                                    && containsAny(answer,
                                            "2 pilots plus 1", "2 pilot")
                                    && answer.contains("12 hour")
                                    && answer.contains("36 hour");
                    case "PIR_FILING_RULES" ->
                            answer.contains("pir")
                                    && containsAny(answer,
                                            "arrival baggage", "baggage desk")
                                    && answer.contains("before leaving")
                                    && containsAny(answer,
                                            "baggage tag", "bag tag")
                                    && containsAny(answer,
                                            "government id", "identification");
                    case "WORLDTRACER_MILESTONES" ->
                            answer.contains("worldtracer")
                                    && answer.contains("24 hour")
                                    && answer.contains("day 3")
                                    && answer.contains("day 5")
                                    && answer.contains("day 14")
                                    && answer.contains("day 21");
                    case "BAGGAGE_CLAIM_DOCUMENTS" ->
                            containsAny(answer,
                                    "boarding pass", "baggage tag", "bag tag")
                                    && containsAny(answer,
                                            "receipt", "photograph")
                                    && containsAny(answer,
                                            "delivery address", "contact detail",
                                            "government id");
                    case "MONTREAL_LIABILITY_BOUNDARY" ->
                            answer.contains("montreal")
                                    && answer.contains("1,519 sdr")
                                    && answer.contains("per passenger")
                                    && containsAny(answer,
                                            "not an automatic", "not automatic")
                                    && containsAny(answer,
                                            "hard coded exchange", "hard-coded exchange",
                                            "not a fixed exchange", "not a fixed rate",
                                            "approved current rate");
                    case "GENERAL_CANCELLATION_REFUND_POLICY" ->
                            coversGeneralCancellationRefundPolicy(answer);
                    case "BOOKING_RESULT" ->
                            containsAny(answer, "your booking", "your reservation", "pnr")
                                    && containsAny(answer, "status", "confirmed", "flight");
                    default -> answer.contains(phrase);
                };
    }

    private static boolean containsWeightNear(String answer, String anchor) {
        String weight = "\\b\\d+(?:\\.\\d+)?\\s*kg\\b";
        return answer.matches(
                        "(?s).*" + weight + "\\s+(?:of\\s+)?"
                                + java.util.regex.Pattern.quote(anchor) + ".*");
    }

    private static boolean citesCategoryEvidence(
            String answer,
            List<RetrievalDtos.Ranked> evidence,
            String category) {
        if (answer == null || evidence == null || evidence.isEmpty()) {
            return false;
        }
        for (RetrievalDtos.Ranked ranked : evidence) {
            String handle = "[" + CitationBuilder.handleFor(ranked.rank()) + "]";
            if (answer.contains(handle)
                    && PromptAssembler.evidenceCoversCategory(
                            ranked.chunk(), category)) {
                return true;
            }
        }
        return false;
    }

    private static boolean coversGeneralCancellationRefundPolicy(String answer) {
        int fareFamilies = 0;
        if (containsAny(answer, "saver", "super saver", "promotional", "sale fare")) {
            fareFamilies++;
        }
        if (answer.contains("value fare")) {
            fareFamilies++;
        }
        if (containsAny(answer, "flex fare", "full flex", "business flex")) {
            fareFamilies++;
        }
        boolean explainsRefunds = containsAny(
                answer,
                "refund processing", "refund method", "original payment method",
                "statutory taxes", "working days", "refund timeline");
        return fareFamilies >= 2 && explainsRefunds;
    }

    /**
     * Requires related facts to occur near the named product/service. Without this,
     * an answer can claim "AVIH details are unavailable" yet accidentally pass because
     * MEDA contributes "72 hours" and PETC contributes "health certificate" elsewhere.
     */
    private static boolean anyLocalWindow(
            String answer,
            String marker,
            java.util.function.Predicate<String> predicate) {
        int from = 0;
        while (from < answer.length()) {
            int index = answer.indexOf(marker, from);
            if (index < 0) {
                return false;
            }
            String window = answer.substring(
                    index, Math.min(answer.length(), index + 700));
            if (predicate.test(window)) {
                return true;
            }
            from = index + marker.length();
        }
        return false;
    }

    private static boolean requiresDomesticSeatScope(AnswerRequirements requirements) {
        return requirements.domesticOnly()
                && requirements.requiredCategories().contains("STANDARD_ECONOMY")
                && requirements.requiredCategories().contains("BUSINESS_AISLE");
    }

    private static boolean contradictsFailedTool(String answer) {
        return containsAny(answer,
                "flights are available", "i found ", "booking is confirmed",
                "status is on time", "successfully retrieved");
    }

    private static boolean looksTruncated(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String trimmed = answer.trim();
        return trimmed.endsWith("...")
                || trimmed.endsWith("â€¦")
                || trimmed.endsWith("|")
                || trimmed.matches("(?s).*\\b(?:and|or|because|including|such as)\\s*$");
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[*_`]", "")
                .replaceAll("[\\u2010-\\u2015\\u2212-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static int countPresent(String value, String... terms) {
        int count = 0;
        for (String term : terms) {
            if (value.contains(term)) {
                count++;
            }
        }
        return count;
    }

    public record Result(
            boolean complete,
            List<String> failedGates,
            Set<AnswerRequirements.RequestedTopic> missingTopics,
            Set<String> missingCategories) {

        static Result success() {
            return new Result(true, List.of(), Set.of(), Set.of());
        }
    }
}
