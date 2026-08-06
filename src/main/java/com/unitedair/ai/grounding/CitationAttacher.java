package com.unitedair.ai.grounding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.shared.Json;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.stereotype.Component;

/**
 * Deterministically attaches an offered evidence handle to each supported factual sentence.
 *
 * <p>The language model is still asked to cite its claims, but correctness cannot depend on
 * it remembering punctuation syntax. This component only attaches a handle when weighted
 * token overlap clears a conservative threshold. Unsupported claims remain uncited so the
 * evaluator can reject them; a plausible-looking citation is never manufactured.
 */
@Component
public class CitationAttacher {

    private static final Pattern HANDLE = Pattern.compile("\\[(?:E|T)\\d{1,2}]");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern MARKDOWN_PREFIX =
            Pattern.compile("^\\s*(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)");

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "been", "by", "can", "for",
            "from", "has", "have", "in", "is", "it", "may", "of", "on", "or", "that",
            "the", "this", "to", "was", "were", "will", "with", "your", "you");

    private static final List<String> CONNECTIVE_PREFIXES = List.of(
            "here is", "here are", "in short", "to summarise", "to summarize",
            "based on the available information", "thanks for", "thank you");

    private static final double MIN_SCORE = 0.34;
    private static final double MIN_SHARED_WEIGHT = 2.0;

    public AttachmentResult attach(String answer,
                                   List<RetrievalDtos.Ranked> evidence,
                                   List<ToolDtos.ToolOutcome> tools) {
        if (answer == null || answer.isBlank()) {
            return new AttachmentResult(answer, 0, List.of());
        }

        List<Candidate> candidates = candidates(evidence, tools);
        Map<String, Double> idf = inverseDocumentFrequency(candidates);
        List<SentenceSpan> sentences = sentences(answer);
        List<String> unmatched = new ArrayList<>();
        List<Insertion> insertions = new ArrayList<>();
        int matched = 0;

        for (SentenceSpan sentence : sentences) {
            if (!isFactual(sentence.text())) {
                continue;
            }
            String authoritativeTool = authoritativeBookingAllowanceHandle(
                    sentence.text(), tools);
            if (authoritativeTool != null
                    && !hasToolCitation(answer, sentence)) {
                insertions.add(new Insertion(
                        afterFollowingCitations(answer, sentence.end()),
                        " [" + authoritativeTool + "]"));
                matched++;
                continue;
            }
            if (hasCitation(answer, sentence)) {
                matched++;
                continue;
            }

            Match best = bestMatch(sentence.text(), candidates, idf);
            Match collective = collectiveMatch(sentence.text(), candidates, idf);
            if (collective != null
                    && (best == null || collective.score() > best.score() + 0.08)) {
                best = collective;
            }
            if (best != null) {
                insertions.add(new Insertion(sentence.end(), " " + best.handles()));
                matched++;
            } else {
                unmatched.add(sentence.text().trim());
            }
        }

        StringBuilder attached = new StringBuilder(answer);
        for (int index = insertions.size() - 1; index >= 0; index--) {
            Insertion insertion = insertions.get(index);
            attached.insert(insertion.index(), insertion.text());
        }
        return new AttachmentResult(attached.toString(), matched, List.copyOf(unmatched));
    }

    private static String authoritativeBookingAllowanceHandle(
            String sentence,
            List<ToolDtos.ToolOutcome> tools) {
        if (sentence == null || tools == null) {
            return null;
        }
        String lower = sentence.toLowerCase(Locale.ROOT);
        if (!lower.contains("baggage") && !lower.contains("checked")) {
            return null;
        }
        int toolIndex = 0;
        for (ToolDtos.ToolOutcome tool : tools) {
            if (!tool.success()) {
                continue;
            }
            toolIndex++;
            if (tool.data() instanceof ToolDtos.BookingView booking
                    && Pattern.compile(
                            "(?i)\\b" + booking.checkedBaggageKg()
                                    + "\\s*kg\\b")
                            .matcher(sentence)
                            .find()) {
                return "T" + toolIndex;
            }
        }
        return null;
    }

    private static boolean hasToolCitation(
            String answer,
            SentenceSpan sentence) {
        int end = Math.min(answer.length(), sentence.end() + 40);
        return Pattern.compile("\\[T\\d{1,2}]")
                .matcher(answer.substring(sentence.start(), end))
                .find();
    }

    private static int afterFollowingCitations(String answer, int sentenceEnd) {
        Matcher citations = Pattern.compile(
                "(?:\\s*\\[(?:E|T)\\d{1,2}])+")
                .matcher(answer.substring(sentenceEnd));
        return citations.lookingAt()
                ? sentenceEnd + citations.end()
                : sentenceEnd;
    }

    /**
     * Returns factual sentences that do not carry an evidence or tool handle.
     * The strict evaluator uses the same sentence semantics as attachment.
     */
    public List<String> uncitedFactualSentences(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<String> uncited = new ArrayList<>();
        for (SentenceSpan sentence : sentences(answer)) {
            if (isFactual(sentence.text()) && !hasCitation(answer, sentence)) {
                uncited.add(sentence.text().trim());
            }
        }
        return List.copyOf(uncited);
    }

    private static List<Candidate> candidates(List<RetrievalDtos.Ranked> evidence,
                                              List<ToolDtos.ToolOutcome> tools) {
        List<Candidate> candidates = new ArrayList<>();
        if (evidence != null) {
            for (RetrievalDtos.Ranked ranked : evidence) {
                String content = ranked.chunk().content();
                candidates.add(new Candidate(
                        CitationBuilder.handleFor(ranked.rank()),
                        content == null ? "" : content,
                        tokens(content)));
            }
        }
        if (tools != null) {
            int toolIndex = 1;
            for (ToolDtos.ToolOutcome tool : tools) {
                if (!tool.success()) {
                    continue;
                }
                String corpus = (tool.summary() == null ? "" : tool.summary())
                        + " " + (tool.data() == null ? "" : Json.write(tool.data()));
                candidates.add(new Candidate("T" + toolIndex++, corpus, tokens(corpus)));
            }
        }
        return candidates;
    }

    private static Map<String, Double> inverseDocumentFrequency(List<Candidate> candidates) {
        Map<String, Integer> documentsContaining = new HashMap<>();
        for (Candidate candidate : candidates) {
            for (String token : candidate.tokens()) {
                documentsContaining.merge(token, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new LinkedHashMap<>();
        int count = Math.max(1, candidates.size());
        documentsContaining.forEach((token, frequency) ->
                idf.put(token, 1.0 + Math.log((count + 1.0) / (frequency + 1.0))));
        return idf;
    }

    private static Match bestMatch(String sentence,
                                   List<Candidate> candidates,
                                   Map<String, Double> idf) {
        Set<String> sentenceTokens = tokens(sentence);
        if (sentenceTokens.isEmpty()) {
            return null;
        }

        double sentenceWeight = sentenceTokens.stream()
                .mapToDouble(token -> idf.getOrDefault(token, 1.0))
                .sum();
        Match best = null;

        for (Candidate candidate : candidates) {
            double sharedWeight = sentenceTokens.stream()
                    .filter(candidate.tokens()::contains)
                    .mapToDouble(token -> idf.getOrDefault(token, 1.0))
                    .sum();
            double score = sentenceWeight == 0 ? 0 : sharedWeight / sentenceWeight;

            Set<String> sentenceNumbers = numbers(sentenceTokens);
            if (!sentenceNumbers.isEmpty() && candidate.tokens().containsAll(sentenceNumbers)) {
                score += 0.15;
            }
            if (sharedWeight >= MIN_SHARED_WEIGHT
                    && score >= MIN_SCORE
                    && (best == null || score > best.score())) {
                best = new Match("[" + candidate.handle() + "]", score);
            }
        }
        return best;
    }

    /**
     * Finds support that is intentionally spread across several evidence chunks.
     *
     * <p>This is common for multi-part answers: one concluding sentence may mention a
     * wheelchair service, an unaccompanied-minor service and pet carriage, each published
     * in a different section. A single fabricated citation would be wrong, while rejecting
     * the otherwise grounded answer is needlessly brittle. The greedy union remains
     * conservative: every selected source must add vocabulary and the combined overlap
     * must clear the same support floor as a single-source match.
     */
    private static Match collectiveMatch(
            String sentence,
            List<Candidate> candidates,
            Map<String, Double> idf) {
        Set<String> sentenceTokens = tokens(sentence);
        if (sentenceTokens.isEmpty() || candidates.size() < 2) {
            return null;
        }
        double sentenceWeight = sentenceTokens.stream()
                .mapToDouble(token -> idf.getOrDefault(token, 1.0))
                .sum();
        Set<String> covered = new HashSet<>();
        List<Candidate> selected = new ArrayList<>();

        while (selected.size() < 4) {
            Candidate best = null;
            double bestAdded = 0;
            for (Candidate candidate : candidates) {
                if (selected.contains(candidate)) {
                    continue;
                }
                double added = sentenceTokens.stream()
                        .filter(candidate.tokens()::contains)
                        .filter(token -> !covered.contains(token))
                        .mapToDouble(token -> idf.getOrDefault(token, 1.0))
                        .sum();
                if (added > bestAdded) {
                    best = candidate;
                    bestAdded = added;
                }
            }
            if (best == null || bestAdded < 1.0) {
                break;
            }
            selected.add(best);
            covered.addAll(best.tokens());
        }

        double sharedWeight = sentenceTokens.stream()
                .filter(covered::contains)
                .mapToDouble(token -> idf.getOrDefault(token, 1.0))
                .sum();
        double score = sentenceWeight == 0 ? 0 : sharedWeight / sentenceWeight;
        if (selected.size() < 2
                || sharedWeight < MIN_SHARED_WEIGHT
                || score < MIN_SCORE) {
            return null;
        }
        String handles = selected.stream()
                .map(candidate -> "[" + candidate.handle() + "]")
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        return new Match(handles, score);
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT).replace(",", ""));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 1 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Set<String> numbers(Set<String> tokens) {
        Set<String> numbers = new HashSet<>();
        for (String token : tokens) {
            if (token.chars().allMatch(Character::isDigit)) {
                numbers.add(token);
            }
        }
        return numbers;
    }

    private static boolean isFactual(String rawSentence) {
        String sentence = MARKDOWN_PREFIX.matcher(rawSentence).replaceFirst("").trim();
        String lower = sentence.toLowerCase(Locale.ROOT);
        if (sentence.isBlank() || sentence.endsWith("?") || HANDLE.matcher(sentence).matches()) {
            return false;
        }
        // Markdown section labels organise supported claims; they are not themselves
        // factual claims. Long bold labels (especially ones containing fare codes or
        // section numbers) previously crossed the word-count heuristic and caused an
        // otherwise fully cited multipart answer to be escalated.
        if ((sentence.startsWith("**") && sentence.endsWith("**"))
                || sentence.endsWith(":")
                || sentence.endsWith(":**")) {
            return false;
        }
        for (String prefix : CONNECTIVE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return false;
            }
        }
        int contentWords = tokens(sentence).size();
        if (contentWords < 4 && !sentence.matches(".*\\d.*")) {
            return false;
        }
        // A line without terminal punctuation is commonly a Markdown section heading.
        return sentence.matches(".*[.!?]$") || sentence.matches(".*\\d.*") || contentWords >= 7;
    }

    private static boolean hasCitation(String answer, SentenceSpan sentence) {
        Matcher embedded = HANDLE.matcher(sentence.text());
        while (embedded.find()) {
            String beforeHandle = MARKDOWN_PREFIX.matcher(
                    sentence.text().substring(0, embedded.start())).replaceFirst("").trim();
            // A trailing marker from the previous sentence is captured at the start of
            // this regex span. It supports the previous sentence, not this one.
            if (beforeHandle.matches(".*[\\p{L}\\p{N}].*")) {
                return true;
            }
        }
        int lookaheadEnd = Math.min(answer.length(), sentence.end() + 8);
        String following = answer.substring(sentence.end(), lookaheadEnd).trim();
        return HANDLE.matcher(following).lookingAt();
    }

    private static List<SentenceSpan> sentences(String answer) {
        List<SentenceSpan> sentences = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < answer.length(); index++) {
            char current = answer.charAt(index);
            boolean lineEnd = current == '\r' || current == '\n';
            boolean decimalPoint = current == '.'
                    && index > 0
                    && index + 1 < answer.length()
                    && Character.isDigit(answer.charAt(index - 1))
                    && Character.isDigit(answer.charAt(index + 1));
            boolean sentenceEnd = !decimalPoint
                    && (current == '.' || current == '!' || current == '?');
            if (lineEnd || sentenceEnd) {
                int end = sentenceEnd ? index + 1 : index;
                addSentence(answer, start, end, sentences);
                start = index + 1;
            }
        }
        addSentence(answer, start, answer.length(), sentences);
        return sentences;
    }

    private static void addSentence(String answer,
                                    int start,
                                    int end,
                                    List<SentenceSpan> sentences) {
        while (start < end && Character.isWhitespace(answer.charAt(start))) {
            start++;
        }
        if (start < end) {
            sentences.add(new SentenceSpan(start, end, answer.substring(start, end)));
        }
    }

    public record AttachmentResult(
            String answer,
            int matchedSentences,
            List<String> unmatchedFactualSentences) { }

    private record Candidate(String handle, String corpus, Set<String> tokens) { }
    private record Match(String handles, double score) { }
    private record SentenceSpan(int start, int end, String text) { }
    private record Insertion(int index, String text) { }
}
