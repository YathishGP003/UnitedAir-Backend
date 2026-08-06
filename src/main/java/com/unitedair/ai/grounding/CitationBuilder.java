package com.unitedair.ai.grounding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.unitedair.ai.knowledge.HybridRetriever;
import com.unitedair.ai.knowledge.RetrievalDtos;
import org.springframework.stereotype.Component;

/**
 * Turns retrieved evidence into the citations an answer must carry (SRS 4.1.2), and reports
 * how much of the answer those citations actually cover.
 */
@Component
public class CitationBuilder {

    /** Matches Knowledge Base and live-tool handles offered to the model. */
    private static final Pattern HANDLE_REF = Pattern.compile("\\[((?:E|T)\\d{1,2})]");

    /** Sentence splitter used for coverage measurement. */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+");

    public List<GroundingDtos.Citation> fromEvidence(List<RetrievalDtos.Ranked> evidence) {
        List<GroundingDtos.Citation> citations = new ArrayList<>();
        for (RetrievalDtos.Ranked ranked : evidence) {
            RetrievalDtos.Chunk chunk = ranked.chunk();
            citations.add(GroundingDtos.Citation.fromKb(
                    handleFor(ranked.rank()),
                    chunk.documentCode(),
                    chunk.documentTitle(),
                    chunk.section(),
                    chunk.page(),
                    chunk.category(),
                    round(HybridRetriever.relevance(chunk)),
                    excerpt(chunk.content())));
        }
        return citations;
    }

    public static String handleFor(int rank) {
        return "E" + rank;
    }

    /** The handles the answer text actually references. */
    public Set<String> citedHandles(String answer) {
        Set<String> handles = new LinkedHashSet<>();
        if (answer == null) {
            return handles;
        }
        Matcher matcher = HANDLE_REF.matcher(answer);
        while (matcher.find()) {
            handles.add(matcher.group(1));
        }
        return handles;
    }

    /**
     * Keeps only the citations the answer referenced, so the evidence panel shows what was
     * used rather than everything that was retrieved. If the answer cited nothing, all
     * offered citations are returned - the validator will fail the answer anyway, and
     * showing the evidence makes the failure diagnosable.
     */
    public List<GroundingDtos.Citation> retainCited(List<GroundingDtos.Citation> offered, String answer) {
        Set<String> cited = citedHandles(answer);
        if (cited.isEmpty()) {
            return offered;
        }
        List<GroundingDtos.Citation> used = offered.stream()
                .filter(c -> cited.contains(c.handle()))
                .toList();
        return used.isEmpty() ? offered : used;
    }

    /**
     * Moves a citation that trails a sentence inside it: {@code "…fee. [E1]"} becomes
     * {@code "…fee [E1]."}
     *
     * <p>Without this, splitting on sentence boundaries attaches every marker to the
     * <em>following</em> sentence, so the sentence the citation actually supports is
     * counted as uncited and the one after it is credited twice. Both citation styles are
     * common - the model writes either depending on phrasing - so coverage has to
     * normalise before it measures rather than assume one of them.
     */
    private static String attachTrailingCitations(String answer) {
        return answer.replaceAll("([.!?])\\s*(\\[[^\\]\\n]{1,80}\\])", " $2$1");
    }

    /**
     * Proportion of substantive sentences carrying a citation.
     *
     * <p>Short connective sentences ("Here are the details.") are excluded, because
     * requiring a citation on them would push the model to litter the answer with markers
     * and would make coverage meaningless as a quality signal.
     */
    public double coverage(String answer) {
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }
        String[] sentences = SENTENCE.split(attachTrailingCitations(answer).replace("\n", " "));
        int substantive = 0;
        int cited = 0;

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.length() < 40) {
                continue;
            }
            substantive++;
            if (HANDLE_REF.matcher(trimmed).find()) {
                cited++;
            }
        }

        if (substantive == 0) {
            // A single short answer such as "Yes, the fare is refundable. [E1]"
            return HANDLE_REF.matcher(answer).find() ? 1.0 : 0.0;
        }
        return (double) cited / substantive;
    }

    /**
     * Replaces bare [E1] markers with a reader-friendly form once validation has passed,
     * e.g. {@code [KB-AIR-004 2.1 Cancellation Fee Matrix]}. The UI links these back to the
     * evidence panel; a passenger should not have to decode an internal handle.
     */
    public String renderCitations(String answer, List<GroundingDtos.Citation> citations) {
        if (answer == null || citations.isEmpty()) {
            return answer;
        }
        String rendered = answer;
        for (GroundingDtos.Citation citation : citations) {
            String label = citation.isToolCitation()
                    ? citation.toolName()
                    : citation.documentCode()
                            + (citation.section() == null || citation.section().isBlank()
                                    ? "" : " " + citation.section());
            rendered = rendered.replace("[" + citation.handle() + "]", "[" + label + "]");
        }
        return rendered;
    }

    private static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String collapsed = content.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 500 ? collapsed : collapsed.substring(0, 500) + "...";
    }

    private static Double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
