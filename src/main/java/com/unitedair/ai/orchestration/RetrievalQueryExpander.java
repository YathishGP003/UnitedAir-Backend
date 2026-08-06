package com.unitedair.ai.orchestration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Adds controlled synonyms already published in the selected KB documents.
 *
 * <p>This improves recall without adding facts or answers. The expanded query is persisted
 * on the retrieval run, so an auditor can see every term used to find the source passage.
 */
@Component
public class RetrievalQueryExpander {

    public String expand(String query, Set<String> documentCodes) {
        return expand(query, documentCodes, null);
    }

    public String expand(
            String query,
            Set<String> documentCodes,
            AnswerRequirements requirements) {
        if (query == null || query.isBlank()) {
            return query;
        }
        Set<String> effectiveDocumentCodes = new LinkedHashSet<>();
        if (documentCodes != null) {
            effectiveDocumentCodes.addAll(documentCodes);
        }
        if (requirements != null) {
            effectiveDocumentCodes.addAll(requirements.documentCodeHints());
        }
        documentCodes = Set.copyOf(effectiveDocumentCodes);
        String lower = query.toLowerCase(Locale.ROOT);
        StringBuilder expanded = new StringBuilder(query.trim());

        if (documentCodes.contains("KB-AIR-002")
                && (lower.contains("check-in")
                    || lower.contains("check in")
                    || lower.contains("checkin"))) {
            expanded.append(" check-in opening and closing times check-in windows"
                    + " web mobile kiosk counter baggage drop"
                    + " government issued photo ID acceptable travel documents");
        }
        if (documentCodes.contains("KB-AIR-002")
                && lower.contains("document")
                && (lower.contains("domestic") || lower.contains("international"))) {
            expanded.append(" acceptable travel documents government issued photo ID passport visa");
        }
        if (documentCodes.contains("KB-AIR-006")
                && lower.contains("meda")
                && lower.contains("document")) {
            expanded.append(" MEDIF Medical Information Form medical clearance procedure");
        }
        if ((documentCodes.contains("KB-AIR-002") || documentCodes.contains("KB-AIR-004"))
                && lower.contains("denied boarding")
                && (lower.contains("right") || lower.contains("compensation"))) {
            expanded.append(" involuntary denied boarding compensation passenger rights");
        }
        if (documentCodes.contains("KB-AIR-004")
                && containsAny(lower, "cancel", "cancellation", "refund", "refundable")) {
            expanded.append(" cancellation policy cancellation fee fare brand timing band"
                    + " more than 7 days 3-7 days within 3 days"
                    + " refund eligibility refund method");
        }
        if (documentCodes.contains("KB-AIR-003")
                && (lower.contains("cabin baggage") || lower.contains("cabin bag"))
                && lower.contains("allowance")) {
            expanded.append(" allowed pieces maximum weight per piece maximum dimensions personal item");
        }
        if (documentCodes.contains("KB-AIR-003")
                && lower.contains("cabin")
                && lower.contains("checked")
                && (lower.contains("luggage") || lower.contains("baggage"))) {
            expanded.append(" domestic cabin baggage allowance maximum weight"
                    + " allowed pieces maximum dimensions personal item handbag backpack"
                    + " checked baggage free allowance economy value");
        }
        if (documentCodes.contains("KB-AIR-003")
                && (lower.contains("power bank")
                    || lower.contains("lithium")
                    || lower.contains("lithum")
                    || (requirements != null
                        && requirements.requiredCategories().contains("RESTRICTED_ITEMS")))) {
            expanded.append(" lithium battery spare battery carry-on cabin baggage"
                    + " prohibited checked baggage Items Not Allowed Explosive materials"
                    + " Sharp objects Checked Baggage Only");
        }
        if (documentCodes.contains("KB-AIR-006")
                && containsWholeWord(
                        lower, "cat", "cats", "dog", "dogs",
                        "pet", "pets", "animal", "animals")) {
            expanded.append(" PETC pet in cabin AVIH animal in hold special service request");
        }
        if ((documentCodes.contains("KB-AIR-002") || documentCodes.contains("KB-AIR-004"))
                && containsAny(lower, "bump", "bumped", "oversold")) {
            expanded.append(" involuntary denied boarding compensation passenger rights"
                    + " confirmed ticket volunteers");
        }
        if (documentCodes.contains("KB-AIR-004")
                && containsAny(lower, "missed the first leg", "missed my first leg",
                        "return sector", "return leg")) {
            expanded.append(" outbound no-show round-trip booking return leg");
        }
        if (documentCodes.contains("KB-AIR-006") && lower.contains("wheelchair")) {
            expanded.append(" WCHR Wheelchair Ramp long distances 48 hours"
                    + " SSR code advance request");
        }
        if (documentCodes.contains("KB-AIR-006")
                && isNaturalUnaccompaniedMinorQuery(lower)) {
            expanded.append(" Unaccompanied Minor UM Service mandatory optional"
                    + " documentation required UM Form emergency contact form"
                    + " photo ID authorised pick-up person");
        }
        if (documentCodes.contains("KB-AIR-005")
                && containsAny(lower, "business class", "cabin class")) {
            expanded.append(" booking class fare brand Business Saver Business Flex"
                    + " business cabin availability");
        }
        if (documentCodes.contains("KB-AIR-006")
                && requirements != null
                && requirements.topics().contains(
                        AnswerRequirements.RequestedTopic.SPECIAL_SERVICES)
                && countPresent(
                        lower, "wheelchair", "wchr", "unaccompanied", " um ",
                        "petc", "avih", "meda") >= 3) {
            expanded.append(" Wheelchair Assistance Codes and Procedures WCHR"
                    + " Unaccompanied Minor UM Service"
                    + " Pet in Cabin PETC Policy"
                    + " Animal in Hold AVIH Policy"
                    + " Medical Clearance MEDA Procedure MEDIF"
                    + " Special Services Escalation Hierarchy");
        }
        if (requirements != null
                && requirements.topics().contains(
                        AnswerRequirements.RequestedTopic.SPECIAL_SERVICES)
                && requirements.topics().contains(
                        AnswerRequirements.RequestedTopic.SEATS)) {
            expanded.append(" special services WCHR WCHC UM MEDA PETC AVIH"
                    + " seat selection Standard Economy Preferred Economy"
                    + " Comfort Business Window Business Aisle domestic fee");
        }
        if (requirements != null
                && requirements.requiredCategories().stream().anyMatch(category ->
                        category.equals("STANDARD_ECONOMY")
                                || category.equals("PREFERRED_ECONOMY")
                                || category.equals("COMFORT")
                                || category.equals("BUSINESS_WINDOW")
                                || category.equals("BUSINESS_AISLE"))) {
            expanded.append(" Standard Economy Preferred Economy Comfort"
                    + " Business Window Business Aisle");
        }
        if (requirements != null
                && requirements.requiredCategories().contains("UPGRADE_PATHWAYS")) {
            expanded.append(" Upgrade Pathways for Passengers"
                    + " eligible fare classes fee conditions");
        }
        if (requirements != null
                && requirements.requiredCategories().contains("FFP_EARNING")) {
            expanded.append(" Points Accrual Rates by Fare Class distance flown");
        }
        if (requirements != null
                && requirements.requiredCategories().contains("FFP_TIERS")) {
            expanded.append(" Tier Structure and Qualification"
                    + " Blue Silver Gold Platinum");
        }
        if (requirements != null
                && requirements.requiredCategories().contains("FFP_REDEMPTION")) {
            expanded.append(" Points Redemption Options award flight cabin upgrade");
        }
        if (requirements != null
                && requirements.requiredCategories().contains(
                        "FARE_CLASS_REVENUE_BANDS")) {
            expanded.append(" Booking Class Codes and Revenue Bands Y B M H K Q V W");
        }
        if (requirements != null
                && requirements.requiredCategories().contains("CABIN_CONFIGURATION")) {
            expanded.append(" UnitedAir Cabin Class Overview seat pitch width recline");
        }
        if (requirements != null
                && requirements.requiredCategories().contains("SEAT_BLOCKING_RULES")) {
            expanded.append(" Seat Blocking Rules CBBG EXST STCR"
                    + " commercial block safety block");
        }
        if (requirements != null
                && requirements.requiredCategories().contains(
                        "OVERBOOKING_THRESHOLDS")) {
            expanded.append(" Overbooking Thresholds route standard peak authority");
        }
        if (requirements != null
                && requirements.requiredCategories().contains("WAITLIST_RULES")) {
            expanded.append(" Waitlist Priority Rules clearance order");
        }
        if (requirements != null
                && requirements.requiredCategories().contains(
                        "DENIED_BOARDING_RULES")) {
            expanded.append(" Involuntary Denied Boarding Compensation volunteers");
        }
        return expanded.toString();
    }

