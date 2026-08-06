package com.unitedair.ai.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.unitedair.ai.grounding.CitationBuilder;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.stereotype.Component;

/**
 * Builds the prompt - the Chain pattern step of SRS 2.1, "prompt augmentation with
 * citations".
 *
 * <p>The system prompt is scoped to the actor. A Passenger and a member of Airline Staff
 * asking the same words want different answers: the passenger wants to know what they can
 * do, the agent wants the rule and its authority. Scoping also closes a leak that filtering
 * alone does not - an Admin's retrieval may legitimately include staff-only chunks, and the
 * prompt is where we say who the reply is for.
 */
@Component
public class PromptAssembler {

    private final UnitedAirProperties properties;

    public PromptAssembler(UnitedAirProperties properties) {
        this.properties = properties;
    }

    public String systemPrompt(Role role) {
        int minFollowups = properties.getFollowups().getMinimum();

        String base = """
                You are UnitedAir AI, the assistant for UnitedAir, an Indian carrier.

                GROUNDING RULES - these are absolute:
                1. Answer ONLY from the EVIDENCE and TOOL RESULTS provided below. You have no
                   other knowledge of UnitedAir policy, fares, schedules or regulations.
                2. Cite the evidence handle in square brackets after every factual claim,
                   like this: [E1]. A sentence stating a fee, a rule, a deadline or an
                   entitlement must carry a citation.
                3. If the evidence does not answer the question, say so plainly and offer to
                   refer the matter on. Never fill a gap with a plausible-sounding policy.
                4. Never invent a fare, a fee, a flight number, a gate, a date or a
                   regulation reference. Numbers come from evidence or tool results only.
                5. You provide information. You do not take payments, issue boarding passes,
                   or make final decisions on boarding denial, medical clearance or refund
                   disputes - those rest with UnitedAir operations staff.
                6. Answer every requested part, but only those parts. Do not add unrelated
                   policies, prices, fees or advice merely because they appear in evidence.
                7. When policy says an operational price or quote must come from a tool,
                   do not state that price unless the matching successful tool result is
                   present. A Knowledge Base tariff table is not a live personal quote.
                8. When a tool result and a general policy table describe different fare,
                   route or booking contexts, the tool result is authoritative for that
                   booking. Never claim the general row matches the booking unless the
                   fare and route context actually match.
                9. An evidence header may name the required categories that block covers.
                   Use that block for those categories. Do not claim a category is absent
                   when a selected block is explicitly labelled as covering it.

                CITATION EXAMPLE:
                Evidence [E1] says "Value fares cancelled more than seven days before
                departure have an INR 2,000 fee."
                Good: A Value fare cancelled more than seven days before departure has an
                INR 2,000 fee. [E1]
                Bad: A Value fare has a cancellation fee. It is INR 2,000. [E1]
                The bad version leaves its first factual sentence uncited. Put a handle
                after every factual sentence, including narrative policy claims.

                STYLE:
                - Lead with the direct answer, then the detail that supports it.
                - Use INR for money and the 24-hour clock for times, as the evidence does.
                - Short paragraphs. Use a bullet list when presenting several options, fees
                  or conditions.
                - Never copy an evidence table, pipe-delimited row, parser heading or
                  document metadata into the answer. Convert it into labelled prose.
                - Translate "Nil" into a clear no-fee or no-refund statement.
                - An isolated "Yes" or "No" must name what is true or false.
                - Keep every citation handle attached to the claim it supports while
                  rewriting evidence into plain language.
                - Be warm and plain-spoken. No corporate padding.

                FOLLOW-UPS:
                End every reply with a line reading exactly FOLLOWUPS: followed by %d
                suggested next questions separated by ' | '. Each must be answerable from
                the same evidence you were given, and phrased for this user's role.
                """.formatted(minFollowups);

        String roleBlock = switch (role) {
            case PASSENGER -> """

                    YOU ARE SPEAKING TO A PASSENGER.
                    Explain what this means for their journey and what they should do next.
                    Translate internal terminology - say "the cheapest non-refundable fare"
                    rather than "booking class Q". Do not disclose internal operational
                    procedures, override authorities or staff-only thresholds.
                    """;
            case AIRLINE_STAFF -> """

                    YOU ARE SPEAKING TO AIRLINE STAFF.
                    Give the operative rule, its authority and any thresholds or approval
                    levels. Booking class codes, override procedures and regulatory
                    references are appropriate here. State clearly when a decision requires
                    a Duty Manager or a regulatory filing.
                    """;
            case ADMIN -> """

                    YOU ARE SPEAKING TO AN ADMINISTRATOR.
                    Answer operationally and include Knowledge Base governance detail -
                    document codes, versions and effective dates - where relevant.
                    """;
        };

        return base + roleBlock;
    }

