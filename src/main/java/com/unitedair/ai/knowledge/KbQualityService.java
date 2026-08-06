package com.unitedair.ai.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.unitedair.ai.identity.Role;
import org.springframework.stereotype.Service;

/**
 * Measures corpus shape and retrieval safety without encoding or changing policy facts.
 */
@Service
public class KbQualityService {

    static final int EXPECTED_DIMENSIONS = 1536;
    private static final int EXPECTED_VECTOR_BYTES = EXPECTED_DIMENSIONS * Float.BYTES;

    private static final List<ProbeDefinition> PROBES = List.of(
            new ProbeDefinition("Passenger cancellation", Role.PASSENGER,
                    "2.1 Cancellation Fee Matrix by Fare Type"),
            new ProbeDefinition("Passenger baggage", Role.PASSENGER,
                    "2.1 Cabin Baggage Allowance by Travel Class"),
            new ProbeDefinition("Passenger check-in", Role.PASSENGER,
                    "2.1 Check-In Opening and Closing Times"),
            new ProbeDefinition("Staff disruption", Role.AIRLINE_STAFF,
                    "5.2 Disruption Management Protocols"),
            new ProbeDefinition("Admin governance", Role.ADMIN,
                    "5 Support Case Record Requirements"));

    private final KbQualityRepository repository;
    private final HybridRetriever retriever;

    public KbQualityService(KbQualityRepository repository, HybridRetriever retriever) {
        this.repository = repository;
        this.retriever = retriever;
    }

    public KbDtos.KbQualityReport inspect() {
        List<KbQualityRepository.ChunkQualityRow> chunks = repository.activeChunks();
        List<KbDtos.KbQualityIssue> issues = inspectChunks(chunks);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String type : List.of(
                "EMPTY_CONTENT", "HEADER_ONLY", "METADATA_ONLY", "EXACT_DUPLICATE",
                "MISSING_METADATA", "MISSING_VECTOR", "WRONG_VECTOR_DIMENSION")) {
            counts.put(type, issues.stream().filter(issue -> type.equals(issue.type())).count());
        }
        return new KbDtos.KbQualityReport(
                repository.activeDocumentCount(),
                chunks.size(),
                EXPECTED_DIMENSIONS,
                counts,
                issues,
                runProbes(),
                java.util.Optional.ofNullable(repository.activeEmbeddingProvenance())
                        .orElse(List.of()),
                Instant.now());
    }

    private List<KbDtos.KbQualityIssue> inspectChunks(
            List<KbQualityRepository.ChunkQualityRow> chunks) {
        List<KbDtos.KbQualityIssue> issues = new ArrayList<>();
        Map<String, Long> firstByNormalizedContent = new LinkedHashMap<>();
        for (KbQualityRepository.ChunkQualityRow chunk : chunks) {
            String content = chunk.content() == null ? "" : chunk.content().trim();
            if (content.isEmpty()) {
                add(issues, "EMPTY_CONTENT", chunk, "The passage has no retrievable text.");
            } else if (isMetadataOnly(content)) {
                add(issues, "METADATA_ONLY", chunk,
                        "The passage contains ingestion metadata but no passenger-facing policy prose.");
            } else if (isHeaderOnly(content)) {
                add(issues, "HEADER_ONLY", chunk,
                        "The passage appears to contain only a heading.");
            }

            String normalized = normalize(content);
            if (!normalized.isBlank()) {
                Long first = firstByNormalizedContent.putIfAbsent(normalized, chunk.chunkId());
                if (first != null) {
                    add(issues, "EXACT_DUPLICATE", chunk,
                            "Duplicates normalized content from chunk " + first + ".");
                }
            }

            List<String> missing = new ArrayList<>();
            if (blank(chunk.documentCode())) missing.add("document code");
            if (blank(chunk.documentTitle())) missing.add("document title");
            if (blank(chunk.fileType())) missing.add("file type");
            if (blank(chunk.category())) missing.add("category");
            if (blank(chunk.audience())) missing.add("audience");
            if (chunk.page() < 1) missing.add("citation page");
            if (!missing.isEmpty()) {
                add(issues, "MISSING_METADATA", chunk,
                        "Missing " + String.join(", ", missing) + ".");
            }

            if (chunk.vectorBytes() == null || chunk.vectorBytes() <= 0) {
                add(issues, "MISSING_VECTOR", chunk, "No stored embedding is available.");
            } else if (chunk.vectorBytes() != EXPECTED_VECTOR_BYTES) {
                add(issues, "WRONG_VECTOR_DIMENSION", chunk,
                        "Stored vector uses " + (chunk.vectorBytes() / Float.BYTES)
                                + " dimensions; expected " + EXPECTED_DIMENSIONS + ".");
            }
        }
        return List.copyOf(issues);
    }

    private List<KbDtos.KbRetrievalProbe> runProbes() {
        List<KbDtos.KbRetrievalProbe> results = new ArrayList<>();
        for (ProbeDefinition probe : PROBES) {
            RetrievalDtos.Filter filter = RetrievalDtos.Filter.forRole(probe.role());
            RetrievalDtos.Result result;
            try {
                result = retriever.retrieve(probe.query(), filter, RetrievalDtos.Lane.FAST, 1);
            } catch (RuntimeException unavailable) {
                results.add(new KbDtos.KbRetrievalProbe(
                        probe.name(), probe.role().name(), probe.query(), 0,
                        List.of(), false, 0));
                continue;
            }
            Set<String> codes = new LinkedHashSet<>();
            boolean violation = false;
            for (RetrievalDtos.Ranked ranked : result.evidence()) {
                RetrievalDtos.Chunk chunk = ranked.chunk();
                codes.add(chunk.documentCode());
                violation |= !audienceAllowed(chunk.audience(), filter.audiences());
            }
            results.add(new KbDtos.KbRetrievalProbe(
                    probe.name(), probe.role().name(), probe.query(),
                    result.topSimilarity(), List.copyOf(codes), violation,
                    result.evidence().size()));
        }
        return List.copyOf(results);
    }

    private static boolean audienceAllowed(String audience, List<String> readable) {
        if (audience == null || audience.isBlank()) return false;
        for (String candidate : audience.split(",")) {
            if (readable.stream().anyMatch(value -> value.equalsIgnoreCase(candidate.trim()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHeaderOnly(String content) {
        String plain = content.replaceFirst("^#{1,6}\\s*", "").trim();
        return content.lines().count() <= 2
                && plain.length() < 100
                && !plain.matches(".*[.!?].*");
    }

    private static boolean isMetadataOnly(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        long keys = List.of(
                "doc_type=", "audience=", "version=", "review frequency",
                "ingestion tags", "serves_frs=").stream().filter(lower::contains).count();
        return keys >= 1 && !content.matches("(?s).*[.!?].*");
    }

    private static String normalize(String content) {
        return content.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void add(
            List<KbDtos.KbQualityIssue> issues,
            String type,
            KbQualityRepository.ChunkQualityRow chunk,
            String detail) {
        issues.add(new KbDtos.KbQualityIssue(
                type, chunk.chunkId(), chunk.versionId(), chunk.documentCode(), detail));
    }

    private record ProbeDefinition(String name, Role role, String query) { }
}
