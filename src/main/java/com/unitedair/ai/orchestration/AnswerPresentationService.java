package com.unitedair.ai.orchestration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import org.springframework.stereotype.Service;

/**
 * Final presentation boundary for grounded answers.
 *
 * <p>Models and deterministic fallbacks may both produce table-shaped evidence. This
 * service prevents those parser-oriented shapes from reaching a user and applies the same
 * contract to initial and regenerated answers.
 */
@Service
public final class AnswerPresentationService {

    private static final Pattern NUMBERED_POLICY_HEADING =
            Pattern.compile("^\\d+\\s+passenger[- ]initiated cancellation policy.*$",
                    Pattern.CASE_INSENSITIVE);

    public record PresentationRequest(
            Role role,
            String query,
            String generatedText,
            List<RetrievalDtos.Ranked> evidence,
            String intent,
            boolean regenerated) { }

    public record PresentedAnswer(
            String text,
            boolean valid,
            String repairReason,
            boolean usedDeterministicFallback) { }

    public PresentedAnswer present(PresentationRequest request) {
        String normalized = normalize(request == null ? null : request.generatedText());
        if (normalized.isBlank()) {
            return new PresentedAnswer(normalized, false, "EMPTY_PRESENTATION", false);
        }
        if (looksLikeRawStructuredPayload(normalized)) {
            return new PresentedAnswer(
                    "I couldn’t safely format that result. Please try again.",
                    true,
                    "RAW_STRUCTURED_PAYLOAD",
                    true);
        }
        if (containsDashDelimitedCancellationRows(normalized, request)) {
            return new PresentedAnswer(
                    formatRawEvidence(convertDashDelimitedRows(normalized)),
                    true,
                    "RAW_EVIDENCE_PRESENTATION",
                    true);
        }
        if (!containsRawEvidenceShape(normalized)) {
            return new PresentedAnswer(normalized, true, null, false);
        }
        return new PresentedAnswer(
                formatRawEvidence(normalized),
                true,
                "RAW_EVIDENCE_PRESENTATION",
                true);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\u00a0", " ")
                .replaceAll(
                        "(\\[(?:E|T)\\d{1,2}\\])(?=\\[(?:E|T)\\d{1,2}\\])",
                        "$1 ")
                .replaceAll("[\\t ]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static boolean containsRawEvidenceShape(String text) {
        return text.lines().anyMatch(line -> line.chars().filter(ch -> ch == '|').count() >= 2);
    }

    private static boolean containsDashDelimitedCancellationRows(
            String text, PresentationRequest request) {
        String query = request == null || request.query() == null
                ? ""
                : request.query().toLowerCase(Locale.ROOT);
        String intent = request == null || request.intent() == null
                ? ""
                : request.intent().toLowerCase(Locale.ROOT);
        boolean cancellationContext = query.contains("cancel")
                || query.contains("refund")
                || intent.contains("cancel")
                || text.toLowerCase(Locale.ROOT).contains("cancellation fee matrix");
        return cancellationContext && text.lines().anyMatch(line ->
                line.split("\\s+-\\s+", -1).length >= 4);
    }

    private static String convertDashDelimitedRows(String text) {
        return text.lines()
                .map(line -> line.split("\\s+-\\s+", -1).length >= 4
                        ? line.replaceAll("\\s+-\\s+", " | ")
                        : line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static boolean looksLikeRawStructuredPayload(String text) {
        String trimmed = text == null ? "" : text.stripLeading();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return false;
        }
        int objectEnd = trimmed.indexOf('}');
        int arrayEnd = trimmed.indexOf(']');
        int structuredEnd = objectEnd >= 0 ? objectEnd : arrayEnd;
        if (structuredEnd < 0) {
            return false;
        }
        return trimmed.substring(0, structuredEnd + 1)
                .matches("(?s).*[\"'][A-Za-z][A-Za-z0-9_]*[\"']\\s*:.*");
    }

    private static String formatRawEvidence(String text) {
        List<String> output = new ArrayList<>();
        List<String> tableHeaders = List.of();
        boolean cancellation = text.lines().anyMatch(AnswerPresentationService::isCancellationRow)
                || text.toLowerCase(Locale.ROOT).contains("cancellation policy");

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isBlank() || NUMBERED_POLICY_HEADING.matcher(line).matches()) {
                continue;
            }
            if (!line.contains("|")) {
                output.add(line);
                continue;
            }

            List<String> cells = Arrays.stream(line.split("\\|"))
                    .map(String::trim)
                    .filter(cell -> !cell.isBlank())
                    .toList();
            if (isHeaderRow(cells)) {
                tableHeaders = cells;
                continue;
            }
            output.add(formatRow(cells, cancellation, tableHeaders));
        }

        String body = String.join("\n\n", output).trim();
        if (cancellation) {
            return "Here are the passenger-initiated cancellation terms:\n\n" + body;
        }
        return body;
    }

    private static boolean isCancellationRow(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return line.contains("|") && (lower.contains("before departure")
                || lower.contains("base fare")
                || lower.contains("statutory taxes"));
    }