    /**
     * Assembles the user-facing prompt: evidence blocks with citation handles, any tool
     * results, and the question itself.
     */
    public ChatDtos.ChatRequest assemble(Role role,
                                         String question,
                                         String rewrittenQuestion,
                                         List<RetrievalDtos.Ranked> evidence,
                                         List<ToolDtos.ToolOutcome> toolOutcomes,
                                         List<ChatDtos.HistoryTurn> history,
                                         int generateFrom) {
        return assemble(
                role, question, rewrittenQuestion, evidence,
                toolOutcomes, history, generateFrom,
                AnswerRequirements.from(question, null));
    }

    public ChatDtos.ChatRequest assemble(Role role,
                                         String question,
                                         String rewrittenQuestion,
                                         List<RetrievalDtos.Ranked> evidence,
                                         List<ToolDtos.ToolOutcome> toolOutcomes,
                                         List<ChatDtos.HistoryTurn> history,
                                         int generateFrom,
                                         AnswerRequirements requirements) {

        List<ChatDtos.Grounding> grounding = new ArrayList<>();
        StringBuilder prompt = new StringBuilder();

        // ---- tool results first: live data outranks documentation about live data -----
        if (toolOutcomes != null && !toolOutcomes.isEmpty()) {
            prompt.append("TOOL RESULTS (live system data, retrieved just now):\n\n");
            int index = 1;
            for (ToolDtos.ToolOutcome outcome : toolOutcomes) {
                if (!outcome.success()) {
                    prompt.append("- ").append(outcome.toolName())
                            .append(" could not complete: ").append(outcome.errorMessage())
                            .append("\nTell the user plainly that this lookup failed; do not guess the answer.\n\n");
                    continue;
                }
                String handle = "T" + index++;
                String structuredData = Json.write(outcome.data());
                prompt.append("[").append(handle).append("] ").append(outcome.toolName()).append("\n")
                        .append("Summary: ").append(outcome.summary()).append("\n")
                        .append("Data: ").append(structuredData).append("\n\n");
                grounding.add(new ChatDtos.Grounding(
                        handle,
                        outcome.toolName(),
                        outcome.summary() + "\n" + structuredData));
            }
        }

        // ---- KB evidence ------------------------------------------------------------
        if (evidence != null && !evidence.isEmpty()) {
            prompt.append("EVIDENCE from the UnitedAir Knowledge Base:\n\n");
            List<RetrievalDtos.Ranked> selectedEvidence =
                    selectEvidence(evidence, generateFrom, requirements);
            if (isProhibitedItemsSafetyQuestion(question)) {
                selectedEvidence = evidence.stream()
                        .filter(PromptAssembler::isProhibitedItemsEvidence)
                        .limit(Math.max(1, generateFrom))
                        .toList();
            }
            for (RetrievalDtos.Ranked ranked : selectedEvidence) {
                RetrievalDtos.Chunk chunk = ranked.chunk();
                String handle = CitationBuilder.handleFor(ranked.rank());

                prompt.append("[").append(handle).append("] ")
                        .append(chunk.documentCode());
                if (chunk.documentTitle() != null) {
                    prompt.append(" - ").append(chunk.documentTitle());
                }
                if (chunk.section() != null && !chunk.section().isBlank()) {
                    prompt.append(" | section ").append(chunk.section());
                }
                if (requirements != null
                        && !requirements.requiredCategories().isEmpty()) {
                    List<String> coveredCategories = requirements.requiredCategories()
                            .stream()
                            .filter(category -> evidenceCoversCategory(chunk, category))
                            .sorted()
                            .toList();
                    if (!coveredCategories.isEmpty()) {
                        prompt.append(" | covers ").append(coveredCategories);
                    }
                }
                prompt.append(" | page ").append(chunk.page()).append("\n")
                        .append(chunk.content()).append("\n\n");

                grounding.add(new ChatDtos.Grounding(handle,
                        chunk.documentCode() + " " + chunk.section(), chunk.content()));
            }
        }

        if (requirements != null && !requirements.topics().isEmpty()) {
            prompt.append("ANSWER REQUIREMENTS (all are mandatory):\n")
                    .append("- Requested topics: ")
                    .append(requirements.topics()).append("\n");
            if (!requirements.requiredCategories().isEmpty()) {
                prompt.append("- Required categories: ")
                        .append(requirements.requiredCategories()).append("\n");
                String dimensions = coverageDimensions(requirements);
                if (!dimensions.isBlank()) {
                    prompt.append("- Coverage dimensions (take the actual rules and "
                            + "values only from the selected evidence):\n")
                            .append(dimensions);
                }
                if (requirements.requiredCategories().contains(
                        "GENERAL_CANCELLATION_REFUND_POLICY")) {
                    prompt.append("- In addition to the booking-specific result, provide "
                            + "a separate general cancellation/refund policy overview "
                            + "covering multiple fare or timing scenarios and refund "
                            + "processing. Do not replace it with the personal quote.\n");
                }
                if (requirements.requiredCategories().contains("BOOKING_RESULT")) {
                    prompt.append("- State the retrieved booking or PNR result directly, "
                            + "including its flight and current status. Do not omit it "
                            + "when also explaining policy.\n");
                }
            }
            if (isProhibitedItemsSafetyQuestion(question)) {
                prompt.append("- Safety response: begin with a direct instruction that "
                        + "explosive materials are not allowed and that knives or other "
                        + "sharp objects must not be carried in the cabin. Clearly "
                        + "distinguish the checked-baggage rule for sharp objects and "
                        + "the approval requirement for firearms/ammunition. Explain "
                        + "the explosive-material, sharp-object and approved-firearm "
                        + "rules only from the selected evidence, citing every rule. "
                        + "Do not generalise beyond the evidence. Do not provide "
                        + "concealment, evasion or weapon-use instructions.\n");
            }
            prompt.append("- Operational state required: ")
                    .append(requirements.operationalStateRequired()).append("\n")
                    .append("- Review only; do not imply a mutation: ")
                    .append(requirements.reviewOnly()).append("\n\n");
        }
        prompt.append("QUESTION: ").append(question);
        if (rewrittenQuestion != null && !rewrittenQuestion.equals(question)) {
            prompt.append("\n(Interpreted in context as: ").append(rewrittenQuestion).append(")");
        }

        return new ChatDtos.ChatRequest(
                systemPrompt(role),
                history == null ? List.of() : history,
                prompt.toString(),
                grounding);
    }

