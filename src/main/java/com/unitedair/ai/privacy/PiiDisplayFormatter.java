package com.unitedair.ai.privacy;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Converts safe persistence tokens into readable UI text without exposing originals. */
@Component
public class PiiDisplayFormatter {

    public enum DisplayContext {
        USER,
        ASSISTANT
    }

    private static final String PNR = Pattern.quote(PiiType.PNR.token());
    private static final Pattern LABELLED_PNR = Pattern.compile(
            "(?i)\\b(?:PNR|booking\\s+reference|record\\s+locator)\\s*(?:is\\s*)?[:#-]?\\s*"
                    + PNR);
    private static final Pattern POSSESSIVE_LABELLED_PNR = Pattern.compile(
            "(?i)\\b(?:your|my)\\s+"
                    + "(?:PNR|booking\\s+reference|record\\s+locator)"
                    + "\\s*(?:is\\s*)?[:#-]?\\s*" + PNR);
    private static final Pattern POSSESSIVE_LABELLED_PNR_AND = Pattern.compile(
            "(?i)\\b(?:your|my)\\s+"
                    + "(?:PNR|booking\\s+reference|record\\s+locator)"
                    + "\\s*(?:is\\s*)?[:#-]?\\s*" + PNR
                    + "\\s*,\\s*and\\s+(?:your|my)\\s+");
    private static final Pattern BOOKING_TOKEN = Pattern.compile(
            "(?i)\\bbooking\\s+" + PNR);
    private static final Pattern POSSESSIVE_BOOKING_TOKEN = Pattern.compile(
            "(?i)\\b(?:your|my)\\s+booking\\s+" + PNR);
    private static final Pattern POSSESSIVE_DIRECT_TOKEN = Pattern.compile(
            "(?i)\\b(?:your|my)\\s+" + PNR);
    private static final Pattern POSSESSIVE_FLIGHT_TOKEN = Pattern.compile(
            "(?i)\\b(?:your|my)\\s+flight\\s+" + PNR);
    private static final Pattern MARKDOWN_TOKEN = Pattern.compile(
            "\\*{1,2}\\s*" + PNR + "\\s*\\*{1,2}");
    private static final Pattern TOKEN_WITH_COLON = Pattern.compile(PNR + "\\s*:");

    public String display(String redactedText, DisplayContext context) {
        if (redactedText == null || redactedText.isBlank()) {
            return redactedText;
        }
        String readable = repairEncoding(redactedText);
        String reference = context == DisplayContext.USER
                ? "my booking reference"
                : "your booking reference";
        readable = MARKDOWN_TOKEN.matcher(readable).replaceAll(PiiType.PNR.token());
        readable = POSSESSIVE_LABELLED_PNR_AND.matcher(readable).replaceAll(
                context == DisplayContext.USER ? "My " : "Your ");
        readable = POSSESSIVE_LABELLED_PNR.matcher(readable).replaceAll(
                context == DisplayContext.USER
                        ? "My booking reference" : "Your booking reference");
        readable = POSSESSIVE_BOOKING_TOKEN.matcher(readable).replaceAll(
                context == DisplayContext.USER ? "My booking" : "Your booking");
        readable = POSSESSIVE_FLIGHT_TOKEN.matcher(readable).replaceAll(
                context == DisplayContext.USER ? "My booking" : "Your booking");
        readable = POSSESSIVE_DIRECT_TOKEN.matcher(readable).replaceAll(
                context == DisplayContext.USER
                        ? "My booking reference" : "Your booking reference");
        readable = LABELLED_PNR.matcher(readable).replaceAll(reference);
        readable = BOOKING_TOKEN.matcher(readable).replaceAll(reference);
        readable = TOKEN_WITH_COLON.matcher(readable).replaceAll(
                context == DisplayContext.USER ? "My booking:" : "Your booking:");
        return readable.replace(PiiType.PNR.token(), reference);
    }

    private static String repairEncoding(String value) {
        return value
                .replace("\u00e2\u0080\u00a2", "\u2022")
                .replace("\u00e2\u0080\u0093", "\u2013")
                .replace("\u00e2\u0080\u0094", "\u2014")
                .replace("\u00e2\u0080\u0099", "\u2019")
                .replace("\u00e2\u0086\u0092", "\u2192")
                .replace("\u00c2\u00a7", "\u00a7")
                .replace("\u00c2\u00b7", "\u00b7");
    }
}
