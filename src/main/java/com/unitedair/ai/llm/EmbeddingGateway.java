package com.unitedair.ai.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Produces the 1536-dimension vectors stored in {@code kb_chunk.embedding}.
 *
 * <p>Batches requests and backs off on failure, because the hosted embedding endpoint rate
 * limits and ingesting eight KB documents in one go will hit that limit otherwise.
 *
 * <p>In offline mode a deterministic hashed bag-of-words embedding is used instead. It is
 * genuinely weaker than a learned embedding at capturing paraphrase, but it is stable,
 * needs no network, and - crucially - the lexical retrieval lane runs alongside it, so
 * keyword-heavy airline queries ("cancellation fee Value fare") still retrieve correctly.
 */
@Component
public class EmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingGateway.class);
    private static final int DIMENSIONS = 1536;

    /** How long a user-facing turn will wait for a query embedding before degrading. */
    private static final long QUERY_EMBED_TIMEOUT_MS = 4000;
    private static final int QUERY_CACHE_LIMIT = 512;
    private static final Duration QUERY_CACHE_TTL = Duration.ofMinutes(30);

    private final AiMode mode;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final UnitedAirProperties.Ingestion config;
    private final HostedCallLimiter limiter;
    private final Map<EmbeddingCacheKey, CacheEntry> queryCache = new ConcurrentHashMap<>();

    public EmbeddingGateway(AiMode mode,
                            ObjectProvider<EmbeddingModel> embeddingModel,
                            UnitedAirProperties properties,
                            HostedCallLimiter limiter) {
        this.mode = mode;
        this.embeddingModel = embeddingModel;
        this.config = properties.getIngestion();
        this.limiter = limiter;
    }

    public int dimensions() {
        return DIMENSIONS;
    }

    /**
     * Embeds a single query, with a hard deadline.
     *
     * <p>Ingestion can afford to wait for the embedding endpoint; a passenger waiting for
     * an answer cannot. GitHub Models has been observed taking upwards of fifteen seconds
     * for one query embedding, and because retrieval blocks on it the entire turn stalls -
     * including turns that end in "no matching policy found" and never call a model at all.
     *
     * <p>Past the deadline we fall back to the deterministic embedding and let the lexical
     * lane carry the query. That degrades semantic recall for that one turn, which is a far
     * better trade than a fifteen-second wait. The fallback is recorded so it shows up in
     * the logs rather than silently changing behaviour.
     */
    public QueryEmbedding embedQuery(String text) {
        EmbeddingModel model = embeddingModel.getIfAvailable();
        String space = mode == AiMode.OFFLINE || model == null
                ? "deterministic-v1"
                : "hosted:" + model.getClass().getName();
        EmbeddingCacheKey key = new EmbeddingCacheKey(space, normaliseQuery(text));
        CacheEntry cached = queryCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.embedding();
        }
        if (cached != null) {
            queryCache.remove(key, cached);
        }

        QueryEmbedding computed;
        if (mode == AiMode.OFFLINE || model == null) {
            // The database can survive a switch from live to offline mode. Without a
            // persisted corpus-space identifier we cannot prove that its existing vectors
            // were produced by this deterministic embedder, so comparing them would create
            // confidently random results. The lexical lane remains fully available.
            computed = new QueryEmbedding(deterministicEmbedding(text), false);
            cache(key, computed);
            return computed;
        }
        if (!limiter.tryAcquire(Duration.ofMillis(100))) {
            log.warn("Hosted embedding capacity is busy; using lexical retrieval for this turn.");
            return new QueryEmbedding(deterministicEmbedding(text), false);
        }
        try {
            float[] vector = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return model.embed(text);
                        } finally {
                            limiter.release();
                        }
                    })
                    .get(QUERY_EMBED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            computed = new QueryEmbedding(vector, true);
            cache(key, computed);
            return computed;
        } catch (TimeoutException e) {
            log.warn("Query embedding exceeded {} ms; answering from the lexical lane alone "
                    + "for this turn.", QUERY_EMBED_TIMEOUT_MS);
            return new QueryEmbedding(deterministicEmbedding(text), false);
        } catch (Exception e) {
            log.warn("Query embedding failed ({}); answering from the lexical lane alone "
                    + "for this turn.", e.toString());
            return new QueryEmbedding(deterministicEmbedding(text), false);
        }
    }

    private void cache(EmbeddingCacheKey key, QueryEmbedding embedding) {
        if (queryCache.size() >= QUERY_CACHE_LIMIT) {
            Instant now = Instant.now();
            queryCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
            if (queryCache.size() >= QUERY_CACHE_LIMIT) {
                queryCache.keySet().stream().findFirst().ifPresent(queryCache::remove);
            }
        }
        queryCache.put(key, new CacheEntry(embedding, Instant.now().plus(QUERY_CACHE_TTL)));
    }

    private static String normaliseQuery(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * A query vector plus whether it can legitimately be compared with the stored corpus.
     *
     * <p>This distinction is not pedantry. When the hosted embedding endpoint fails in live
     * mode, substituting a locally computed vector puts the query in a completely different
     * space from chunks embedded by the hosted model. Cosine similarity between the two is
     * meaningless - not merely weaker - so the vector lane returns twelve essentially random
     * passages and confidence is computed from noise. That is strictly worse than not
     * running the lane at all, because the lexical lane's good results get diluted by
     * fusion.
     *
     * <p>When {@code comparable} is false the retriever skips the vector lane and answers
     * from full-text search alone.
     */
    public record QueryEmbedding(float[] vector, boolean comparable) { }

    public record EmbeddingBatch(
            List<float[]> vectors,
            String modelIdentifier,
            int dimensions,
            String generationSource,
            Instant embeddedAt) { }

    public float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    /** Embeds in configured batches, preserving input order. */
    public List<float[]> embed(List<String> texts) {
        return embedWithProvenance(texts).vectors();
    }

    /**
     * Embeds a query in the active corpus vector space.
     *
     * <p>Chat generation mode and retrieval vector provenance are separate concerns.
     * A live chat model must still use a deterministic query vector when the published
     * corpus was embedded deterministically. Conversely, an offline deterministic vector
     * must never be compared with a hosted corpus.
     */
    public QueryEmbedding embedQuery(String text, String corpusSource) {
        String source = corpusSource == null
                ? "UNKNOWN"
                : corpusSource.trim().toUpperCase(Locale.ROOT);
        if ("DETERMINISTIC".equals(source)) {
            EmbeddingCacheKey key = new EmbeddingCacheKey(
                    "deterministic-v1", normaliseQuery(text));
            CacheEntry cached = queryCache.get(key);
            if (cached != null && !cached.expired()) {
                return cached.embedding();
            }
            QueryEmbedding computed =
                    new QueryEmbedding(deterministicEmbedding(text), true);
            cache(key, computed);
            return computed;
        }
        if ("HOSTED".equals(source)) {
            return embedQuery(text);
        }
        log.warn("Active KB versions do not share one known embedding space ({}); "
                + "skipping vector comparison for this turn.", source);
        return new QueryEmbedding(deterministicEmbedding(text), false);
    }

    /**
     * Embeds several focused retrieval queries in one hosted request.
     *
     * <p>A multipart question can require a dozen policy sections. Calling the hosted
     * endpoint once per section creates an avoidable burst that competes with chat
     * generation for the shared bulkhead. This method preserves query order, reuses the
     * same cache as {@link #embedQuery(String)}, and submits only cache misses as one
     * embedding batch.
     */
    public List<QueryEmbedding> embedQueries(
            List<String> texts, String corpusSource) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String source = corpusSource == null
                ? "UNKNOWN"
                : corpusSource.trim().toUpperCase(Locale.ROOT);
        if (!"HOSTED".equals(source)) {
            return texts.stream()
                    .map(text -> embedQuery(text, source))
                    .toList();
        }

        EmbeddingModel model = embeddingModel.getIfAvailable();
        if (mode != AiMode.LIVE || model == null) {
            return texts.stream()
                    .map(text -> new QueryEmbedding(
                            deterministicEmbedding(text), false))
                    .toList();
        }

        String space = "hosted:" + model.getClass().getName();
        List<QueryEmbedding> results = new ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index++) {
            results.add(null);
        }
        List<String> misses = new ArrayList<>();
        List<Integer> missIndexes = new ArrayList<>();
        List<EmbeddingCacheKey> missKeys = new ArrayList<>();

        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            EmbeddingCacheKey key =
                    new EmbeddingCacheKey(space, normaliseQuery(text));
            CacheEntry cached = queryCache.get(key);
            if (cached != null && !cached.expired()) {
                results.set(index, cached.embedding());
                continue;
            }
            if (cached != null) {
                queryCache.remove(key, cached);
            }
            misses.add(text);
            missIndexes.add(index);
            missKeys.add(key);
        }
        if (misses.isEmpty()) {
            return List.copyOf(results);
        }

        if (!limiter.tryAcquire(Duration.ofMillis(100))) {
            log.warn("Hosted embedding capacity is busy; focused vector retrieval "
                    + "is unavailable for this turn.");
            fillIncomparable(results, texts, missIndexes);
            return List.copyOf(results);
        }
        try {
            List<float[]> vectors = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return model.embed(misses);
                        } finally {
                            limiter.release();
                        }
                    })
                    .get(QUERY_EMBED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (vectors.size() != misses.size()) {
                throw new IllegalStateException(
                        "Embedding batch returned an unexpected vector count.");
            }
            for (int miss = 0; miss < vectors.size(); miss++) {
                QueryEmbedding computed =
                        new QueryEmbedding(vectors.get(miss), true);
                results.set(missIndexes.get(miss), computed);
                cache(missKeys.get(miss), computed);
            }
            return List.copyOf(results);
        } catch (TimeoutException timeout) {
            log.warn("Focused embedding batch exceeded {} ms; skipping those vector "
                    + "queries for this turn.", QUERY_EMBED_TIMEOUT_MS);
        } catch (Exception failure) {
            log.warn("Focused embedding batch failed ({}); skipping those vector "
                    + "queries for this turn.", failure.toString());
        }
        fillIncomparable(results, texts, missIndexes);
        return List.copyOf(results);
    }

    private static void fillIncomparable(
            List<QueryEmbedding> results,
            List<String> texts,
            List<Integer> indexes) {
        for (int index : indexes) {
            results.set(index, new QueryEmbedding(
                    deterministicEmbedding(texts.get(index)), false));
        }
    }

    /**
     * Embeds one governed document in one comparable vector space.
     *
     * <p>If one hosted batch exhausts its retries, every passage is recomputed locally.
     * A version is therefore entirely HOSTED or entirely DETERMINISTIC, never a mixture
     * whose cosine distances would be meaningless.
     */
    public EmbeddingBatch embedWithProvenance(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingBatch(
                    List.of(), "deterministic-hashed-bow-v1",
                    DIMENSIONS, "DETERMINISTIC", Instant.now());
        }
        if (mode == AiMode.OFFLINE) {
            return deterministicBatch(texts);
        }

        EmbeddingModel model = embeddingModel.getIfAvailable();
        if (model == null) {
            log.warn("No EmbeddingModel bean available; falling back to deterministic embeddings.");
            return deterministicBatch(texts);
        }

        List<float[]> out = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, config.getEmbedBatchSize());
        boolean hosted = true;

        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
            BatchAttempt attempt = embedBatchWithRetry(model, batch);
            out.addAll(attempt.vectors());
            hosted &= attempt.hosted();
        }
        if (!hosted) {
            log.warn("A hosted embedding batch degraded; recomputing the whole document "
                    + "with deterministic-v1 to preserve a uniform vector space.");
            return deterministicBatch(texts);
        }
        return new EmbeddingBatch(
                List.copyOf(out),
                "text-embedding-3-small:" + model.getClass().getSimpleName(),
                DIMENSIONS, "HOSTED", Instant.now());
    }

    private BatchAttempt embedBatchWithRetry(EmbeddingModel model, List<String> batch) {
        long backoff = config.getEmbedBackoffMs();

        for (int attempt = 1; attempt <= config.getEmbedMaxRetries(); attempt++) {
            try {
                return new BatchAttempt(model.embed(batch), true);
            } catch (Exception e) {
                boolean lastAttempt = attempt == config.getEmbedMaxRetries();
                if (lastAttempt) {
                    // Degrading beats failing the whole ingestion: the chunk still lands
                    // and stays findable through the lexical lane.
                    log.error("Embedding failed after {} attempts ({}). "
                                    + "Falling back to deterministic embeddings for this batch.",
                            attempt, e.toString());
                    return new BatchAttempt(
                            batch.stream().map(EmbeddingGateway::deterministicEmbedding).toList(),
                            false);
                }
                log.warn("Embedding attempt {} failed ({}); retrying in {} ms",
                        attempt, e.toString(), backoff);
                sleep(backoff);
                backoff *= 2;
            }
        }
        return new BatchAttempt(
                batch.stream().map(EmbeddingGateway::deterministicEmbedding).toList(),
                false);
    }

    private static EmbeddingBatch deterministicBatch(List<String> texts) {
        return new EmbeddingBatch(
                texts.stream().map(EmbeddingGateway::deterministicEmbedding).toList(),
                "deterministic-hashed-bow-v1",
                DIMENSIONS, "DETERMINISTIC", Instant.now());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Deterministic hashed bag-of-words embedding.
     *
     * <p>Each token is hashed to a dimension and accumulated with a sign derived from the
     * same digest, then the vector is L2-normalised so cosine similarity behaves. Identical
     * text always yields an identical vector, which is what makes offline runs repeatable.
     */
    static float[] deterministicEmbedding(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            vector[0] = 1.0f;
            return vector;
        }

        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            byte[] digest = sha256(token);
            int index = Math.floorMod(readInt(digest, 0), DIMENSIONS);
            float sign = (digest[4] & 1) == 0 ? 1.0f : -1.0f;
            vector[index] += sign;

            // A second projection per token reduces collisions enough to keep short
            // airline phrases distinguishable.
            int index2 = Math.floorMod(readInt(digest, 8), DIMENSIONS);
            float sign2 = (digest[12] & 1) == 0 ? 1.0f : -1.0f;
            vector[index2] += sign2 * 0.5f;
        }

        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            vector[0] = 1.0f;
            return vector;
        }
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private record EmbeddingCacheKey(String space, String query) { }

    private record BatchAttempt(List<float[]> vectors, boolean hosted) { }

    private record CacheEntry(QueryEmbedding embedding, Instant expiresAt) {
        boolean expired() {
            return !expiresAt.isAfter(Instant.now());
        }
    }
}