    private static boolean isProhibitedItemsSafetyQuestion(String question) {
        if (question == null) {
            return false;
        }
        String lower = question.toLowerCase(java.util.Locale.ROOT);
        return lower.matches(
                ".*\\b(?:bombs?|explosives?|weapons?|knives?|guns?|firearms?)\\b.*");
    }

    private static boolean isProhibitedItemsEvidence(RetrievalDtos.Ranked ranked) {
        String text = (ranked.chunk().section() + " " + ranked.chunk().content())
                .toLowerCase(java.util.Locale.ROOT);
        return text.contains("items not allowed")
                || text.contains("explosive material")
                || text.contains("sharp object")
                || text.contains("firearms/ammunition")
                || text.contains("firearms or ammunition");
    }

    /**
     * Extra instruction added on the single permitted repair attempt, naming what the
     * evaluator rejected so the second try addresses it rather than rephrasing the first.
     */
    public String repairInstruction(List<String> failedGates) {
        return repairInstruction(failedGates, null);
    }

    public String repairInstruction(
            List<String> failedGates,
            AnswerRequirements requirements) {
        String coverage = requirements == null
                ? ""
                : """

                You must explicitly cover these requested topics: %s
                You must explicitly cover these required categories: %s
                Coverage dimensions (take the actual rules and values only from evidence):
                %s
                """.formatted(
                        requirements.topics(),
                        requirements.requiredCategories(),
                        coverageDimensions(requirements));
        return """

                YOUR PREVIOUS ANSWER WAS REJECTED for: %s

                Rewrite it. Cite an evidence handle on every factual sentence, state only
                what the evidence supports, and if the evidence genuinely does not cover the
                question then say that instead of stretching it. Do not add a redundant
                uncited overview or conclusion. If a sentence combines facts from different
                sources, split it into single-source sentences and cite each one.
                %s
                """.formatted(String.join(", ", failedGates), coverage);
    }

