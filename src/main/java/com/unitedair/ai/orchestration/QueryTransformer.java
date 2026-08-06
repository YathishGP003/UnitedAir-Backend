package com.unitedair.ai.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.unitedair.ai.llm.ChatDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rewrites a turn into a standalone question using ChatMemory (SRS 2.1, 4.1.4A).
 *
 * <p>Retrieval sees one string. "What about the return leg?" retrieves nothing useful on its
 * own, and no amount of reranking rescues it - the question simply does not contain the
 * words the answer is filed under. The rewrite carries the antecedent forward so the
 * retriever has something to work with.
 *
 * <p>This is done with rules rather than a model call. Follow-ups in this domain are short
 * and formulaic, and spending a round trip and a token budget to expand "and for business
 * class?" is not a good trade. The rewritten query is recorded on the retrieval run, so
 * anyone auditing a turn can see exactly what was searched for and why.
 */
@Component
public class QueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(QueryTransformer.class);

    /** Openings that signal the turn continues a previous one. */
    private static final List<String> CONTINUATION_MARKERS = List.of(
            "what about", "how about", "and for", "and what", "what if", "and if",
            "even if", "and ", "same for", "also for", "that one", "the same",
            "ok and", "okay and",
            "then what", "and then", "for that", "in that case", "same trip",
            "explain the related", "tell me more about the related",
            "explain how this policy", "how does this policy");

    /** Standalone words that only mean something with an antecedent. */
    private static final List<String> BARE_REFERENCES = List.of(
            "it", "its", "that", "this", "those", "these", "them", "they", "one", "there");

    private static final int SHORT_QUERY_WORDS = 6;
    private static final Pattern PAIRED_LOCATIONS = Pattern.compile(
            "(?i)^\\s*([a-z]{3,20}(?:\\s+[a-z]{2,20})?)\\s+and\\s+"
                    + "([a-z]{3,20}(?:\\s+[a-z]{2,20})?)\\s*[?.!]*\\s*$");
    private static final Pattern BARE_BOOKING_REFERENCE =
            Pattern.compile("(?i)^\\s*[a-z0-9]{6}\\s*[?.!]*\\s*$");
    private static final Pattern STANDALONE_AMBIGUOUS = Pattern.compile(
            "(?i)^\\s*(?:help|help me|something went wrong|it is not working"
                    + "|this is not working|i need help|i need operations help"
                    + "|hello i need operations help|can u help pls|book me something"
                    + "|my trip has a problem|tell me everything|i have a problem"
                    + "|there is an issue|i have an issue)\\s*[?.!]*\\s*$");
    private static final Pattern TEMPORAL_DETAIL = Pattern.compile(
            "(?i).*\\b(?:today|tomorrow|tonight|next\\s+\\w+|in\\s+\\d+\\s+days?"
                    + "|[a-z]+\\s+days?\\s+from\\s+now"
                    + "|on\\s+\\d{1,2}[-/ ]\\w+|\\d{4}-\\d{2}-\\d{2})\\b.*");

    public Transformation transform(
            String query,
            List<ChatDtos.HistoryTurn> history,
            ContextRelevancePolicy.Decision decision) {
        if (decision == null || !decision.useHistory()) {
            return new Transformation(query, query, false, List.of());
        }
        String standalone = "PENDING_SLOT".equals(decision.reason())
                ? completePendingSlot(query, history)
                : toStandalone(query, history);
        boolean used = standalone != null && !standalone.equals(query);
        return new Transformation(
                query,
                standalone,
                used,
                used ? decision.supportingTurnIndexes() : List.of());
    }

    /**
     * A direct slot value completes the operation that requested it. Treating a bare PNR
     * as an unrelated standalone booking lookup loses the user's earlier action intent
     * (for example, "cancel my flight" followed by the requested reference).
     */
    private static String completePendingSlot(
            String query,
            List<ChatDtos.HistoryTurn> history) {
        String antecedent = contextualUserTurns(history, query);
        if (antecedent == null || antecedent.isBlank()) {
            return query;
        }
        return antecedent.trim() + " " + query.trim();
    }

    public String toStandalone(String query, List<ChatDtos.HistoryTurn> history) {
        if (query == null || query.isBlank() || history == null || history.isEmpty()) {
            return query;
        }
        // A direct slot value is already standalone. Never concatenate it with an older
        // booking-creation or policy turn.
        if (BARE_BOOKING_REFERENCE.matcher(query).matches()) {
            return query;
        }
        if (!needsContext(query)) {
            return query;
        }

        String antecedent = contextualUserTurns(history, query);
        if (antecedent == null || antecedent.isBlank()) {
            return query;
        }

        Matcher pairedLocations = PAIRED_LOCATIONS.matcher(query);
        if (pairedLocations.matches()
                && antecedent.toLowerCase(Locale.ROOT).contains("flight")) {
            return antecedent.trim() + " from " + pairedLocations.group(1).trim()
                    + " to " + pairedLocations.group(2).trim();
        }

        // Keep both: the antecedent supplies the retrievable nouns, the new turn supplies
        // what is actually being asked.
        String rewritten = antecedent.trim() + " " + query.trim();
        log.debug("Rewrote follow-up '{}' using prior turn", query);
        return rewritten;
    }

    /** True when the turn cannot stand on its own. */
    boolean needsContext(String query) {
        String lower = query.toLowerCase(Locale.ROOT).trim();

        if (STANDALONE_AMBIGUOUS.matcher(lower).matches()) {
            return false;
        }
        if (lower.contains("[air-pnr-redacted]")) {
            return false;
        }
        if (lower.matches(
                "^what about (?:business|economy|premium economy|first) class[?.!]*$")) {
            return false;
        }

        for (String marker : CONTINUATION_MARKERS) {
            if (lower.startsWith(marker)) {
                return true;
            }
        }

        if (lower.matches(".*\\b(?:which|what) gate\\b.*")
                || lower.contains("what cabin did i")
                || lower.contains("what fare did i")
                || lower.contains("my refund")
                || lower.contains("refund back")) {
            return true;
        }

        // "From Bangalore to Delhi" is a complete route but can still be the
        // answer to the previous turn's missing route slot. Preserve an earlier
        // relative date such as "tomorrow" instead of asking for it again.
        if (lower.matches("^from\\s+.{2,30}\\s+to\\s+.{2,30}[?.!]*$")) {
            return true;
        }

        String[] words = lower.split("\\s+");
        // A bare reference needs an antecedent even when the follow-up is a full sentence:
        // "Can we exceed that limit and who approves it?"
        for (String word : words) {
            String stripped = word.replaceAll("[^a-z]", "");
            if (BARE_REFERENCES.contains(stripped)) {
                return true;
            }
        }

        if (words.length > SHORT_QUERY_WORDS) {
            return false;
        }

        // Very short fragments are almost always continuations: "business class?"
        return words.length <= 3;
    }

    private static String contextualUserTurns(
            List<ChatDtos.HistoryTurn> history,
            String currentQuery) {
        String mostRecent = null;
        List<String> context = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatDtos.HistoryTurn turn = history.get(i);
            if ("USER".equalsIgnoreCase(turn.role())) {
                if (mostRecent == null) {
                    mostRecent = turn.content();
                }
                context.addFirst(turn.content());
                if (isContextAnchor(turn.content())) {
                    if (!TEMPORAL_DETAIL.matcher(turn.content()).matches()
                            && !TEMPORAL_DETAIL.matcher(currentQuery).matches()) {
                        for (int earlier = i - 1; earlier >= 0; earlier--) {
                            ChatDtos.HistoryTurn candidate = history.get(earlier);
                            if ("USER".equalsIgnoreCase(candidate.role())
                                    && TEMPORAL_DETAIL.matcher(candidate.content()).matches()) {
                                context.addFirst(candidate.content());
                                break;
                            }
                        }
                    }
                    return String.join(" ", context);
                }
            }
        }
        return mostRecent;
    }

    private static boolean isContextAnchor(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("[air-pnr-redacted]")
                || lower.matches(".*\\b[a-z]{2}\\s?\\d{2,4}\\b.*")
                || lower.matches(".*\\bfrom\\s+.{2,30}\\s+to\\s+.{2,30}.*")
                || lower.matches("^\\s*(?:flights?\\s+)?[a-z ]{3,24}\\s+to\\s+[a-z ]{3,24}.*")
                || lower.matches(".*\\b(?:baggage|refund|cancellation|no-show|no show"
                        + "|overbooking|oversold|waitlist|denied boarding|wheelchair|wchr|meda"
                        + "|petc|avih|documents?|passport|visa|dgca|car-7"
                        + "|check[ -]?in|identification|photo id"
                        + "|duty[ -]time|rest period)\\b.*");
    }

    public record Transformation(
            String original,
            String standalone,
            boolean usedHistory,
            List<Integer> supportingTurnIndexes) {

        public Transformation {
            supportingTurnIndexes = supportingTurnIndexes == null
                    ? List.of() : List.copyOf(supportingTurnIndexes);
        }
    }
}