    private static boolean isHeaderRow(List<String> cells) {
        if (cells.isEmpty()) {
            return false;
        }
        String joined = String.join(" ", cells).toLowerCase(Locale.ROOT);
        boolean cancellationHeader = joined.contains("fare category")
                && joined.contains("cancellation timing")
                && joined.contains("cancellation fee");
        if (cancellationHeader) {
            return true;
        }
        String first = stripCitation(cells.get(0)).toLowerCase(Locale.ROOT);
        boolean namedFirstColumn = first.contains("category")
                || first.contains("type")
                || first.contains("class")
                || first.contains("criterion");
        boolean descriptiveColumns = cells.stream().skip(1)
                .map(AnswerPresentationService::stripCitation)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("description")
                        || value.contains("fee")
                        || value.contains("condition")
                        || value.contains("requirement")
                        || value.contains("timing"));
        return namedFirstColumn && descriptiveColumns;
    }

    private static String formatRow(
            List<String> cells, boolean cancellation, List<String> headers) {
        if (cells.size() >= 4 && isCabinBaggageRow(cells)) {
            return "- **Cabin baggage:** " + cells.get(1) + ", up to " + cells.get(2)
                    + ", maximum dimensions " + cells.get(3);
        }
        if (cells.size() == 3 && isCheckedBaggageRow(cells)) {
            return "- **Checked baggage (" + cells.get(1) + " fare):** " + cells.get(2);
        }
        if (cancellation && cells.size() >= 4) {
            return formatCancellationRow(cells);
        }
        if (headers.size() == cells.size() && cells.size() >= 2) {
            return formatLabeledRow(cells, headers);
        }
        return "- " + String.join(": ", cells);
    }

    private static String formatLabeledRow(List<String> cells, List<String> headers) {
        List<String> details = new ArrayList<>();
        for (int i = 1; i < cells.size(); i++) {
            String header = stripCitation(headers.get(i))
                    .replaceAll("[.:]+$", "")
                    .trim();
            if (i == 1 && ("description".equalsIgnoreCase(header)
                    || "summary".equalsIgnoreCase(header))) {
                details.add(cells.get(i));
            } else {
                details.add("**" + sentenceLabel(header) + ":** " + cells.get(i));
            }
        }
        return "- **" + stripCitation(cells.get(0)) + ":** "
                + String.join("; ", details);
    }

    private static String sentenceLabel(String value) {
        if (value.isBlank()) {
            return value;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static boolean isCabinBaggageRow(List<String> cells) {
        return "economy".equalsIgnoreCase(cells.get(0))
                && cells.get(1).toLowerCase(Locale.ROOT).contains("piece")
                && cells.get(2).toLowerCase(Locale.ROOT).contains("kg");
    }

    private static boolean isCheckedBaggageRow(List<String> cells) {
        return "economy".equalsIgnoreCase(cells.get(0))
                && cells.get(2).toLowerCase(Locale.ROOT).contains("kg");
    }

    private static String formatCancellationRow(List<String> cells) {
        String fare = cells.get(0);
        String timing = lowerFirst(cells.get(1));
        String fee = translateFee(cells.get(2));
        String baseFare = cells.size() > 3 ? translateBaseFare(cells.get(3)) : "";
        String taxes = cells.size() > 4 ? translateTaxes(cells.get(4)) : "";

        List<String> details = new ArrayList<>();
        details.add("Cancellation fee: " + fee);
        if (!baseFare.isBlank()) {
            details.add(baseFare);
        }
        if (!taxes.isBlank()) {
            details.add(taxes);
        }
        return "- **" + fare + ", " + timing + ":** " + String.join("; ", details);
    }

    private static String translateFee(String value) {
        return isNil(value) ? "No cancellation fee" : value;
    }

    private static String translateBaseFare(String value) {
        if (isNil(value)) {
            return "No base fare is refundable";
        }
        if ("full base fare".equalsIgnoreCase(stripCitation(value))) {
            return "Full base fare is refundable" + citationSuffix(value);
        }
        if (stripCitation(value).toLowerCase(Locale.ROOT).contains("balance")) {
            return "Remaining base fare after the fee is refundable" + citationSuffix(value);
        }
        return value;
    }

    private static String translateTaxes(String value) {
        if ("yes".equalsIgnoreCase(stripCitation(value))) {
            return "taxes are refundable" + citationSuffix(value);
        }
        if ("no".equalsIgnoreCase(stripCitation(value)) || isNil(value)) {
            return "taxes are not refundable" + citationSuffix(value);
        }
        return value;
    }

    private static boolean isNil(String value) {
        return "nil".equalsIgnoreCase(stripCitation(value))
                || "none".equalsIgnoreCase(stripCitation(value));
    }

    private static String stripCitation(String value) {
        return value.replaceAll("\\s*\\[E\\d+\\][.]?\\s*$", "").trim();
    }

    private static String citationSuffix(String value) {
        var matcher = Pattern.compile("(\\[E\\d+\\])").matcher(value);
        return matcher.find() ? " " + matcher.group(1) : "";
    }

    private static String lowerFirst(String value) {
        if (value.isBlank()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
