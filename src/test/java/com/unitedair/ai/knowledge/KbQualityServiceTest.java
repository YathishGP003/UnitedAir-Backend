package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class KbQualityServiceTest {

    private final KbQualityRepository repository = mock(KbQualityRepository.class);
    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final KbQualityService service = new KbQualityService(repository, retriever);

    @Test
    void reportsCorpusDefectsWithoutMutatingTheCorpus() {
        when(repository.activeChunks()).thenReturn(List.of(
                chunk(1, "KB-AIR-001", "Cancellation fees depend on fare timing.", 6144),
                chunk(2, "KB-AIR-001", "Cancellation fees depend on fare timing.", 6144),
                chunk(3, "KB-AIR-002", "", 6144),
                chunk(4, "KB-AIR-003", "# Baggage", 6144),
                chunk(5, "KB-AIR-004", "doc_type=fare_rule, audience=passenger", 0)));
        when(repository.activeDocumentCount()).thenReturn(4);
        when(retriever.retrieve(anyString(), any(), any(), anyInt()))
                .thenReturn(emptyResult());

        KbDtos.KbQualityReport report = service.inspect();

        assertThat(report.activeDocuments()).isEqualTo(4);
        assertThat(report.activeChunks()).isEqualTo(5);
        assertThat(report.issueCounts())
                .containsEntry("EMPTY_CONTENT", 1L)
                .containsEntry("HEADER_ONLY", 1L)
                .containsEntry("METADATA_ONLY", 1L)
                .containsEntry("EXACT_DUPLICATE", 1L)
                .containsEntry("MISSING_VECTOR", 1L);
        assertThat(report.issues()).extracting(KbDtos.KbQualityIssue::documentCode)
                .contains("KB-AIR-002", "KB-AIR-003", "KB-AIR-004");
        assertThat(report.expectedVectorDimensions()).isEqualTo(1536);
        assertThat(report.probes()).hasSize(5);

        // Inspection is read-only: KbQualityRepository deliberately exposes no mutation API.
    }

    @Test
    void roleFilteredProbeReportsSelectedDocumentsAndAudienceViolations() {
        RetrievalDtos.Chunk staffOnly = new RetrievalDtos.Chunk(
                9L, "chunk-9", "KB-OPS-001", "Disruption SOP", "Recovery", 2,
                "TXT", "sop", "Airline Staff", "Rebook disrupted passengers",
                0.91, 0.0);
        RetrievalDtos.Result result = new RetrievalDtos.Result(
                List.of(new RetrievalDtos.Ranked(staffOnly, 0.1, 0.1, 1)),
                RetrievalDtos.Lane.FAST, 1, 1, 0, 1, 0.91, 0.91, 2,
                RetrievalDtos.Filter.forRole(com.unitedair.ai.identity.Role.PASSENGER));
        when(repository.activeChunks()).thenReturn(List.of());
        when(repository.activeDocumentCount()).thenReturn(0);
        when(retriever.retrieve(anyString(), any(), any(), anyInt())).thenReturn(result);

        KbDtos.KbQualityReport report = service.inspect();

        assertThat(report.probes())
                .anySatisfy(probe -> {
                    assertThat(probe.selectedDocumentCodes()).contains("KB-OPS-001");
                    assertThat(probe.audienceViolation()).isTrue();
                });
    }

    private static KbQualityRepository.ChunkQualityRow chunk(
            long id, String code, String content, Integer vectorBytes) {
        return new KbQualityRepository.ChunkQualityRow(
                id, 1L, code, code + " title", "Section", 1,
                "TXT", "policy-manual", "Passenger", "{}", content,
                vectorBytes);
    }

    private static RetrievalDtos.Result emptyResult() {
        return new RetrievalDtos.Result(
                List.of(), RetrievalDtos.Lane.FAST, 1,
                0, 0, 0, 0, 0, 1,
                new RetrievalDtos.Filter(
                        List.of("All"), java.util.Set.of(), java.util.Set.of(),
                        java.util.Set.of(), LocalDate.now(), java.util.Map.of()));
    }
}