    /**
     * Focused semantic queries used by vector retrieval for a multipart request.
     *
     * <p>These are approved section labels, not answers or keyword routing rules. The
     * semantic route supplies the requested topics/categories; this method translates that
     * structured contract into the same governed vocabulary used by the vector index.
     */
    public List<String> coverageQueries(AnswerRequirements requirements) {
        if (requirements == null) {
            return List.of();
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (String category : requirements.requiredCategories()) {
            String query = switch (category) {
                case "CABIN_BAGGAGE" ->
                        "2.1 Cabin Baggage Allowance by Travel Class";
                case "CHECKED_BAGGAGE" ->
                        "3.1 Free Checked Baggage Allowance";
                case "EXCESS_BAGGAGE" ->
                        "3.2 Excess Baggage Fee Schedule";
                case "RESTRICTED_ITEMS" ->
                        "4.1 Items Not Allowed";
                case "WHEELCHAIR" ->
                        "3.1 Wheelchair Assistance Codes and Procedures";
                case "WHEELCHAIR_RULES" ->
                        "3.1 Wheelchair Assistance Codes and Procedures";
                case "UNACCOMPANIED_MINOR" ->
                        "3.2 Unaccompanied Minor UM Service";
                case "UNACCOMPANIED_MINOR_RULES" ->
                        "3.2 Unaccompanied Minor UM Service";
                case "PETC" -> "3.4 Pet in Cabin PETC Policy";
                case "PETC_RULES" -> "3.4 Pet in Cabin PETC Policy";
                case "AVIH" -> "3.5 Animal in Hold AVIH Handling";
                case "AVIH_RULES" -> "3.5 Animal in Hold AVIH Handling";
                case "MEDA" -> "3.3 Medical Clearance MEDA Procedure";
                case "MEDA_RULES" -> "3.3 Medical Clearance MEDA Procedure";
                case "MEAL_VGML", "MEAL_KSML" ->
                        "2.1 Available Meal Codes and Descriptions";
                case "SPECIAL_SERVICES" -> null;
                case "STANDARD_ECONOMY", "PREFERRED_ECONOMY", "COMFORT",
                        "BUSINESS_WINDOW", "BUSINESS_AISLE" ->
                        "3.1 Seat Categories and Fees";
                case "UPGRADE_PATHWAYS" ->
                        "5.1 Upgrade Pathways for Passengers";
                case "FFP_EARNING" -> "4.2 Points Accrual Rates by Fare Class";
                case "FFP_TIERS" -> "4.1 Tier Structure and Qualification";
                case "FFP_REDEMPTION" -> "4.4 Points Redemption Options";
                case "FARE_CLASS_REVENUE_BANDS" ->
                        "2.2 Booking Class Codes and Revenue Bands";
                case "CABIN_CONFIGURATION" ->
                        "2.1 UnitedAir Cabin Class Overview";
                case "SEAT_BLOCKING_RULES" ->
                        "3.3 Seat Blocking Rules Airline Staff";
                case "REFUND_FARE_RULES" ->
                        "2.1 Cancellation Fee Matrix by Fare Type";
                case "NO_SHOW_RULES" ->
                        "5.1 No-Show Rules";
                case "OVERBOOKING_THRESHOLDS" ->
                        "6.1 Overbooking Thresholds";
                case "WAITLIST_RULES" ->
                        "6.3 Waitlist Priority Rules";
                case "DENIED_BOARDING_RULES" ->
                        "6.2 Involuntary Denied Boarding Compensation DGCA CAR";
                case "BOARDING_OVERRIDE_RULES" ->
                        "2.1 Boarding Override Procedures";
                case "GATE_CHANGE_SLA" ->
                        "2.2 Gate Change Notification SLAs";
                case "LATE_PASSENGER_RULES" ->
                        "2.3 Late Passenger Handling Protocol";
                case "CODESHARE_INTERLINE_RULES" ->
                        "3.1 Codeshare Booking Rules";
                case "REVENUE_PRORATION_RULES" ->
                        "3.2 Alliance and Partner Booking Codes";
                case "FFP_UPGRADE_INVENTORY" ->
                        "5.1 Upgrade Pool Management";
                case "FFP_RETRO_CREDIT" ->
                        "4.2 Points Accrual Rates by Fare Class "
                                + "Retroactive claims accepted up to 6 months boarding pass";
                case "DGCA_DAILY_OBLIGATIONS" ->
                        "4.1 Key DGCA Civil Aviation Requirements CARs for Daily Operations";
                case "IATA_OPERATIONAL_RESOLUTIONS" ->
                        "4.2 Key IATA Resolutions for Operational Compliance";
                case "SMS_OBLIGATIONS" ->
                        "4.3 Safety Management System SMS Obligations";
                case "MEL_PROCEDURE" ->
                        "5.1 MEL Query Procedure";
                case "DISRUPTION_SUBSTITUTION_RULES" ->
                        "5.2 Disruption Management Protocols";
                case "AGENT_COMMISSION_RULES" ->
                        "6.1 Travel Agent Commission Structure";
                case "GDS_BSP_RULES" ->
                        "6.2 GDS Booking Codes and BSP Reconciliation";
                case "CONDITIONS_OF_CARRIAGE_BOUNDARY" ->
                        "7.1 Source and Interpretation Boundary";
                case "CROSS_BORDER_SOURCE_RULES" ->
                        "3 Source Selection Rules";
                case "FTL_DTL_LIMITS" ->
                        "8.1 DGCA CAR-7 FTL Limits Key Parameters";
                case "REST_AUGMENTED_CREW_RULES" ->
                        "8.2 Crew Scheduling Constraints";
                case "PIR_FILING_RULES" ->
                        "1 Report the bag at the arrival baggage-service desk before leaving the airport";
                case "WORLDTRACER_MILESTONES" ->
                        "3.3 UnitedAir Internal Service Milestones";
                case "BAGGAGE_CLAIM_DOCUMENTS" ->
                        "4 Filing and Notice Windows";
                case "MONTREAL_LIABILITY_BOUNDARY" ->
                        "5 Montreal Convention Boundary and Current Limit";
                case "GENERAL_CANCELLATION_REFUND_POLICY" ->
                        "2.1 Cancellation Fee Matrix by Fare Type";
                default -> null;
            };
            if (query != null) {
                queries.add(query);
            }
        }
        if (requirements.requiredCategories().contains("SPECIAL_SERVICES")) {
            queries.add("3.1 Wheelchair Assistance Codes and Procedures");
            queries.add("3.2 Unaccompanied Minor UM Service");
            queries.add("3.4 Pet in Cabin PETC Policy");
            queries.add("3.5 Animal in Hold AVIH Handling");
            queries.add("3.3 Medical Clearance MEDA Procedure");
        }
        if (requirements.requiredCategories().contains("RESTRICTED_ITEMS")) {
            queries.add("4.2 Checked Baggage Only");
        }
        if (requirements.requiredCategories().contains("CROSS_BORDER_SOURCE_RULES")) {
            queries.add("2 Facts Required Before Selecting a Source");
            queries.add("4 Effective-Version Rules");
            queries.add("6 Escalation Triggers");
        }

        for (AnswerRequirements.RequestedTopic topic : requirements.topics()) {
            switch (topic) {
                case BOOKING -> queries.add("3.1 STEP-BY-STEP BOOKING PROCESS");
                case CANCELLATION, REFUND -> {
                    queries.add("2.1 Cancellation Fee Matrix by Fare Type");
                    queries.add("3.1 Refund to Original Payment Method");
                }
                case CHECK_IN -> queries.add("2.1 Check-In Opening and Closing Times");
                case BAGGAGE -> {
                    queries.add("2.1 Cabin Baggage Allowance by Travel Class");
                    queries.add("3.1 Free Checked Baggage Allowance");
                }
                case SEATS -> {
                    queries.add("3.1 Seat Categories and Fees");
                    queries.add("5.1 Upgrade Pathways for Passengers");
                }
                case FARES -> {
                    if (requirements.documentCodeHints().contains("KB-AIR-004")) {
                        queries.add("4.1 Change Fee Matrix");
                    }
                }
                case MEALS -> queries.add("2.1 Available Meal Codes and Descriptions");
                case SPECIAL_SERVICES -> {
                    queries.add("3.1 Wheelchair Assistance Codes and Procedures");
                    queries.add("3.2 Unaccompanied Minor UM Service");
                    queries.add("3.3 Medical Clearance MEDA Procedure");
                    queries.add("3.4 Pet in Cabin PETC Policy");
                    queries.add("3.5 Animal in Hold AVIH Handling");
                }
                case DOCUMENTS -> {
                    queries.add("3.1 Domestic Travel Within India");
                    queries.add("3.2 International Travel Document Requirements");
                }
                case FFP -> {
                    queries.add("4.1 Tier Structure and Qualification");
                    queries.add("4.2 Points Accrual Rates by Fare Class");
                    queries.add("4.4 Points Redemption Options");
                }
                default -> {
                    // Operational topics and specifically categorized staff rules are
                    // already represented by their validated category contract.
                }
            }
        }
        return List.copyOf(queries);
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

    private static boolean containsWholeWord(String value, String... words) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String word : words) {
            if (java.util.regex.Pattern.compile(
                            "\\b" + java.util.regex.Pattern.quote(word) + "\\b")
                    .matcher(value)
                    .find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNaturalUnaccompaniedMinorQuery(String lower) {
        return containsAny(
                lower, "child", "children", "minor", "year-old", "year old",
                "son", "daughter")
                && containsAny(
                        lower, "travelling alone", "traveling alone", "travel alone",
                        "travels alone", "flying alone", "fly alone",
                        "without an adult", "unaccompanied");
    }
}
