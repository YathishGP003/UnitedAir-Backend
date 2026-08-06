package com.unitedair.ai.privacy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.springframework.stereotype.Component;

/**
 * Detects and redacts the ten PII classes of SRS 4.1.6.
 *
 * <p>Runs before anything else in the pipeline. The redacted form is what gets persisted,
 * logged, stored in ChatMemory and sent to the model; the raw text does not outlive the
 * request.
 *
 * <h2>Why placeholders instead of direct substitution</h2>
 * Substituting tokens directly as we go would let a later pattern match text that an
 * earlier one had already inserted. {@code [AIR-FFP-ID-REDACTED]} contains a six-character
 * uppercase run, so the PNR pattern would cheerfully redact part of a token that was
 * already a token. Each match is therefore replaced with a private placeholder that no
 * pattern can match, and placeholders are swapped for their real tokens in a single final
 * pass. Redaction is idempotent as a result: redacting twice yields the same string.
 */
@Component
public class PiiRedactor {

    /**
     * Placeholder delimiter. U+0000 cannot appear in a JSON string or in text extracted
     * from a document, so no pattern and no user input can collide with it.
     */
    private static final char SENTINEL = '\u0000';

    /**
     * Redacts every recognised PII value in {@code text}.
     *
     * @return the redacted text plus the request-scoped vault of original values
     */
    public RedactionResult redact(String text) {
        if (text == null || text.isBlank()) {
            return RedactionResult.unchanged(text == null ? "" : text);
        }

        Map<PiiType, List<String>> originals = new EnumMap<>(PiiType.class);
        List<String> placeholderTokens = new ArrayList<>();
        String working = text;

        // PiiType declaration order is the matching order; see that enum for why.
        for (PiiType type : PiiType.values()) {
            Matcher matcher = type.pattern().matcher(working);
            StringBuilder rebuilt = new StringBuilder();
            int lastEnd = 0;
            boolean matchedAny = false;

            while (matcher.find()) {
                String match = matcher.group();

                if (type == PiiType.PAYMENT_CARD && !isLuhnValid(match)) {
                    // A long digit run that fails the checksum is not a card. Leaving it
                    // alone lets AADHAAR and PHONE evaluate it on the next passes.
                    continue;
                }

                matchedAny = true;
                rebuilt.append(working, lastEnd, matcher.start());
                rebuilt.append(SENTINEL).append(placeholderTokens.size()).append(SENTINEL);
                placeholderTokens.add(type.token());
                originals.computeIfAbsent(type, k -> new ArrayList<>()).add(match);
                lastEnd = matcher.end();
            }

            if (matchedAny) {
                rebuilt.append(working.substring(lastEnd));
                working = rebuilt.toString();
            }
        }

        if (placeholderTokens.isEmpty()) {
            return RedactionResult.unchanged(text);
        }

        // Single final pass: placeholders become their SRS tokens.
        StringBuilder out = new StringBuilder(working.length());
        for (int i = 0; i < working.length(); i++) {
            char c = working.charAt(i);
            if (c != SENTINEL) {
                out.append(c);
                continue;
            }
            int close = working.indexOf(SENTINEL, i + 1);
            if (close < 0) {
                out.append(c);
                continue;
            }
            int index = Integer.parseInt(working.substring(i + 1, close));
            out.append(placeholderTokens.get(index));
            i = close;
        }

        return new RedactionResult(out.toString(), originals, placeholderTokens.size());
    }

    /**
     * True when the text already contains at least one redaction token. Used to assert the
     * invariant that nothing reaches persistence unredacted.
     */
    public boolean containsRedactionToken(String text) {
        if (text == null) {
            return false;
        }
        for (PiiType type : PiiType.values()) {
            if (text.contains(type.token())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Luhn checksum. Card numbers satisfy it; arbitrary 13-19 digit numbers almost never
     * do, which is what stops fare amounts and booking references being redacted as cards.
     */
    private static boolean isLuhnValid(String candidate) {
        int sum = 0;
        boolean doubling = false;
        int digits = 0;

        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            digits++;
            int d = c - '0';
            if (doubling) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubling = !doubling;
        }

        return digits >= 13 && digits <= 19 && sum % 10 == 0;
    }
}
