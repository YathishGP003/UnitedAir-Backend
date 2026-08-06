package com.unitedair.ai.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.unitedair.ai.llm.EmbeddingGateway;
import com.unitedair.ai.shared.TraceContext;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Retrieval for the KB path: two lanes in parallel, fused, reranked, then gated.
 *
 * <p>This is the <b>Parallelization pattern</b> of SRS 2.2. The semantic and lexical lanes
 * are independent and both IO bound, so running them concurrently costs one round trip
 * instead of two.
 *
 * <h2>How the SRS 4.1.1 threshold is applied</h2>
 * The threshold is stated as "retrieval similarity must be &gt;= 0.5", so the gate needs a
 * measure that is absolute rather than relative to the result set - normalising within the
 * results would make the top hit score 1.0 every time and the gate would never fire.
 *
 * <p>Each candidate therefore gets a <i>relevance</i> in [0,1]:
 * <pre>
 *   relevance = max( cosineSimilarity , bm25 / (bm25 + 3) )
 * </pre>
 * Cosine is already absolute. The lexical term is an absolute, monotonic squash of the
 * full-text score, chosen so a BM25 of 3 - a solidly matching chunk in this corpus - maps
 * to exactly 0.5. Taking the maximum means either lane can independently satisfy the gate,
 * which matters because exact-token queries ("DGCA CAR-7 duty time") are strong lexical
 * matches and mediocre semantic ones.
 *
 * <p>Fusion for <i>ranking</i> is separate from the gate: Reciprocal Rank Fusion combines
 * the two orderings, and RRF is deliberately rank-based because cosine and BM25 are not on
 * a comparable scale and averaging them directly would let whichever lane happens to
 * produce larger numbers dominate.
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /** BM25 value that maps to a relevance of exactly 0.5. See the class comment. */
    private static final double LEXICAL_HALF_SATURATION = 3.0;

    private final KbRepository repository;
    private final EmbeddingGateway embeddings;
    private final UnitedAirProperties properties;

    public HybridRetriever(KbRepository repository,
                           EmbeddingGateway embeddings,
                           UnitedAirProperties properties) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    public RetrievalDtos.Result retrieve(String query,
                                         RetrievalDtos.Filter filter,
                                         RetrievalDtos.Lane lane,
                                         int attempt) {
        long started = System.nanoTime();
        UnitedAirProperties.LaneConfig config = lane == RetrievalDtos.Lane.DEEP
                ? properties.getRag().getDeep()
                : properties.getRag().getFast();

        String traceId = TraceContext.traceId();
        String sessionUuid = TraceContext.sessionUuid();

        CompletableFuture<List<RetrievalDtos.Chunk>> lexicalLane =
                properties.getRag().isLexicalEnabled()
                        ? CompletableFuture.supplyAsync(
                                () -> withTrace(traceId, sessionUuid,
                                        () -> repository.lexicalSearch(
                                                query, filter, config.getLexicalTopK())))
                        : CompletableFuture.completedFuture(List.of());

        // Query and corpus must occupy the same vector space. Chat can remain live even
        // when the governed corpus uses deterministic embeddings.
        String corpusSource = repository.activeEmbeddingSource();
        EmbeddingGateway.QueryEmbedding embedding =
                embeddings.embedQuery(query, corpusSource);
        float[] queryVector = embedding.vector();

        // The vector lane is skipped when the query embedding is not comparable with the
        // corpus; see QueryEmbedding for why running it anyway is worse than not running it.
        CompletableFuture<List<RetrievalDtos.Chunk>> vectorLane = embedding.comparable()
                ? CompletableFuture.supplyAsync(
                        () -> withTrace(traceId, sessionUuid,
                                () -> repository.vectorSearch(
                                        queryVector, query, filter, config.getVectorTopK())))
                : CompletableFuture.completedFuture(List.of());

        List<RetrievalDtos.Chunk> vectorHits;
        List<RetrievalDtos.Chunk> lexicalHits;
        try {
            vectorHits = vectorLane.join();
            lexicalHits = lexicalLane.join();
        } catch (Exception e) {
            log.error("Retrieval lane failed: {}", e.toString());
            throw e;
        }

        // KbRepository's vector query also selects a full-text score for diagnostics and
        // for true hybrid mode. Do not let that diagnostic score influence relevance,
        // reranking or the SRS threshold when lexical retrieval is explicitly disabled.
        if (!properties.getRag().isLexicalEnabled()) {
            vectorHits = vectorHits.stream()
                    .map(chunk -> chunk.withScores(chunk.vectorScore(), null))
                    .toList();
        }

        if (!embedding.comparable()) {
            // Both queries select a cosine score. With an incomparable query vector that
            // number is noise, and because relevance takes the maximum of the two lanes it
            // could push an irrelevant chunk over the SRS 4.1.1 threshold. Discard it and
            // let the lexical score stand alone.
            lexicalHits = lexicalHits.stream().map(c -> c.withScores(null, c.lexicalScore())).toList();
        }

        List<Fused> fused = fuse(vectorHits, lexicalHits);
        List<Fused> reranked = rerank(query, fused);

        // --- Grounding gate: SRS 4.1.1 ------------------------------------------------
        double threshold = properties.getRag().getSimilarityThreshold();
        List<Fused> admitted = reranked.stream()
                .filter(f -> relevance(f.chunk()) >= threshold)
                .limit(config.getRerankKeep())
                .toList();

        List<RetrievalDtos.Ranked> evidence = new ArrayList<>();
        for (int i = 0; i < admitted.size(); i++) {
            Fused f = admitted.get(i);
            evidence.add(new RetrievalDtos.Ranked(f.chunk(), f.rrfScore(), f.rerankScore(), i + 1));
        }

        double topRelevance = admitted.isEmpty() ? 0.0 : relevance(admitted.get(0).chunk());
        double confidence = confidence(
                admitted,
                config.getGenerateFrom(),
                topRelevance,
                filter.documentCodes() != null && !filter.documentCodes().isEmpty());
        long durationMs = (System.nanoTime() - started) / 1_000_000;

        log.debug("Retrieval[{} attempt {}] vector={} lexical={} fused={} admitted={} top={} conf={}",
                lane, attempt, vectorHits.size(), lexicalHits.size(), fused.size(),
                evidence.size(), String.format("%.3f", topRelevance), String.format("%.3f", confidence));

        return new RetrievalDtos.Result(
                evidence, lane, attempt,
                vectorHits.size(), lexicalHits.size(), fused.size(),
                topRelevance, confidence, durationMs, filter);
    }

    /**
     * Adds one threshold-clearing lexical chunk for each focused coverage query.
     *
     * <p>The normal hybrid search remains authoritative for ranking. This method is used
     * only on the bounded repair pass after the completeness gate has shown that a
     * multipart answer is missing a named category. Exact policy section vocabulary is
     * especially reliable lexically, and avoiding a new embedding call for each category
     * keeps the repair fast and independent of hosted-model quotas.
     */
    public RetrievalDtos.Result augmentLexically(
            RetrievalDtos.Result base,
            List<String> coverageQueries) {
        if (base == null || coverageQueries == null || coverageQueries.isEmpty()) {
            return base;
        }
        long started = System.nanoTime();
        UnitedAirProperties.LaneConfig config = base.lane() == RetrievalDtos.Lane.DEEP
                ? properties.getRag().getDeep()
                : properties.getRag().getFast();
        double threshold = properties.getRag().getSimilarityThreshold();

        Map<Long, RetrievalDtos.Ranked> byId = new LinkedHashMap<>();
        for (RetrievalDtos.Ranked ranked : base.evidence()) {
            byId.put(ranked.chunk().id(), ranked);
        }
        int lexicalHits = base.lexicalHits();
        int fusedHits = base.fusedHits();
        double topSimilarity = base.topSimilarity();

        for (String coverageQuery : coverageQueries) {
            List<RetrievalDtos.Chunk> lexical =
                    repository.lexicalSearch(
                            coverageQuery, base.filter(), config.getLexicalTopK());
            lexicalHits += lexical.size();
            List<Fused> candidates = rerank(
                    coverageQuery, fuse(List.of(), lexical));
            fusedHits += candidates.size();
            for (Fused candidate : candidates) {
                double relevance = relevance(candidate.chunk());
                if (relevance < threshold || byId.containsKey(candidate.chunk().id())) {
                    continue;
                }
                byId.put(candidate.chunk().id(), new RetrievalDtos.Ranked(
                        candidate.chunk(),
                        candidate.rrfScore(),
                        candidate.rerankScore(),
                        byId.size() + 1));
                topSimilarity = Math.max(topSimilarity, relevance);
                break;
            }
        }

        List<RetrievalDtos.Ranked> rerankedEvidence = new ArrayList<>();
        int rank = 1;
        for (RetrievalDtos.Ranked existing : byId.values()) {
            rerankedEvidence.add(new RetrievalDtos.Ranked(
                    existing.chunk(), existing.fusedScore(),
                    existing.rerankScore(), rank++));
        }
        long durationMs = base.durationMs()
                + (System.nanoTime() - started) / 1_000_000;
        return new RetrievalDtos.Result(
                List.copyOf(rerankedEvidence),
                base.lane(),
                base.attempt(),
                base.vectorHits(),
                lexicalHits,
                fusedHits,
                topSimilarity,
                base.confidence(),
                durationMs,
                base.filter());
    }

    /**
     * Retrieves missing multipart evidence with focused vector queries in parallel.
     *
     * <p>One long embedding tends to represent its dominant topic and under-rank the
     * remaining clauses. Each bounded coverage query targets one requested policy section;
     * all queries run concurrently, and their best grounded chunks are merged with the
     * original evidence. This is retrieval orchestration, not hard-coded answers.
     */
    public RetrievalDtos.Result augmentFocused(
            RetrievalDtos.Result base,
            List<String> coverageQueries) {
        if (base == null || coverageQueries == null || coverageQueries.isEmpty()) {
            return base;
        }
        long started = System.nanoTime();
        List<String> focused = coverageQueries.stream()
                .filter(q -> q != null && !q.isBlank())
                .distinct()
                .limit(16)
                .toList();
        if (focused.isEmpty()) {
            return base;
        }

        String traceId = TraceContext.traceId();
        String sessionUuid = TraceContext.sessionUuid();
        String corpusSource = repository.activeEmbeddingSource();
        List<EmbeddingGateway.QueryEmbedding> queryEmbeddings =
                embeddings.embedQueries(focused, corpusSource);
        List<CompletableFuture<RetrievalDtos.Result>> futures =
                java.util.stream.IntStream.range(0, focused.size())
                        .mapToObj(index -> CompletableFuture.supplyAsync(
                                () -> withTrace(traceId, sessionUuid,
                                        () -> retrieveWithEmbedding(
                                                focused.get(index),
                                                queryEmbeddings.get(index),
                                                base.filter(),
                                                base.lane(),
                                                base.attempt()))))
                        .toList();

        Map<Long, RetrievalDtos.Ranked> byId = new LinkedHashMap<>();
        for (RetrievalDtos.Ranked ranked : base.evidence()) {
            byId.put(ranked.chunk().id(), ranked);
        }

        int vectorHits = base.vectorHits();
        int lexicalHits = base.lexicalHits();
        int fusedHits = base.fusedHits();
        double topSimilarity = base.topSimilarity();
        double confidence = base.confidence();

        for (CompletableFuture<RetrievalDtos.Result> future : futures) {
            RetrievalDtos.Result focusedResult = future.join();
            vectorHits += focusedResult.vectorHits();
            lexicalHits += focusedResult.lexicalHits();
            fusedHits += focusedResult.fusedHits();
            topSimilarity = Math.max(topSimilarity, focusedResult.topSimilarity());
            confidence = Math.max(confidence, focusedResult.confidence());
            int added = 0;
            for (RetrievalDtos.Ranked ranked : focusedResult.evidence()) {
                if (!byId.containsKey(ranked.chunk().id())) {
                    byId.put(ranked.chunk().id(), ranked);
                    added++;
                    if (added == 2) {
                        break;
                    }
                }
            }
        }

        List<RetrievalDtos.Ranked> evidence = new ArrayList<>();
        int rank = 1;
        for (RetrievalDtos.Ranked ranked : byId.values()) {
            evidence.add(new RetrievalDtos.Ranked(
                    ranked.chunk(), ranked.fusedScore(),
                    ranked.rerankScore(), rank++));
        }
        long durationMs = base.durationMs()
                + (System.nanoTime() - started) / 1_000_000;
        return new RetrievalDtos.Result(
                List.copyOf(evidence),
                base.lane(),
                base.attempt(),
                vectorHits,
                lexicalHits,
                fusedHits,
                topSimilarity,
                confidence,
                durationMs,
                base.filter());
    }

    /**
     * Runs the ordinary vector/lexical ranking path with a query vector supplied by the
     * caller. Focused multipart retrieval uses this after obtaining all query vectors in
     * one hosted batch, so the remaining searches are local and may safely run in
     * parallel.
     */
    private RetrievalDtos.Result retrieveWithEmbedding(
            String query,
            EmbeddingGateway.QueryEmbedding embedding,
            RetrievalDtos.Filter filter,
            RetrievalDtos.Lane lane,
            int attempt) {
        long started = System.nanoTime();
        UnitedAirProperties.LaneConfig config =
                lane == RetrievalDtos.Lane.DEEP
                        ? properties.getRag().getDeep()
                        : properties.getRag().getFast();
        String traceId = TraceContext.traceId();
        String sessionUuid = TraceContext.sessionUuid();

        CompletableFuture<List<RetrievalDtos.Chunk>> lexicalLane =
                properties.getRag().isLexicalEnabled()
                        ? CompletableFuture.supplyAsync(
                                () -> withTrace(traceId, sessionUuid,
                                        () -> repository.lexicalSearch(
                                                query, filter,
                                                config.getLexicalTopK())))
                        : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<RetrievalDtos.Chunk>> vectorLane =
                embedding.comparable()
                        ? CompletableFuture.supplyAsync(
                                () -> withTrace(traceId, sessionUuid,
                                        () -> repository.vectorSearch(
                                                embedding.vector(), query, filter,
                                                config.getVectorTopK())))
                        : CompletableFuture.completedFuture(List.of());

        List<RetrievalDtos.Chunk> vectorHits = vectorLane.join();
        List<RetrievalDtos.Chunk> lexicalHits = lexicalLane.join();
        if (!properties.getRag().isLexicalEnabled()) {
            vectorHits = vectorHits.stream()
                    .map(chunk -> chunk.withScores(chunk.vectorScore(), null))
                    .toList();
        }
        if (!embedding.comparable()) {
            lexicalHits = lexicalHits.stream()
                    .map(chunk -> chunk.withScores(
                            null, chunk.lexicalScore()))
                    .toList();
        }

        List<Fused> fused = fuse(vectorHits, lexicalHits);
        List<Fused> reranked = rerank(query, fused);
        double threshold = properties.getRag().getSimilarityThreshold();
        List<Fused> admitted = reranked.stream()
                .filter(candidate ->
                        relevance(candidate.chunk()) >= threshold)
                .limit(config.getRerankKeep())
                .toList();
        List<RetrievalDtos.Ranked> evidence = new ArrayList<>();
        for (int index = 0; index < admitted.size(); index++) {
            Fused candidate = admitted.get(index);
            evidence.add(new RetrievalDtos.Ranked(
                    candidate.chunk(), candidate.rrfScore(),
                    candidate.rerankScore(), index + 1));
        }
        double topRelevance = admitted.isEmpty()
                ? 0.0 : relevance(admitted.getFirst().chunk());
        double confidence = confidence(
                admitted, config.getGenerateFrom(), topRelevance,
                filter.documentCodes() != null
                        && !filter.documentCodes().isEmpty());
        long durationMs =
                (System.nanoTime() - started) / 1_000_000;
        log.debug(
                "Focused retrieval[{} attempt {}] vector={} lexical={} "
                        + "fused={} admitted={} top={} conf={}",
                lane, attempt, vectorHits.size(), lexicalHits.size(),
                fused.size(), evidence.size(),
                String.format("%.3f", topRelevance),
                String.format("%.3f", confidence));
        return new RetrievalDtos.Result(
                evidence, lane, attempt,
                vectorHits.size(), lexicalHits.size(), fused.size(),
                topRelevance, confidence, durationMs, filter);
    }

    private static <T> T withTrace(String traceId, String sessionUuid, Supplier<T> work) {
        try {
            TraceContext.setTraceId(traceId);
            if (sessionUuid != null) {
                TraceContext.setSessionUuid(sessionUuid);
            }
            return work.get();
        } finally {
            TraceContext.clear();
        }
    }

    // ----------------------------------------------------------------- scoring ---

    /**
     * Absolute relevance in [0,1] used by the grounding gate. Either lane can carry a
     * chunk over the threshold on its own.
     */
    public static double relevance(RetrievalDtos.Chunk chunk) {
        double cosine = chunk.vectorScore() == null ? 0.0 : chunk.vectorScore();
        double bm25 = chunk.lexicalScore() == null ? 0.0 : chunk.lexicalScore();
        double lexical = bm25 <= 0 ? 0.0 : bm25 / (bm25 + LEXICAL_HALF_SATURATION);
        return Math.max(clamp(cosine), clamp(lexical));
    }

    /** Reciprocal Rank Fusion over the two lane orderings. */
    private List<Fused> fuse(List<RetrievalDtos.Chunk> vectorHits,
                             List<RetrievalDtos.Chunk> lexicalHits) {
        int k = properties.getRag().getRrfK();
        Map<Long, Fused> byId = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            RetrievalDtos.Chunk chunk = vectorHits.get(i);
            byId.put(chunk.id(), new Fused(chunk, 1.0 / (k + i + 1), 0, true, false));
        }

        for (int i = 0; i < lexicalHits.size(); i++) {
            RetrievalDtos.Chunk chunk = lexicalHits.get(i);
            double contribution = 1.0 / (k + i + 1);
            Fused existing = byId.get(chunk.id());
            if (existing == null) {
                byId.put(chunk.id(), new Fused(chunk, contribution, 0, false, true));
            } else {
                byId.put(chunk.id(), new Fused(existing.chunk(),
                        existing.rrfScore() + contribution, 0, existing.fromVector(), true));
            }
        }

        return new ArrayList<>(byId.values());
    }

    /**
     * Post-retrieval reranking (SRS 2.1).
     *
     * <p>Without a cross-encoder available offline, this scores the signals that actually
     * discriminate on this corpus: how much of the question's vocabulary the chunk covers,
     * whether the match lands in the section heading rather than incidentally in the body,
     * and whether both lanes agreed. Chunks found by both lanes are meaningfully more
     * likely to be right than chunks found by one, and RRF alone under-weights that.
     */
    private List<Fused> rerank(String query, List<Fused> candidates) {
        Set<String> queryTerms = contentWords(query);

        List<Fused> scored = candidates.stream().map(candidate -> {
            RetrievalDtos.Chunk chunk = candidate.chunk();

            double base = relevance(chunk);

            Set<String> chunkTerms = contentWords(chunk.content());
            long covered = queryTerms.stream().filter(chunkTerms::contains).count();
            double coverage = queryTerms.isEmpty() ? 0 : (double) covered / queryTerms.size();

            String heading = chunk.section() == null ? "" : chunk.section().toLowerCase(Locale.ROOT);
            long headingHits = queryTerms.stream().filter(heading::contains).count();
            double headingBoost = Math.min(0.15, headingHits * 0.05);

            double agreementBoost = candidate.fromVector() && candidate.fromLexical() ? 0.10 : 0.0;

            double score = (0.55 * base)
                    + (0.25 * coverage)
                    + headingBoost
                    + agreementBoost;

            return new Fused(chunk, candidate.rrfScore(), score,
                    candidate.fromVector(), candidate.fromLexical());
        }).sorted(Comparator.comparingDouble(Fused::rerankScore).reversed()).toList();

        return scored;
    }

    /**
     * Confidence in the evidence set, computed from the evidence rather than asked of the
     * model. A model's self-reported confidence is not evidence, and SRS 4.2.2 makes this
     * number the trigger for escalation, so it has to mean something.
     */
    private double confidence(
            List<Fused> admitted,
            int generateFrom,
            double topRelevance,
            boolean explicitlyDocumentScoped) {
        if (admitted.isEmpty()) {
            return 0.0;
        }

        // How much of the context budget we were actually able to fill with usable evidence.
        double support = Math.min(1.0, (double) admitted.size() / Math.max(1, generateFrom));

        // Independent corroboration: chunks both lanes surfaced.
        long agreed = admitted.stream().filter(f -> f.fromVector() && f.fromLexical()).count();
        double agreement = (double) agreed / admitted.size();

        double blended = (0.55 * topRelevance) + (0.25 * support) + (0.20 * agreement);

        // A single strong policy section is useful even when it cannot fill the whole
        // generation budget. Retain the richer blended score when multiple chunks or both
        // lanes corroborate, then apply document-scope calibration below.
        double calibrated = Math.max(blended, topRelevance * 0.8);
        if (explicitlyDocumentScoped) {
            // A high-confidence classifier has already constrained retrieval to the
            // authoritative document for this policy domain. Once a chunk from that
            // document clears the grounding threshold, do not discard it merely because
            // the small document cannot fill the whole context budget. Claim, citation,
            // audience and numeric-support gates still run before delivery.
            calibrated = Math.max(
                    calibrated, properties.getRag().getEscalationConfidence());
        }
        return clamp(calibrated);
    }

    private static Set<String> contentWords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) {
            return words;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 2) {
                words.add(token);
            }
        }
        return words;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Fused(RetrievalDtos.Chunk chunk,
                         double rrfScore,
                         double rerankScore,
                         boolean fromVector,
                         boolean fromLexical) { }
}