    private static String coverageDimensions(
            AnswerRequirements requirements) {
        if (requirements == null
                || requirements.requiredCategories().isEmpty()) {
            return "";
        }
        StringBuilder dimensions = new StringBuilder();
        for (String category : requirements.requiredCategories()) {
            String instruction = coverageDimension(category);
            if (instruction != null) {
                dimensions.append("  * ")
                        .append(category)
                        .append(": ")
                        .append(instruction)
                        .append('\n');
            }
        }
        return dimensions.toString();
    }

    /**
     * Describes what a complete answer must cover, not what the policy answer is. Policy
     * values remain exclusively in the retrieved evidence and verified tool results.
     */
    private static String coverageDimension(String category) {
        return switch (category) {
            case "FARE_CLASS_REVENUE_BANDS" ->
                    "all requested booking-class groups, cabin/fare brand and revenue band.";
            case "CABIN_CONFIGURATION" ->
                    "seat pitch, width and recline or lie-flat characteristics by cabin.";
            case "SEAT_BLOCKING_RULES" ->
                    "each relevant block type, purpose, approval authority and release rule.";
            case "REFUND_FARE_RULES" ->
                    "each requested fare and timing band, fee, base-fare treatment and taxes.";
            case "NO_SHOW_RULES" ->
                    "outbound/return-sector effect, fare forfeiture and refundable taxes.";
            case "OVERBOOKING_THRESHOLDS" ->
                    "standard and peak thresholds plus approval authority.";
            case "WAITLIST_RULES" ->
                    "clearance order, priority and involuntary-rebooking treatment.";
            case "DENIED_BOARDING_RULES" ->
                    "volunteer process, involuntary cases and compensation bands.";
            case "BOARDING_OVERRIDE_RULES" ->
                    "trigger, decision authority, deadline and required override record.";
            case "GATE_CHANGE_SLA" ->
                    "every timing band, FIDS/announcement action, escort and notification SLA.";
            case "LATE_PASSENGER_RULES" ->
                    "each arrival-time band, decision authority and checked-bag offload impact.";
            case "CODESHARE_INTERLINE_RULES" ->
                    "marketing versus operating carrier, check-in and through-check handling.";
            case "REVENUE_PRORATION_RULES" ->
                    "IATA or bilateral basis and settlement mechanism for each partner type.";
            case "FFP_UPGRADE_INVENTORY" ->
                    "inventory size, tier-based opening windows, bid timing and final release.";
            case "FFP_RETRO_CREDIT" ->
                    "claim deadline, required proof and partner-credit timing.";
            case "WHEELCHAIR_RULES" ->
                    "WCHR/WCHC/WCHW meanings, advance timing and airport handling steps.";
            case "UNACCOMPANIED_MINOR_RULES" ->
                    "age bands, booking channel, documents, flight restrictions and hand-off.";
            case "PETC_RULES" ->
                    "advance-request timing, eligible animals, carrier/weight limit, documents, "
                            + "route restrictions and fee.";
            case "AVIH_RULES" ->
                    "advance timing, eligible animals/routes, documents, container, hold checks "
                            + "and approving operational roles.";
            case "MEDA_RULES" ->
                    "notification, MEDIF parts, medical review, equipment and check-in validation.";
            case "DGCA_DAILY_OBLIGATIONS" ->
                    "each daily operational compliance area requested and its governing source.";
            case "IATA_OPERATIONAL_RESOLUTIONS" ->
                    "each relevant resolution number and the operational subject it governs.";
            case "SMS_OBLIGATIONS" ->
                    "occurrence reporting, deadline, monthly review and annual audit.";
            case "MEL_PROCEDURE" ->
                    "LAME/OCC decision path, placard/log action and AOG boundary.";
            case "DISRUPTION_SUBSTITUTION_RULES" ->
                    "substitution cause, rebooking action, timing and passenger entitlement.";
            case "AGENT_COMMISSION_RULES" ->
                    "IATA/OTA base commission, PLB treatment and settlement cadence.";
            case "GDS_BSP_RULES" ->
                    "GDS class control, BSP reconciliation, ADM/ACM and NDC boundary.";
            case "CONDITIONS_OF_CARRIAGE_BOUNDARY" ->
                    "authoritative source and which disputed legal outcomes require escalation.";
            case "CROSS_BORDER_SOURCE_RULES" ->
                    "route/origin/date/carrier source selection and ambiguity escalation.";
            case "FTL_DTL_LIMITS" ->
                    "daily, rolling-period, annual and duty-period limits.";
            case "REST_AUGMENTED_CREW_RULES" ->
                    "augmented complement, maximum duty and minimum rest rules.";
            case "PIR_FILING_RULES" ->
                    "where and when to file, required identity/bag proof and PIR reference.";
            case "WORLDTRACER_MILESTONES" ->
                    "initial update and every published escalation/review milestone.";
            case "BAGGAGE_CLAIM_DOCUMENTS" ->
                    "travel/bag proof, identity/contact details, receipts and photographs.";
            case "MONTREAL_LIABILITY_BOUNDARY" ->
                    "SDR limit scope, per-passenger basis, why it is not an automatic payout, "
                            + "and the evidence-defined exchange-rate/conversion boundary.";
            default -> null;
        };
    }

