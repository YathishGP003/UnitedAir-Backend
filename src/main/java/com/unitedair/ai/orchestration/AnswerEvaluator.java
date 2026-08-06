package com.unitedair.ai.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.unitedair.ai.grounding.CitationAttacher;
import com.unitedair.ai.grounding.CitationBuilder;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.stereotype.Component;

/**
 * The Evaluator-Optimizer pattern of SRS 2.2: validates a generated answer before it is
 * delivered, and decides between accepting it, repairing it once, or escalating.
 *
 * <p>Each gate answers a question that a passenger or a regulator would ask of the answer:
 *
 * <ul>
 *   <li><b>EMPTY_ANSWER</b> - did the model actually say anything?</li>
 *   <li><b>RAW_STRUCTURED_PAYLOAD</b> - did a tool JSON object leak into the reply?</li>
 *   <li><b>MISSING_CITATION</b> - is any factual claim uncited? (SRS 4.1.2)</li>
 *   <li><b>UNSUPPORTED_CLAIM</b> - do the numbers in the answer appear in the evidence?
 *       This is the gate that catches a plausible invented fee.</li>
 *   <li><b>LOW_CONFIDENCE</b> - was the evidence strong enough? (SRS 4.2.2)</li>
 *   <li><b>INSUFFICIENT_FOLLOWUPS</b> - SRS 4.3.3 requires at least two.</li>
 *   <li><b>ACTOR_SCOPE_LEAK</b> - has staff-only material reached a passenger?</li>
 *   <li><b>POLICY_DEVIATION</b> - has the assistant promised something SRS 4.2.1 forbids?</li>
 * </ul>
 */
@Component
public class AnswerEvaluator {

    /** Currency amounts, percentages and hour/day counts that must be traceable. */
    private static final Pattern NUMERIC_CLAIM = Pattern.compile(
            "(?i)(?:INR|rs\\.?|₹)\\s?([\\d,]+(?:\\.\\d{1,2})?)"
                    + "|\\b(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?)\\b"
                    + "|\\b(\\d+)\\s?(?:%|percent)"
                    + "|\\b(\\d+)\\s?(?:kg|hours?|hrs?|days?|minutes?|mins?)\\b");
    private static final Pattern RAW_STRUCTURED_PAYLOAD = Pattern.compile(
            "(?s)[\\[{]\\s*[\"'][A-Za-z][A-Za-z0-9_]*[\"']\\s*:");

    /** Wording that would commit UnitedAir to something SRS 4.2.1 puts out of scope. */
    private static final List<String> FORBIDDEN_COMMITMENTS = List.of(
            "i have cancelled", "i have processed your refund", "i have issued your boarding pass",
            "your payment has been", "i have charged", "i have booked your",
            "your refund has been approved", "i have rebooked");

    /** Staff-only vocabulary that must not surface in a passenger-facing answer. */
    private static final List<String> STAFF_ONLY_TERMS = List.of(
            "override authority", "duty manager approval", "yield management parameter",
            "revenue band", "override log", "denied boarding compensation threshold",
            "overbooking threshold", "waitlist priority rule");

    private final UnitedAirProperties properties;
    private final CitationBuilder citationBuilder;
    private final CitationAttacher citationAttacher;
    private final CompletenessEvaluator completenessEvaluator;

    public AnswerEvaluator(UnitedAirProperties properties,
                           CitationBuilder citationBuilder,
                           CitationAttacher citationAttacher) {
        this.properties = properties;
        this.citationBuilder = citationBuilder;
        this.citationAttacher = citationAttacher;
        this.completenessEvaluator = new CompletenessEvaluator();
    }

    public Verdict evaluate(String answer,
                            List<String> followups,
                            List<RetrievalDtos.Ranked> evidence,
                            double retrievalConfidence,
                            Role actorRole,
                            boolean toolGrounded) {
        AnswerRequirements inferred =
                completenessEvaluator.infer(answer, evidence);
        return evaluate(
                answer, followups, evidence, retrievalConfidence,
                actorRole, toolGrounded, inferred, List.of());
    }

