package com.unitedair.ai.orchestration;

import com.unitedair.ai.llm.ChatDtos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Decides whether a current turn is allowed to inherit prior conversation text. */
@Component
public class ContextRelevancePolicy {

    private static final Pattern BARE_PNR =
            Pattern.compile("(?i)^\\s*[a-z0-9]{6}\\s*[?.!]*\\s*$");
    private static final Pattern ARITHMETIC =
            Pattern.compile("^\\s*(?:what\\s+is\\s+)?[-+*/().\\d\\s]+[?.!]*\\s*$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_SLOT = Pattern.compile(
            "(?i)^\\s*(?:today|tomorrow|tonight|\\d{4}-\\d{2}-\\d{2}"
                    + "|from\\s+.+\\s+to\\s+.+|[a-z]{3}\\s+to\\s+[a-z]{3})[?.!]*\\s*$");
    private static final Set<String> CONTINUATIONS = Set.of(
            "what about", "how about", "and ", "and for", "and what", "what if",
            "even if", "same for", "also for", "that one", "the same", "then what",
            "and then", "for that", "in that case", "same trip",
            "explain the related", "tell me more about the related");
    private static final Pattern REFERENCE = Pattern.compile(
            "(?i).*\\b(?:it|its|that|this|those|these|them|they|one|same"
                    + "|such|there|the flight|the booking|the trip|my booking"
                    + "|my refund|my flight)\\b.*");
    private static final Pattern KNOWN_FOLLOW_UP = Pattern.compile(
            "(?i).*\\b(?:what cabin did i|what fare did i|which gate"
                    + "|what gate|is it refundable|refund back)\\b.*");
    private static final Pattern GENERAL_TOPIC = Pattern.compile(
            "(?i).*\\b(?:earth|math|calculate|code|programming|weather|politics"
                    + "|president|medical|doctor|legal|stock|crypto|recipe)\\b.*");

    public Decision evaluate(
            String currentQuery,
            List<ChatDtos.HistoryTurn> history,
            String pendingSlot) {
        String query = currentQuery == null ? "" : currentQuery.trim();
        List<ChatDtos.HistoryTurn> safeHistory =
                history == null ? List.of() : history;
        if (query.isBlank() || safeHistory.isEmpty()) {
            return Decision.ignore("NO_CONTEXT");
        }

        if (pendingSlot != null && !pendingSlot.isBlank()
                && isDirectSlotValue(query, pendingSlot)) {
            return new Decision(true, "PENDING_SLOT",
                    latestUserIndexes(safeHistory, 1));
        }

        String lower = query.toLowerCase(Locale.ROOT);
        if (ARITHMETIC.matcher(lower).matches()
                || GENERAL_TOPIC.matcher(lower).matches()) {
            return Decision.ignore("COMPLETE_NEW_TOPIC");
        }

        boolean continuation = CONTINUATIONS.stream().anyMatch(lower::startsWith);
        boolean reference = REFERENCE.matcher(lower).matches()
                || KNOWN_FOLLOW_UP.matcher(lower).matches();
        boolean directRouteAnswer = lower.matches(
                "^from\\s+.{2,30}\\s+to\\s+.{2,30}[?.!]*$")
                || lower.matches(
                        "^[a-z][a-z ]{1,29}\\s+to\\s+[a-z][a-z ]{1,29}[?.!]*$");
        if (!continuation && !reference && !directRouteAnswer) {
            return Decision.ignore("COMPLETE_CURRENT_TURN");
        }

        List<Integer> indexes = latestCompatibleUserIndexes(safeHistory);
        return indexes.isEmpty()
                ? Decision.ignore("NO_COMPATIBLE_ANTECEDENT")
                : new Decision(true, continuation ? "EXPLICIT_CONTINUATION" : "REFERENCE",
                        indexes);
    }

    private static boolean isDirectSlotValue(String query, String pendingSlot) {
        String slot = pendingSlot.trim().toUpperCase(Locale.ROOT);
        if ("PNR".equals(slot)) {
            return BARE_PNR.matcher(query).matches()
                    || query.toUpperCase(Locale.ROOT).contains("[AIR-PNR-REDACTED]");
        }
        return DIRECT_SLOT.matcher(query).matches();
    }

    private static List<Integer> latestCompatibleUserIndexes(
            List<ChatDtos.HistoryTurn> history) {
        return latestUserIndexes(history, 2);
    }

    private static List<Integer> latestUserIndexes(
            List<ChatDtos.HistoryTurn> history,
            int maximum) {
        ArrayList<Integer> indexes = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && indexes.size() < maximum; i--) {
            if ("USER".equalsIgnoreCase(history.get(i).role())) {
                indexes.addFirst(i);
                if (isAnchor(history.get(i).content())) {
                    break;
                }
            }
        }
        return List.copyOf(indexes);
    }

    private static boolean isAnchor(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("[air-pnr-redacted]")
                || lower.matches(".*\\bfrom\\s+.+\\s+to\\s+.+")
                || lower.matches(".*\\b(?:booking|refund|flight|baggage|check[ -]?in"
                        + "|seat|fare|cancellation)\\b.*");
    }

    public record Decision(
            boolean useHistory,
            String reason,
            List<Integer> supportingTurnIndexes) {

        public Decision {
            supportingTurnIndexes = supportingTurnIndexes == null
                    ? List.of() : List.copyOf(supportingTurnIndexes);
        }

        public static Decision ignore(String reason) {
            return new Decision(false, reason, List.of());
        }
    }
}
