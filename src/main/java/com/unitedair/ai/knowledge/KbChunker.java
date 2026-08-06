package com.unitedair.ai.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.stereotype.Component;

/**
 * Splits an extracted document into retrievable, citable chunks.
 *
 * <h2>Why section-aware rather than fixed-size</h2>
 * SRS 4.1.2 requires every answer to cite the section and page it came from. A fixed-size
 * splitter cannot produce that: it cuts mid-table and mid-clause, so a chunk about
 * cancellation fees might carry the heading of the rescheduling section above it. These
 * documents are rigidly numbered ("2.1 Cancellation Fee Matrix by Fare Type"), so the
 * chunker follows that structure and every chunk inherits the heading it actually sits
 * under.
 *
 * <p>Sections longer than the target size are windowed with overlap, and each window keeps
 * the section heading so a fee table split across two chunks still cites correctly. Very
 * short sections are merged forward, because a chunk containing only "1.2 Applicability"
 * retrieves noise.
 */
@Component
public class KbChunker {

    /** "2.1 Cancellation Fee Matrix" / "4. Rescheduling" - number, then a title. */
    private static final Pattern HEADING =
            Pattern.compile("^\\s{0,6}(\\d{1,2}(?:\\.\\d{1,2}){0,3})\\.?\\s+([A-Z0-9][^\\n]{2,110})$");

    /** Tika emits a form feed at PDF page boundaries; TXT and DOCX have none. */
    private static final char PAGE_BREAK = '\f';

    /** Characters of body text that stand in for one page when no page breaks exist. */
    private static final int CHARS_PER_ESTIMATED_PAGE = 3000;

    private final UnitedAirProperties.Ingestion config;

    public KbChunker(UnitedAirProperties properties) {
        this.config = properties.getIngestion();
    }

    public List<ChunkDraft> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Section> sections = splitIntoSections(text);
        List<ChunkDraft> chunks = new ArrayList<>();
        int index = 0;

        for (Section section : sections) {
            String body = section.body().strip();
            if (body.isEmpty()) {
                continue;
            }

            for (String window : windows(body)) {
                String content = section.heading().isBlank()
                        ? window
                        : section.heading() + "\n" + window;

                chunks.add(new ChunkDraft(
                        index++,
                        section.heading().isBlank() ? "Introduction" : section.heading(),
                        section.number(),
                        section.page(),
                        content.strip(),
                        estimateTokens(content)));
            }
        }

        return chunks;
    }

    // ------------------------------------------------------------ sectioning ---

    private List<Section> splitIntoSections(String text) {
        String[] lines = text.split("\\r?\\n");
        List<Section> sections = new ArrayList<>();

        StringBuilder buffer = new StringBuilder();
        String currentHeading = "";
        String currentNumber = "";
        int currentPage = 1;
        int page = 1;
        int charsOnPage = 0;
        boolean sawExplicitBreak = text.indexOf(PAGE_BREAK) >= 0;

        for (String rawLine : lines) {
            if (sawExplicitBreak && rawLine.indexOf(PAGE_BREAK) >= 0) {
                page++;
                charsOnPage = 0;
            } else if (!sawExplicitBreak) {
                charsOnPage += rawLine.length() + 1;
                if (charsOnPage >= CHARS_PER_ESTIMATED_PAGE) {
                    page++;
                    charsOnPage = 0;
                }
            }

            String line = rawLine.replace(PAGE_BREAK, ' ');
            Matcher heading = HEADING.matcher(line);

            if (heading.matches() && looksLikeHeading(line)) {
                if (buffer.length() > 0) {
                    sections.add(new Section(currentHeading, currentNumber, currentPage, buffer.toString()));
                    buffer.setLength(0);
                }
                currentNumber = heading.group(1);
                currentHeading = (currentNumber + " " + heading.group(2).trim()).trim();
                currentPage = page;
            } else {
                buffer.append(line).append('\n');
            }
        }

        if (buffer.length() > 0) {
            sections.add(new Section(currentHeading, currentNumber, currentPage, buffer.toString()));
        }

        return mergeTinySections(sections);
    }

    /**
     * A numbered line is only a heading if it is short and is not a data row. These
     * documents contain pipe-delimited tables whose first cell is often numeric, and
     * "2,000 | Balance after fee | Yes" must not become a section boundary.
     */
    private boolean looksLikeHeading(String line) {
        String trimmed = line.trim();
        return trimmed.length() <= 120
                && !trimmed.contains("|")
                && !trimmed.endsWith(".")
                && !trimmed.endsWith(",");
    }

    /** Folds sections below the minimum size into the following one. */
    private List<Section> mergeTinySections(List<Section> sections) {
        List<Section> merged = new ArrayList<>();
        StringBuilder carried = new StringBuilder();
        String carriedHeading = null;
        String carriedNumber = null;
        Integer carriedPage = null;

        for (Section section : sections) {
            String body = section.body().strip();

            // A structural parent such as "5 Upgrades" often has no body before
            // "5.1 Upgrade Pathways". It supplies hierarchy, not evidence. Carrying that
            // empty parent forward used to replace the child's precise section label.
            if (body.isEmpty()) {
                continue;
            }

            if (body.length() < config.getChunkMinChars()) {
                if (carriedHeading == null) {
                    carriedHeading = section.heading();
                    carriedNumber = section.number();
                    carriedPage = section.page();
                }
                carried.append(section.heading()).append('\n').append(body).append('\n');
                continue;
            }

            if (carriedHeading != null) {
                merged.add(new Section(carriedHeading, carriedNumber, carriedPage,
                        carried + body));
                carried.setLength(0);
                carriedHeading = null;
                carriedNumber = null;
                carriedPage = null;
            } else {
                merged.add(new Section(section.heading(), section.number(), section.page(), body));
            }
        }

        // Trailing fragment that never found a substantial section to attach to
        if (carriedHeading != null && carried.length() > 0) {
            merged.add(new Section(carriedHeading, carriedNumber, carriedPage, carried.toString()));
        }
        return merged;
    }

    // -------------------------------------------------------------- windowing ---

    private List<String> windows(String body) {
        int target = config.getChunkTargetChars();
        int overlap = config.getChunkOverlapChars();

        if (body.length() <= target) {
            return List.of(body);
        }

        List<String> windows = new ArrayList<>();
        int start = 0;

        while (start < body.length()) {
            int end = Math.min(body.length(), start + target);

            // Prefer to cut at a line boundary so table rows stay intact.
            if (end < body.length()) {
                int newline = body.lastIndexOf('\n', end);
                if (newline > start + (target / 2)) {
                    end = newline;
                }
            }

            windows.add(body.substring(start, end).strip());

            if (end >= body.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }

        return windows;
    }

    /** Rough token estimate; used only for reporting, never for truncation decisions. */
    private int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    private record Section(String heading, String number, int page, String body) { }

    /** A chunk before it has been embedded or assigned a database identity. */
    public record ChunkDraft(
            int index,
            String section,
            String sectionNumber,
            int page,
            String content,
            int tokenEstimate) { }
}