    public Verdict evaluate(String answer,
                            List<String> followups,
                            List<RetrievalDtos.Ranked> evidence,
                            double retrievalConfidence,
                            Role actorRole,
                            boolean toolGrounded,
                            AnswerRequirements requirements,
                            List<com.unitedair.ai.tools.ToolDtos.ToolOutcome> toolOutcomes) {

        List<String> failed = new ArrayList<>();

        if (answer == null || answer.isBlank()) {
            return new Verdict(false, List.of("EMPTY_ANSWER"), 0.0, retrievalConfidence,
                    "The model returned nothing.");
        }
        if (looksLikeRawStructuredPayload(answer)) {
            failed.add("RAW_STRUCTURED_PAYLOAD");
        }

        double coverage = citationBuilder.coverage(answer);
        // SRS 4.1.2 is a claim-level rule: every factual claim must identify its
        // source. CitationAttacher repairs omitted syntax when support can be proven;
        // anything that remains uncited here is either unsupported or ambiguous and
        // must not be delivered.
        if (!citationAttacher.uncitedFactualSentences(answer).isEmpty()) {
            failed.add("MISSING_CITATION");
        }

        if (!numbersAreSupported(answer, evidence, toolGrounded)) {
            failed.add("UNSUPPORTED_CLAIM");
        }

        if (retrievalConfidence < properties.getRag().getEscalationConfidence() && !toolGrounded) {
            failed.add("LOW_CONFIDENCE");
        }

        if (followups == null || followups.size() < properties.getFollowups().getMinimum()) {
            failed.add("INSUFFICIENT_FOLLOWUPS");
        }

        if (actorRole == Role.PASSENGER && leaksStaffMaterial(answer, evidence)) {
            failed.add("ACTOR_SCOPE_LEAK");
        }

        if (makesForbiddenCommitment(answer)) {
            failed.add("POLICY_DEVIATION");
        }

        CompletenessEvaluator.Result completeness = completenessEvaluator.evaluate(
                requirements, answer, evidence, toolOutcomes);
        for (String gate : completeness.failedGates()) {
            if (!failed.contains(gate)) {
                failed.add(gate);
            }
        }

        String detail = failed.isEmpty()
                ? "All gates passed."
                : "Failed: " + String.join(", ", failed)
                    + "; missing topics=" + completeness.missingTopics()
                    + "; missing categories=" + completeness.missingCategories();
        return new Verdict(
                failed.isEmpty(), failed, coverage,
                retrievalConfidence, detail);
    }

    private static boolean looksLikeRawStructuredPayload(String answer) {
        return answer != null && RAW_STRUCTURED_PAYLOAD.matcher(answer).find();
    }

    /**
     * Checks that every currency, percentage, weight and duration in the answer also occurs
     * in the evidence it was drawn from.
     *
     * <p>Deliberately lenient in two ways. Digits are compared after stripping separators,
     * so "INR 2,000" matches evidence reading "2000". And a tool-grounded answer is exempt,
     * because the numbers there come from a verified live result whose JSON formatting will
     * not match the prose. The gate exists to catch a fee the model made up, not to police
     * formatting.
     */
    private boolean numbersAreSupported(String answer,
                                        List<RetrievalDtos.Ranked> evidence,
                                        boolean toolGrounded) {
        if (toolGrounded) {
            return true;
        }
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }

        StringBuilder corpus = new StringBuilder();
        for (RetrievalDtos.Ranked ranked : evidence) {
            corpus.append(ranked.chunk().content()).append('\n');
        }
        String haystack = corpus.toString().replaceAll("[,\\s]", "");

        Matcher matcher = NUMERIC_CLAIM.matcher(answer);
        int checked = 0;
        int unsupported = 0;

        while (matcher.find()) {
            String value = firstNonNull(matcher.group(1), matcher.group(2),
                    matcher.group(3), matcher.group(4));
            if (value == null) {
                continue;
            }
            String normalised = value.replace(",", "").replaceAll("\\.0+$", "");
            // Single digits appear everywhere and prove nothing either way.
            if (normalised.length() < 2) {
                continue;
            }
            checked++;
            if (!haystack.contains(normalised)) {
                unsupported++;
            }
        }

        // Tolerate one stray figure - a page reference or a rounded restatement - but not
        // an answer whose numbers largely cannot be found in its sources.
        return checked == 0 || unsupported <= 1;
    }

    private boolean leaksStaffMaterial(
            String answer, List<RetrievalDtos.Ranked> evidence) {
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String term : STAFF_ONLY_TERMS) {
            if (lower.contains(term)
                    && !passengerVisibleEvidenceSupports(term, evidence)) {
                return true;
            }
        }
        return false;
    }

    private boolean passengerVisibleEvidenceSupports(
            String term, List<RetrievalDtos.Ranked> evidence) {
        if (evidence == null) {
            return false;
        }
        return evidence.stream().map(RetrievalDtos.Ranked::chunk).anyMatch(chunk ->
                chunk.audience() != null
                        && List.of(chunk.audience().split(",")).stream()
                                .map(String::trim)
                                .anyMatch("Passenger"::equalsIgnoreCase)
                        && chunk.content() != null
                        && chunk.content().toLowerCase(Locale.ROOT).contains(term));
    }

    private boolean makesForbiddenCommitment(String answer) {
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN_COMMITMENTS) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * @param passed  whether the answer may be delivered as is
     * @param failedGates the gates that rejected it, used to steer the repair attempt
     */
    public record Verdict(
            boolean passed,
            List<String> failedGates,
            double citationCoverage,
            double confidence,
            String detail) {

        /**
         * Some failures cannot be repaired by rewriting. If the evidence was too weak, or
         * the answer would leak staff material to a passenger, a second attempt at the same
         * evidence will fail the same way - those go straight to a human.
         */
        public boolean isRepairable() {
            return !failedGates.contains("LOW_CONFIDENCE")
                    && !failedGates.contains("ACTOR_SCOPE_LEAK");
        }

        public String gatesAsString() {
            return String.join(",", failedGates);
        }
    }
}
