package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.EmbeddingGateway;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HybridRetrieverTest {

    @Test
    void requestedDefaultsUseVectorOnlyAndLiteralSrsThresholds() {
        UnitedAirProperties properties = new UnitedAirProperties();

        assertThat(properties.getRag().isLexicalEnabled()).isFalse();
        assertThat(properties.getRag().getSimilarityThreshold()).isEqualTo(0.50);
        assertThat(properties.getRag().getEscalationConfidence()).isEqualTo(0.40);
    }

    @Test
    void lexicalLaneIsNotCalledWhenDisabled() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        var filter = RetrievalDtos.Filter.forRole(Role.PASSENGER);
        var vectorHit = chunk(0.72, null);

        when(repository.activeEmbeddingSource()).thenReturn("DETERMINISTIC");
        when(embeddings.embedQuery("baggage", "DETERMINISTIC"))
                .thenReturn(new EmbeddingGateway.QueryEmbedding(new float[] {1}, true));
        when(repository.vectorSearch(any(), eq("baggage"), eq(filter), eq(12)))
                .thenReturn(List.of(vectorHit));

        RetrievalDtos.Result result = new HybridRetriever(repository, embeddings, properties)
                .retrieve("baggage", filter, RetrievalDtos.Lane.FAST, 1);

        assertThat(result.vectorHits()).isEqualTo(1);
        assertThat(result.lexicalHits()).isZero();
        verify(repository, never()).lexicalSearch(any(), any(), Mockito.anyInt());
    }

    @Test
    void disabledLexicalRetrievalCannotInfluenceTheVectorGroundingGate() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        var filter = RetrievalDtos.Filter.forRole(Role.PASSENGER);

        // MariaDB's vector query also exposes a diagnostic full-text score. When lexical
        // retrieval is disabled, that score must not admit a semantically weak passage.
        var weakVectorWithStrongLexicalDiagnostic = chunk(0.34, 9.0);
        when(repository.activeEmbeddingSource()).thenReturn("DETERMINISTIC");
        when(embeddings.embedQuery("booking workflow", "DETERMINISTIC"))
                .thenReturn(new EmbeddingGateway.QueryEmbedding(new float[] {1}, true));
        when(repository.vectorSearch(
                any(), eq("booking workflow"), eq(filter), eq(12)))
                .thenReturn(List.of(weakVectorWithStrongLexicalDiagnostic));

        RetrievalDtos.Result result = new HybridRetriever(
                repository, embeddings, properties)
                .retrieve(
                        "booking workflow", filter,
                        RetrievalDtos.Lane.FAST, 1);

        assertThat(result.evidence()).isEmpty();
        assertThat(result.topSimilarity()).isZero();
        assertThat(result.lexicalHits()).isZero();
    }

    @Test
    void focusedCoverageQueriesAreMergedWithoutLexicalSearch() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        var filter = RetrievalDtos.Filter.forRole(Role.PASSENGER)
                .withDocumentCodes(Set.of("KB-AIR-004", "KB-AIR-005"));
        var cancellation = new RetrievalDtos.Chunk(
                1L, "cancel", "KB-AIR-004", "Cancellation", "Fee Matrix", 1,
                "txt", "fare-rule", "Passenger", "Value cancellation fee", 0.72, null);
        var seats = new RetrievalDtos.Chunk(
                2L, "seats", "KB-AIR-005", "Seats", "Seat Categories", 1,
                "txt", "fare-rule", "Passenger",
                "Standard Economy Preferred Economy Comfort Business Window Business Aisle",
                0.74, null);
        RetrievalDtos.Result base = new RetrievalDtos.Result(
                List.of(new RetrievalDtos.Ranked(cancellation, 0.5, 0.7, 1)),
                RetrievalDtos.Lane.FAST, 1, 1, 0, 1,
                0.72, 0.6, 10, filter);

        when(repository.activeEmbeddingSource()).thenReturn("DETERMINISTIC");
        when(embeddings.embedQueries(
                List.of("Seat Categories and Fees Domestic"), "DETERMINISTIC"))
                .thenReturn(List.of(
                        new EmbeddingGateway.QueryEmbedding(
                                new float[] {1}, true)));
        when(repository.vectorSearch(
                any(), eq("Seat Categories and Fees Domestic"), eq(filter), eq(12)))
                .thenReturn(List.of(seats));

        RetrievalDtos.Result augmented = new HybridRetriever(
                repository, embeddings, properties).augmentFocused(
                        base, List.of("Seat Categories and Fees Domestic"));

        assertThat(augmented.evidence())
                .extracting(ranked -> ranked.chunk().chunkUuid())
                .containsExactly("cancel", "seats");
        verify(repository, never()).lexicalSearch(any(), any(), Mockito.anyInt());
    }

    @Test
    void focusedCoverageKeepsTwoAdjacentChunksWhenASectionSpansAChunkBoundary() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        var filter = RetrievalDtos.Filter.forRole(Role.PASSENGER)
                .withDocumentCodes(Set.of("KB-AIR-004"));
        var overview = new RetrievalDtos.Chunk(
                1L, "overview", "KB-AIR-004", "Cancellation", "Overview", 1,
                "txt", "fare-rule", "Passenger", "Cancellation overview", 0.72, null);
        var matrixContinuation = new RetrievalDtos.Chunk(
                2L, "matrix-continuation", "KB-AIR-004", "Cancellation",
                "Cancellation Fee Matrix by Fare Type", 3,
                "txt", "fare-rule", "Passenger",
                "Full Flex | Any time up to 2 hours | Nil | Full base fare", 0.82, null);
        var completeMatrix = new RetrievalDtos.Chunk(
                3L, "complete-matrix", "KB-AIR-004", "Cancellation",
                "Cancellation Fee Matrix by Fare Type", 2,
                "txt", "fare-rule", "Passenger",
                "Fare Category | Cancellation Timing | Cancellation Fee | Refund",
                0.80, null);
        RetrievalDtos.Result base = new RetrievalDtos.Result(
                List.of(new RetrievalDtos.Ranked(overview, 0.5, 0.7, 1)),
                RetrievalDtos.Lane.FAST, 1, 1, 0, 1,
                0.72, 0.6, 10, filter);

        String focusedQuery = "2.1 Cancellation Fee Matrix by Fare Type";
        when(repository.activeEmbeddingSource()).thenReturn("DETERMINISTIC");
        when(embeddings.embedQueries(List.of(focusedQuery), "DETERMINISTIC"))
                .thenReturn(List.of(new EmbeddingGateway.QueryEmbedding(
                        new float[] {1}, true)));
        when(repository.vectorSearch(any(), eq(focusedQuery), eq(filter), eq(12)))
                .thenReturn(List.of(matrixContinuation, completeMatrix));

        RetrievalDtos.Result augmented = new HybridRetriever(
                repository, embeddings, properties)
                .augmentFocused(base, List.of(focusedQuery));

        assertThat(augmented.evidence())
                .extracting(ranked -> ranked.chunk().chunkUuid())
                .containsExactlyInAnyOrder(
                        "overview", "matrix-continuation", "complete-matrix");
        verify(repository, never()).lexicalSearch(any(), any(), Mockito.anyInt());
    }

    @Test
    void multipartCoverageUsesOneEmbeddingBatchForEveryFocusedSection() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        var filter = RetrievalDtos.Filter.forRole(Role.AIRLINE_STAFF)
                .withDocumentCodes(Set.of("KB-AIR-006", "KB-AIR-007"));
        var baseChunk = new RetrievalDtos.Chunk(
                1L, "base", "KB-AIR-007", "Operations", "Overview", 1,
                "txt", "staff-operations", "Airline Staff",
                "Operational overview", 0.72, null);
        RetrievalDtos.Result base = new RetrievalDtos.Result(
                List.of(new RetrievalDtos.Ranked(baseChunk, 0.5, 0.7, 1)),
                RetrievalDtos.Lane.FAST, 1, 1, 0, 1,
                0.72, 0.6, 10, filter);
        List<String> queries = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(index -> "focused-" + index)
                .toList();
        List<EmbeddingGateway.QueryEmbedding> vectors = queries.stream()
                .map(ignored -> new EmbeddingGateway.QueryEmbedding(
                        new float[] {1}, true))
                .toList();

        when(repository.activeEmbeddingSource()).thenReturn("HOSTED");
        when(embeddings.embedQueries(queries, "HOSTED")).thenReturn(vectors);
        when(repository.vectorSearch(any(), any(), eq(filter), eq(12)))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    int index = Integer.parseInt(query.substring("focused-".length()));
                    return List.of(new RetrievalDtos.Chunk(
                            100L + index, query, "KB-AIR-007", "Operations",
                            query, index, "txt", "staff-operations",
                            "Airline Staff", query + " evidence", 0.75, null));
                });

        RetrievalDtos.Result augmented = new HybridRetriever(
                repository, embeddings, properties)
                .augmentFocused(base, queries);

        assertThat(augmented.evidence()).hasSize(11);
        verify(embeddings).embedQueries(queries, "HOSTED");
        verify(embeddings, never()).embedQuery(any(), any());
        verify(repository, never()).lexicalSearch(any(), any(), Mockito.anyInt());
    }

    @Test
    void lexicalScoreThreeMeetsTheSrsRelevanceFloor() {
        assertThat(HybridRetriever.relevance(chunk(null, 3.0)))
                .isEqualTo(0.5, within(0.0001));
        assertThat(HybridRetriever.relevance(chunk(0.49, 2.9))).isLessThan(0.5);
    }

    @Test
    void eitherRetrievalLaneCanCarryAChunkAcrossTheFloor() {
        assertThat(HybridRetriever.relevance(chunk(0.72, null))).isEqualTo(0.72);
        assertThat(HybridRetriever.relevance(chunk(0.1, 9.0))).isEqualTo(0.75);
    }

    @Test
    void lexicalLaneStartsBeforeABlockedEmbeddingCompletes() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        properties.getRag().setLexicalEnabled(true);
        var filter = RetrievalDtos.Filter.forRole(Role.PASSENGER);
        CompletableFuture<Void> releaseEmbedding = new CompletableFuture<>();

        when(repository.lexicalSearch("baggage", filter, 12)).thenReturn(List.of());
        when(repository.activeEmbeddingSource()).thenReturn("HOSTED");
        when(embeddings.embedQuery("baggage", "HOSTED")).thenAnswer(ignored -> {
            releaseEmbedding.join();
            return new EmbeddingGateway.QueryEmbedding(new float[] {1}, false);
        });

        var retrieval = CompletableFuture.supplyAsync(() ->
                new HybridRetriever(repository, embeddings, properties)
                        .retrieve("baggage", filter, RetrievalDtos.Lane.FAST, 1));

        verify(repository, Mockito.timeout(Duration.ofSeconds(1).toMillis()))
                .lexicalSearch("baggage", filter, 12);
        releaseEmbedding.complete(null);

        assertThat(retrieval.join().lexicalHits()).isZero();
    }

    @Test
    void aSingleAuthoritativeChunkThatClearsGroundingDoesNotBecomeLowConfidence() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        properties.getRag().setLexicalEnabled(true);
        var filter = RetrievalDtos.Filter.forRole(Role.PASSENGER);
        var exactPolicy = chunk(null, 3.1);

        when(repository.lexicalSearch("infant baggage", filter, 12))
                .thenReturn(List.of(exactPolicy));
        when(repository.activeEmbeddingSource()).thenReturn("HOSTED");
        when(embeddings.embedQuery("infant baggage", "HOSTED"))
                .thenReturn(new EmbeddingGateway.QueryEmbedding(new float[] {1}, false));

        RetrievalDtos.Result result = new HybridRetriever(repository, embeddings, properties)
                .retrieve("infant baggage", filter, RetrievalDtos.Lane.FAST, 1);

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.confidence())
                .isGreaterThanOrEqualTo(properties.getRag().getEscalationConfidence());
    }

    @Test
    void anExplicitDocumentHintCanTrustAnAdmittedChunkFromThatDocument() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        properties.getRag().setLexicalEnabled(true);
        var filter = RetrievalDtos.Filter.forRole(Role.PASSENGER)
                .withDocumentCodes(Set.of("KB-AIR-005"));
        var seatPolicy = new RetrievalDtos.Chunk(
                1L, "chunk", "KB-AIR-005", "Fare and Seat Policy", "3 Seat Selection", 1,
                "pdf", "fare-rule", "Passenger",
                "Seat types, published selection fees and upgrade paths.", null, 3.1);

        when(repository.lexicalSearch(
                "seat types selection fees upgrade paths", filter, 12))
                .thenReturn(List.of(seatPolicy));
        when(repository.activeEmbeddingSource()).thenReturn("HOSTED");
        when(embeddings.embedQuery(
                "seat types selection fees upgrade paths", "HOSTED"))
                .thenReturn(new EmbeddingGateway.QueryEmbedding(new float[] {1}, false));

        RetrievalDtos.Result result = new HybridRetriever(repository, embeddings, properties)
                .retrieve(
                        "seat types selection fees upgrade paths",
                        filter, RetrievalDtos.Lane.FAST, 1);

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.confidence())
                .isGreaterThanOrEqualTo(properties.getRag().getEscalationConfidence());
    }

    @Test
    void lexicalCoverageAugmentationAddsOneGroundedChunkPerFocusedQuery() {
        KbRepository repository = mock(KbRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        UnitedAirProperties properties = new UnitedAirProperties();
        var filter = RetrievalDtos.Filter.forRole(Role.PASSENGER)
                .withDocumentCodes(Set.of("KB-AIR-003"));
        var cabin = new RetrievalDtos.Chunk(
                1L, "cabin", "KB-AIR-003", "Baggage", "Cabin Baggage", 1,
                "txt", "baggage", "Passenger", "Economy | 1 piece | 7 kg", null, 4.0);
        var checked = new RetrievalDtos.Chunk(
                2L, "checked", "KB-AIR-003", "Baggage", "Checked Baggage", 2,
                "txt", "baggage", "Passenger", "Economy | Value | 15 kg", null, 5.0);
        RetrievalDtos.Result base = new RetrievalDtos.Result(
                List.of(new RetrievalDtos.Ranked(cabin, 0.5, 0.7, 1)),
                RetrievalDtos.Lane.DEEP, 2, 0, 1, 1,
                0.57, 0.6, 10, filter);

        when(repository.lexicalSearch(
                "Free Checked Baggage Allowance by Travel Class and Fare Type Economy",
                filter, 20)).thenReturn(List.of(checked));

        RetrievalDtos.Result augmented = new HybridRetriever(
                repository, embeddings, properties).augmentLexically(
                        base,
                        List.of("Free Checked Baggage Allowance by Travel Class and Fare Type Economy"));

        assertThat(augmented.evidence())
                .extracting(ranked -> ranked.chunk().chunkUuid())
                .containsExactly("cabin", "checked");
        assertThat(augmented.topSimilarity()).isGreaterThanOrEqualTo(base.topSimilarity());
    }

    private static RetrievalDtos.Chunk chunk(Double vector, Double lexical) {
        return new RetrievalDtos.Chunk(
                1L, "chunk", "KB-AIR-001", "Policy", "1", 1,
                "pdf", "policy-manual", "All", "content", vector, lexical);
    }
}
