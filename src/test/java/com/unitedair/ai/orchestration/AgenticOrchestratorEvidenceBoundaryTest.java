package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.llm.ChatDtos;
import org.junit.jupiter.api.Test;

class AgenticOrchestratorEvidenceBoundaryTest {

    @Test
    void validationCanUseOnlyEvidenceActuallySuppliedToTheModel() {
        List<RetrievalDtos.Ranked> retrieved = List.of(
                ranked(1, "4.1 Items Not Allowed", "Explosive materials are not allowed."),
                ranked(2, "4.2 Checked Baggage Only",
                        "Sharp objects must be safely packed in checked baggage."),
                ranked(3, "2.2 Cabin Items", "Medication is allowed in reasonable quantity."));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(), "QUESTION: Can I bring a bomb and knife?",
                List.of(
                        new ChatDtos.Grounding("E1", "KB-AIR-003", "Explosive materials"),
                        new ChatDtos.Grounding("E2", "KB-AIR-003", "Sharp objects")));

        assertThat(AgenticOrchestrator.evidenceSuppliedToModel(request, retrieved))
                .extracting(ranked -> ranked.chunk().section())
                .containsExactly("4.1 Items Not Allowed", "4.2 Checked Baggage Only");
    }

    private static RetrievalDtos.Ranked ranked(
            int rank,
            String section,
            String content) {
        RetrievalDtos.Chunk chunk = new RetrievalDtos.Chunk(
                (long) rank, "chunk-" + rank, "KB-AIR-003", "Baggage Policy",
                section, 1, "TXT", "sop", "Passenger", content, 0.9, 1.0);
        return new RetrievalDtos.Ranked(chunk, 0.9, 0.9, rank);
    }
}