    static List<RetrievalDtos.Ranked> selectEvidence(
            List<RetrievalDtos.Ranked> evidence,
            int generateFrom,
            AnswerRequirements requirements) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        int baseLimit = Math.max(1, generateFrom);
        if (requirements == null
                || (requirements.documentCodeHints().isEmpty()
                    && requirements.requiredCategories().isEmpty())) {
            return evidence.stream().limit(baseLimit).toList();
        }

        Set<RetrievalDtos.Ranked> selected = new LinkedHashSet<>();
        for (String category : requirements.requiredCategories()) {
            int bestCategoryScore = evidence.stream()
                    .filter(ranked -> requirements.documentCodeHints().isEmpty()
                            || requirements.documentCodeHints().contains(
                                    ranked.chunk().documentCode()))
                    .mapToInt(ranked -> categoryCoverageScore(
                            ranked.chunk(), category))
                    .max()
                    .orElse(0);
            evidence.stream()
                    .filter(ranked -> requirements.documentCodeHints().isEmpty()
                            || requirements.documentCodeHints().contains(
                                    ranked.chunk().documentCode()))
                    .filter(ranked -> bestCategoryScore > 0
                            && categoryCoverageScore(
                                    ranked.chunk(), category) == bestCategoryScore)
                    .sorted(java.util.Comparator
                            .comparingInt((RetrievalDtos.Ranked ranked) ->
                                    categoryCoverageScore(ranked.chunk(), category))
                            .reversed()
                            .thenComparingInt(RetrievalDtos.Ranked::rank))
                    // Some SRS categories are composite procedures spread across
                    // several approved sections (for example cross-border source
                    // selection needs facts, effective-version rules and escalation
                    // triggers). Preserve the bounded set instead of pretending one
                    // chunk can satisfy the whole contract.
                    .limit(4)
                    .forEach(selected::add);
        }
        for (String documentCode : requirements.documentCodeHints()) {
            boolean alreadyRepresented = selected.stream().anyMatch(
                    ranked -> documentCode.equalsIgnoreCase(
                            ranked.chunk().documentCode()));
            if (!alreadyRepresented) {
                evidence.stream()
                        .filter(ranked -> documentCode.equalsIgnoreCase(
                                ranked.chunk().documentCode()))
                        .findFirst()
                        .ifPresent(selected::add);
            }
        }
        if (!requirements.requiredCategories().isEmpty()
                && !selected.isEmpty()) {
            return List.copyOf(selected);
        }
        int limit = Math.max(baseLimit, selected.size());
        for (RetrievalDtos.Ranked ranked : evidence) {
            if (selected.size() >= limit) {
                break;
            }
            if (!requirements.documentCodeHints().isEmpty()
                    && !requirements.documentCodeHints().contains(
                            ranked.chunk().documentCode())) {
                continue;
            }
            selected.add(ranked);
        }
        return List.copyOf(selected);
    }

    static boolean evidenceCoversCategory(
            RetrievalDtos.Chunk chunk,
            String category) {
        return categoryCoverageScore(chunk, category) > 0;
    }

    private static int categoryCoverageScore(
            RetrievalDtos.Chunk chunk,
            String category) {
        String haystack = ((chunk.section() == null ? "" : chunk.section()) + " "
                + (chunk.content() == null ? "" : chunk.content()))
                .toLowerCase(java.util.Locale.ROOT);
        String phrase = category.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        if ("CHECKED_BAGGAGE".equals(category)
                && haystack.contains("free checked baggage allowance")) {
            return 4;
        }
        if ("REVENUE_PRORATION_RULES".equals(category)
                && haystack.contains("iata")
                && haystack.contains("bsp")
                && haystack.contains("proration")) {
            return 4;
        }
        if ("PIR_FILING_RULES".equals(category)
                && haystack.contains("arrival baggage-service desk")
                && haystack.contains("before leaving")) {
            return 4;
        }
        if ("GENERAL_CANCELLATION_REFUND_POLICY".equals(category)) {
            boolean completeFareMatrix =
                    haystack.contains("cancellation fee matrix by fare type")
                            && haystack.contains("saver / super saver")
                            && haystack.contains("value")
                            && haystack.contains("flex");
            boolean refundMethod =
                    haystack.contains("refund to original payment method")
                            || (haystack.contains("original payment method")
                                && haystack.contains("working days"));
            if (completeFareMatrix || refundMethod) {
                return 4;
            }
        }
        if (haystack.contains(phrase)) {
            return 2;
        }
        boolean matches = switch (category) {
            case "CABIN_BAGGAGE" -> haystack.contains("cabin bag");
            case "CHECKED_BAGGAGE" -> haystack.contains("checked bag");
            case "EXCESS_BAGGAGE" ->
                    haystack.contains("excess bag")
                            || (haystack.contains("free allowance")
                                && haystack.contains("additional")
                                && haystack.contains("charge"));
            case "RESTRICTED_ITEMS" ->
                    haystack.contains("restricted")
                            || haystack.contains("lithium")
                            || haystack.contains("power bank");
            case "STANDARD_ECONOMY", "PREFERRED_ECONOMY", "COMFORT",
                    "BUSINESS_WINDOW", "BUSINESS_AISLE" ->
                    haystack.contains("seat categories and fees")
                            || haystack.contains("seat categories");
            case "WHEELCHAIR" ->
                    haystack.contains("wheelchair") || haystack.contains("wchr");
            case "WHEELCHAIR_RULES" ->
                    (haystack.contains("wheelchair") || haystack.contains("wchr"))
                            && haystack.contains("48 hours");
            case "UNACCOMPANIED_MINOR" ->
                    haystack.contains("unaccompanied minor")
                            || haystack.contains("um service");
            case "UNACCOMPANIED_MINOR_RULES" ->
                    (haystack.contains("unaccompanied minor")
                            || haystack.contains("um service"))
                            && (haystack.contains("um form")
                                || haystack.contains("eligible age"));
            case "PETC" -> haystack.contains("petc");
            case "PETC_RULES" ->
                    haystack.contains("petc")
                            && (haystack.contains("health certificate")
                                || haystack.contains("48 hours"));
            case "AVIH" -> haystack.contains("avih");
            case "AVIH_RULES" ->
                    haystack.contains("avih")
                            && (haystack.contains("health certificate")
                                || haystack.contains("animal in hold"));
            case "MEDA" -> haystack.contains("meda");
            case "MEDA_RULES" ->
                    haystack.contains("meda") && haystack.contains("medif");
            case "MEAL_VGML" ->
                    haystack.contains("vgml") && haystack.contains("24 hours");
            case "MEAL_KSML" ->
                    haystack.contains("ksml") && haystack.contains("48 hours");
            case "UPGRADE_PATHWAYS" ->
                    haystack.contains("upgrade pathways")
                            || (haystack.contains("upgrade")
                                && haystack.contains("eligible fare"));
            case "FFP_EARNING" ->
                    haystack.contains("points accrual")
                            || haystack.contains("base miles earned");
            case "FFP_TIERS" ->
                    haystack.contains("tier structure")
                            || haystack.contains("tier qualification");
            case "FFP_REDEMPTION" ->
                    haystack.contains("points redemption")
                            || haystack.contains("redemption options");
            case "FARE_CLASS_REVENUE_BANDS" ->
                    haystack.contains("booking class codes and revenue bands");
            case "CABIN_CONFIGURATION" ->
                    haystack.contains("cabin class overview")
                            && haystack.contains("seat pitch");
            case "SEAT_BLOCKING_RULES" ->
                    haystack.contains("seat blocking rules")
                            || (haystack.contains("cbbg")
                                && haystack.contains("stcr"));
            case "REFUND_FARE_RULES" ->
                    haystack.contains("cancellation fee matrix by fare type")
                            || (haystack.contains("refund of base fare")
                                && haystack.contains("refund of taxes"));
            case "NO_SHOW_RULES" ->
                    haystack.contains("no-show rules")
                            || haystack.contains("no show rules");
            case "OVERBOOKING_THRESHOLDS" ->
                    haystack.contains("overbooking thresholds")
                            && haystack.contains("standard overbooking rate");
            case "WAITLIST_RULES" ->
                    haystack.contains("waitlist priority rules");
            case "DENIED_BOARDING_RULES" ->
                    haystack.contains("involuntary denied boarding compensation");
            case "BOARDING_OVERRIDE_RULES" ->
                    haystack.contains("boarding override procedures")
                            && haystack.contains("za-ops-010");
            case "GATE_CHANGE_SLA" ->
                    haystack.contains("gate change notification slas");
            case "LATE_PASSENGER_RULES" ->
                    haystack.contains("late passenger handling protocol");
            case "CODESHARE_INTERLINE_RULES" ->
                    haystack.contains("codeshare booking rules")
                            && haystack.contains("interline agreement");
            case "REVENUE_PRORATION_RULES" ->
                    haystack.contains("revenue proration")
                            || (haystack.contains("iata proration")
                                && haystack.contains("bsp"));
            case "FFP_UPGRADE_INVENTORY" ->
                    haystack.contains("upgrade pool management");
            case "FFP_RETRO_CREDIT" ->
                    haystack.contains("retroactive claims")
                            && haystack.contains("boarding pass");
            case "DGCA_DAILY_OBLIGATIONS" ->
                    haystack.contains("key dgca civil aviation requirements");
            case "IATA_OPERATIONAL_RESOLUTIONS" ->
                    haystack.contains("key iata resolutions");
            case "SMS_OBLIGATIONS" ->
                    haystack.contains("safety management system")
                            && haystack.contains("mandatory occurrence");
            case "MEL_PROCEDURE" ->
                    haystack.contains("mel query procedure");
            case "DISRUPTION_SUBSTITUTION_RULES" ->
                    haystack.contains("disruption management protocols")
                            || (haystack.contains("substitute aircraft")
                                && haystack.contains("rebook"));
            case "AGENT_COMMISSION_RULES" ->
                    haystack.contains("travel agent commission structure");
            case "GDS_BSP_RULES" ->
                    haystack.contains("gds booking codes and bsp reconciliation");
            case "CONDITIONS_OF_CARRIAGE_BOUNDARY" ->
                    haystack.contains("conditions of carriage")
                            && haystack.contains("interpretation boundary");
            case "CROSS_BORDER_SOURCE_RULES" ->
                    (haystack.contains("facts required before selecting a source")
                            || haystack.contains("source selection rules")
                            || haystack.contains("effective-version rules")
                            || haystack.contains("escalation triggers"))
                            && (haystack.contains("cross-border")
                                || haystack.contains("origin")
                                || haystack.contains("effective"));
            case "FTL_DTL_LIMITS" ->
                    haystack.contains("car-7 ftl limits");
            case "REST_AUGMENTED_CREW_RULES" ->
                    haystack.contains("augmented crew")
                            && haystack.contains("minimum rest");
            case "PIR_FILING_RULES" ->
                    haystack.contains("property irregularity report")
                            || (haystack.contains("pir")
                                && haystack.contains("arrival baggage"));
            case "WORLDTRACER_MILESTONES" ->
                    haystack.contains("worldtracer")
                            || (haystack.contains("internal service milestones")
                                && haystack.contains("day 21"));
            case "BAGGAGE_CLAIM_DOCUMENTS" ->
                    haystack.contains("baggage-tag")
                            && haystack.contains("boarding pass");
            case "MONTREAL_LIABILITY_BOUNDARY" ->
                    haystack.contains("montreal convention")
                            && haystack.contains("1,519 sdr");
            default -> false;
        };
        if (!matches) {
            return 0;
        }
        if ("RESTRICTED_ITEMS".equals(category)
                && (haystack.contains("lithium")
                    || haystack.contains("power bank")
                    || haystack.contains("items not allowed"))) {
            return 3;
        }
        return 1;
    }
}
