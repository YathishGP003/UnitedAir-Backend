package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unitedair.ai.audit.AuditService;
import com.unitedair.ai.llm.EmbeddingGateway;
import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class IngestionServiceTest {

    @Test
    void retrievalEmbeddingBoostsTheSectionHeadingWithoutChangingStoredContent() {
        KbChunker.ChunkDraft draft = new KbChunker.ChunkDraft(
                3,
                "6.3 Waitlist Priority Rules",
                "6.3",
                4,
                "6.3 Waitlist Priority Rules\nPriority Rank | Passenger Category",
                20);

        String embeddingText = IngestionService.retrievalPassageText(draft);

        assertThat(embeddingText)
                .startsWith("6.3 Waitlist Priority Rules\n6.3 Waitlist Priority Rules")
                .contains(draft.content());
        assertThat(embeddingText.split("Waitlist Priority Rules", -1))
                .hasSize(IngestionService.SECTION_HEADING_WEIGHT + 2);
    }

    @Test
    void validationFailureIsRecordedInTheDurableAttemptLedger() {
        IngestionAttemptService attempts = mock(IngestionAttemptService.class);
        when(attempts.start(9L, "empty.txt")).thenReturn("attempt-1");
        TextExtractor extractor = mock(TextExtractor.class);
        IngestionService service = new IngestionService(
                mock(JdbcClient.class),
                extractor,
                mock(KbFrontMatterParser.class),
                mock(KbChunker.class),
                mock(EmbeddingGateway.class),
                attempts,
                mock(AuditService.class),
                new UnitedAirProperties());

        assertThatThrownBy(() ->
                service.ingest(new byte[0], "empty.txt", 9L, "ADMIN"))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("empty");

        verify(attempts).start(9L, "empty.txt");
        verify(attempts).fail(
                eq("attempt-1"),
                eq(IngestionAttemptService.FailurePhase.VALIDATION),
                contains("empty"),
                anyLong());
        verifyNoInteractions(extractor);
    }
}
