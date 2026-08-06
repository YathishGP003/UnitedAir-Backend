package com.unitedair.ai.knowledge;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code Field | Value} header block that opens every UnitedAir KB document.
 *
 * <p>Parsing is tolerant by design. A document whose header is malformed still ingests -
 * it simply inherits conservative defaults (audience "All", category "policy-manual") and
 * logs what it could not read. Refusing the document outright would leave a policy
 * unavailable to passengers because of a typo in a metadata table.
 */
@Component
public class KbFrontMatterParser {

    private static final Logger log = LoggerFactory.getLogger(KbFrontMatterParser.class);

    /** Matches "Document Code | KB-AIR-001" style rows anywhere in the header region. */
    private static final Pattern FIELD_ROW =
            Pattern.compile("^\\s*([A-Za-z][A-Za-z ()/&.-]{2,40}?)\\s*\\|\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private static final Pattern FR_ID = Pattern.compile("FR-\\d{3}");

    /** "01-July-2025", "01-Jul-2025", "2025-07-01" and "01/07/2025" all appear in practice. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MMMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH));

    /**
     * Only the header of the document is scanned for fields. Body tables use the same pipe
     * syntax, so scanning the whole document would pick up fare rows as metadata.
     */
    private static final int HEADER_SCAN_CHARS = 4000;

    public KbFrontMatter parse(String text, String fallbackTitle) {
        String header = text.length() > HEADER_SCAN_CHARS ? text.substring(0, HEADER_SCAN_CHARS) : text;

        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = FIELD_ROW.matcher(header);
        while (matcher.find()) {
            fields.put(matcher.group(1).trim().toLowerCase(Locale.ROOT), matcher.group(2).trim());
        }

        String documentCode = firstNonBlank(
                fields.get("document code"),
                deriveCodeFromFilename(fallbackTitle));

        String title = deriveTitle(text, fallbackTitle);
        String versionLabel = firstNonBlank(fields.get("version"), "v1.0");
        String category = firstNonBlank(fields.get("category"), "General");
        Map<String, String> tags = parseTags(fields.get("kb ingestion tags"));
        List<String> audiences = parseAudiences(firstNonBlank(fields.get("audience"), tags.get("audience")));
        String srsCategory = normaliseCategory(category, tags.get("doc_type"), title);

        LocalDate effectiveFrom = parseDate(fields.get("effective date"));
        LocalDate effectiveTo = parseDate(fields.get("expiry date"));

        List<String> servesFrs = parseServesFrs(header);

        if (documentCode == null || documentCode.isBlank()) {
            log.warn("KB document '{}' declares no Document Code; using its filename as the code.",
                    fallbackTitle);
            documentCode = fallbackTitle;
        }

        return new KbFrontMatter(
                documentCode,
                title,
                versionLabel,
                category,
                srsCategory,
                audiences,
                effectiveFrom,
                effectiveTo,
                fields.get("approved by"),
                fields.get("classification"),
                tags,
                servesFrs);
    }

    // ------------------------------------------------------------- helpers ---

    /**
     * Maps a document's own category wording onto the four values SRS 4.3.2 allows.
     * The {@code doc_type} ingestion tag wins when present because it is machine-authored;
     * otherwise the human-written category is keyword-matched.
     */
    private String normaliseCategory(String category, String docTypeTag, String title) {
        String probe = ((docTypeTag == null ? "" : docTypeTag + " ")
                + (category == null ? "" : category + " ")
                + (title == null ? "" : title)).toLowerCase(Locale.ROOT);

        if (probe.contains("regulat") || probe.contains("compliance") || probe.contains("dgca")
                || probe.contains("iata") || probe.contains("circular")) {
            return "regulatory-circular";
        }
        if (probe.contains("fare") || probe.contains("pricing") || probe.contains("refund")
                || probe.contains("cancellation") || probe.contains("tariff")) {
            return "fare-rule";
        }
        if (probe.contains("sop") || probe.contains("procedure") || probe.contains("operations")
                || probe.contains("handling") || probe.contains("check-in") || probe.contains("checkin")
                || probe.contains("boarding") || probe.contains("dispute") || probe.contains("support")) {
            return "sop";
        }
        return "policy-manual";
    }

    private List<String> parseAudiences(String raw) {
        List<String> audiences = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return List.of("All");
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("all")) {
            audiences.add("All");
        }
        if (lower.contains("passenger")) {
            audiences.add("Passenger");
        }
        if (lower.contains("staff") || lower.contains("airline staff")) {
            audiences.add("Airline Staff");
        }
        if (lower.contains("admin")) {
            audiences.add("Admin");
        }
        return audiences.isEmpty() ? List.of("All") : audiences;
    }

    /** "doc_type=booking_policy, audience=passenger, version=v1.0" */
    private Map<String, String> parseTags(String raw) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return tags;
        }
        for (String pair : raw.split("[,;]")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                tags.put(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                        pair.substring(eq + 1).trim());
            }
        }
        return tags;
    }

    private List<String> parseServesFrs(String header) {
        List<String> frs = new ArrayList<>();
        Matcher m = FR_ID.matcher(header);
        while (m.find()) {
            String id = m.group();
            if (!frs.contains(id)) {
                frs.add(id);
            }
        }
        return frs;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, format);
            } catch (Exception ignored) {
                // try the next known layout
            }
        }
        log.debug("Unrecognised effective date '{}'; leaving it unset.", raw);
        return null;
    }

    /**
     * The human title is the first substantial line that is not a banner, a running header
     * or the literal words "Knowledge Base Document".
     */
    private String deriveTitle(String text, String fallback) {
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < Math.min(lines.length, 12); i++) {
            String line = lines[i].trim();
            if (!usableTitleLine(line) || line.length() < 8 || line.contains("|")) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("unitedair ai") || lower.contains("knowledge base document")) {
                continue;
            }
            if (lower.startsWith("kb ") && line.length() < 60) {
                continue;
            }
            return line;
        }
        return fallback;
    }

    private static boolean usableTitleLine(String line) {
        return line != null
                && !line.isBlank()
                && !line.matches("^[\\p{Punct}=\\s]+$");
    }

    private String deriveCodeFromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        Matcher m = Pattern.compile("KB[_ -]?(\\d{2})").matcher(filename.toUpperCase(Locale.ROOT));
        return m.find() ? "KB-AIR-" + m.group(1) + "" : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
