package com.unitedair.ai.privacy;

import com.unitedair.ai.llm.ChatDtos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds conversation-safe recognition for legacy all-letter booking references.
 *
 * <p>The general PII pattern deliberately requires both a letter and a digit so ordinary
 * words are not redacted as PNRs. Older/generated bookings can nevertheless contain six
 * letters. Those values are accepted only when explicitly labelled as a PNR or when the
 * immediately preceding assistant turn requested a six-character booking reference.
 */
@Component
public final class PnrContextRedactor {

    private static final Pattern EXPLICIT_PNR = Pattern.compile(
            "(?i)(?:\\bpnr\\b|\\bbooking\\s+reference\\b|\\brecord\\s+locator\\b)"
                    + "\\s*(?:is\\s*)?[:#-]?\\s*([A-Z0-9]{6})(?![A-Z0-9])");
    private static final Pattern BARE_PNR =
            Pattern.compile("^[A-Z0-9]{6}$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> RESERVED_WORKFLOW_WORDS =
            Set.of("REFUND", "CANCEL");

    private final PiiRedactor base;

    public PnrContextRedactor(PiiRedactor base) {
        this.base = base;
    }

    public RedactionResult redact(
            String rawText, List<ChatDtos.HistoryTurn> history) {
        return redact(rawText, history, null);
    }

    public RedactionResult redact(
            String rawText,
            List<ChatDtos.HistoryTurn> history,
            String pendingSlot) {
        RedactionResult standard = base.redact(rawText);
        if (standard.first(PiiType.PNR).isPresent() || rawText == null) {
            return standard;
        }

        String candidate = explicitCandidate(rawText);
        if (candidate == null
                && ("pnr".equalsIgnoreCase(pendingSlot) || awaitingPnr(history))) {
            String trimmed = rawText.trim();
            if (BARE_PNR.matcher(trimmed).matches()
                    && !RESERVED_WORKFLOW_WORDS.contains(
                            trimmed.toUpperCase(Locale.ROOT))) {
                candidate = trimmed;
            }
        }
        if (candidate == null) {
            return standard;
        }

        String normalized = candidate.toUpperCase(Locale.ROOT);
        Pattern valuePattern = Pattern.compile(
                "(?i)(?<![A-Z0-9])" + Pattern.quote(candidate) + "(?![A-Z0-9])");
        Matcher valueMatcher = valuePattern.matcher(standard.redacted());
        if (!valueMatcher.find()) {
            return standard;
        }

        String redacted = valueMatcher.replaceFirst(
                Matcher.quoteReplacement(PiiType.PNR.token()));
        Map<PiiType, List<String>> originals = new EnumMap<>(PiiType.class);
        standard.originals().forEach((type, values) ->
                originals.put(type, new ArrayList<>(values)));
        originals.put(PiiType.PNR, List.of(normalized));
        return new RedactionResult(
                redacted, Map.copyOf(originals), standard.redactionCount() + 1);
    }

    private static String explicitCandidate(String rawText) {
        Matcher matcher = EXPLICIT_PNR.matcher(rawText);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean awaitingPnr(List<ChatDtos.HistoryTurn> history) {
        if (history == null) {
            return false;
        }
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatDtos.HistoryTurn turn = history.get(index);
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if (!"ASSISTANT".equalsIgnoreCase(turn.role())) {
                return false;
            }
            String lower = turn.content().toLowerCase(Locale.ROOT);
            return lower.contains("six-character booking reference")
                    || (lower.contains("pnr") && (lower.contains("share")
                        || lower.contains("enter") || lower.contains("provide")));
        }
        return false;
    }
}
