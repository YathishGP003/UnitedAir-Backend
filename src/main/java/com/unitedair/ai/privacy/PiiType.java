package com.unitedair.ai.privacy;

import java.util.regex.Pattern;

/**
 * The ten PII classes of SRS 4.1.6, with their redaction tokens reproduced verbatim.
 *
 * <p><b>Declaration order is the matching order and is load bearing.</b> Longer, more
 * specific formats are declared before shorter, more permissive ones, because the first
 * pattern to claim a span wins. Three orderings in particular are deliberate:
 *
 * <ul>
 *   <li>{@code EMAIL} first: an address contains letters, digits and dots that later
 *       numeric patterns would happily fragment.</li>
 *   <li>{@code PAYMENT_CARD} (13-19 digits) before {@code AADHAAR} (exactly 12) before
 *       {@code PHONE} (10). Otherwise a card number is shredded into a phone number
 *       and a remainder.</li>
 *   <li>{@code PNR} last. It is the loosest pattern - six alphanumerics - so it only
 *       gets to inspect text that no stricter pattern recognised.</li>
 * </ul>
 */
public enum PiiType {

    /** passenger@example.com */
    EMAIL(
            "[EMAIL-REDACTED]",
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")),

    /**
     * 4111 1111 1111 1111. Matches 13-19 digit groups in the common separator styles;
     * candidates are additionally Luhn-checked so ordinary long numbers (a booking
     * reference, an amount) are not mistaken for a card.
     */
    PAYMENT_CARD(
            "[AIR-CARD-NO-REDACTED]",
            Pattern.compile("(?<![0-9])(?:\\d{4}[ -]?){3}\\d{1,7}(?![0-9])")),

    /** 1234 5678 9012 - exactly twelve digits, optionally spaced in groups of four. */
    AADHAAR(
            "[AADHAAR-REDACTED]",
            Pattern.compile("(?<![0-9])\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?![0-9])")),

    /** +91 98765 43210. Indian mobile numbers begin 6-9 and run to ten digits. */
    PHONE(
            "[PHONE-REDACTED]",
            Pattern.compile("(?<![0-9])(?:\\+91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}(?![0-9])")),

    /** ZA-12345678 - UnitedAir frequent flyer number. */
    FREQUENT_FLYER(
            "[AIR-FFP-ID-REDACTED]",
            Pattern.compile("\\b[A-Z]{2}-\\d{8}\\b")),

    /** ABCDE1234F */
    PAN(
            "[PAN-REDACTED]",
            Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b")),

    /** P1234567 - one letter followed by seven digits. */
    PASSPORT(
            "[AIR-PASSPORT-NO-REDACTED]",
            Pattern.compile("\\b[A-PR-WYa-pr-wy]\\d{7}\\b")),

    /** 22/04/1985 - also accepts '-' and '.' separators. */
    DATE_OF_BIRTH(
            "[DOB-REDACTED]",
            Pattern.compile("\\b(?:0?[1-9]|[12]\\d|3[01])[/.\\-](?:0?[1-9]|1[0-2])[/.\\-](?:19|20)\\d{2}\\b")),

    /**
     * 45, Nehru Place, New Delhi 110019.
     *
     * <p>Free-text addresses cannot be matched reliably, so this deliberately requires the
     * strong signal: a house number, comma-separated locality parts, and a terminating
     * six-digit Indian PIN code. A looser pattern would redact ordinary prose such as
     * "Gate 12, Terminal 3" and make answers unreadable.
     */
    HOME_ADDRESS(
            "[ADDRESS-REDACTED]",
            Pattern.compile("\\b\\d{1,4}[A-Za-z]?\\s*,\\s*[^,\\n]{2,60}\\s*,\\s*[^,\\n]{2,40}?\\s*[-\\u2013]?\\s*\\d{6}\\b")),

    /**
     * B6X9K2 - six alphanumerics, at least one letter and at least one digit.
     *
     * <p>Requiring both character classes is what keeps this from redacting ordinary
     * six-letter words such as "REFUND" or "CANCEL" out of every sentence.
     */
    PNR(
            "[AIR-PNR-REDACTED]",
            Pattern.compile("(?<![A-Za-z0-9\\-])(?=[A-Z0-9]{6}(?![A-Za-z0-9]))(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{6}(?![A-Za-z0-9])"));

    private final String token;
    private final Pattern pattern;

    PiiType(String token, Pattern pattern) {
        this.token = token;
        this.pattern = pattern;
    }

    public String token() {
        return token;
    }

    public Pattern pattern() {
        return pattern;
    }
}
