package com.unitedair.ai.privacy;

import com.unitedair.ai.llm.ChatDtos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PnrContextRedactorTest {

    private final PnrContextRedactor resolver =
            new PnrContextRedactor(new PiiRedactor());

    @Test
    void recognisesAnAllLetterPnrWhenItIsExplicitlyLabelled() {
        RedactionResult result = resolver.redact(
                "show my booking for PNR XBHJDM", List.of());

        assertThat(result.first(PiiType.PNR)).contains("XBHJDM");
        assertThat(result.redacted())
                .isEqualTo("show my booking for PNR [AIR-PNR-REDACTED]");
    }

    @Test
    void recognisesABareAllLetterPnrAfterTheAssistantRequestedOne() {
        List<ChatDtos.HistoryTurn> history = List.of(
                new ChatDtos.HistoryTurn(
                        "ASSISTANT",
                        "Please share the six-character booking reference (PNR) for that request."));

        RedactionResult result = resolver.redact("XBHJDM", history);

        assertThat(result.first(PiiType.PNR)).contains("XBHJDM");
        assertThat(result.redacted()).isEqualTo("[AIR-PNR-REDACTED]");
    }

    @Test
    void doesNotTreatAnOrdinaryBareWordAsAPnrWithoutBookingContext() {
        RedactionResult result = resolver.redact("REFUND", List.of());

        assertThat(result.first(PiiType.PNR)).isEmpty();
        assertThat(result.redacted()).isEqualTo("REFUND");
    }

    @Test
    void recognisesTheExplicitBookingReferenceLabel() {
        RedactionResult result = resolver.redact(
                "booking reference is XBHJDM", List.of());

        assertThat(result.first(PiiType.PNR)).contains("XBHJDM");
        assertThat(result.redacted()).contains("[AIR-PNR-REDACTED]");
    }

    @Test
    void explicitPendingSlotRecognisesBarePnrWithoutDependingOnPromptText() {
        RedactionResult result = resolver.redact(
                "XBHJDM", List.of(), "pnr");

        assertThat(result.first(PiiType.PNR)).contains("XBHJDM");
        assertThat(result.redacted()).isEqualTo("[AIR-PNR-REDACTED]");
    }

    @Test
    void pendingPnrSlotDoesNotTurnWorkflowWordsIntoBookingReferences() {
        assertThat(resolver.redact("refund", List.of(), "pnr")
                .first(PiiType.PNR)).isEmpty();
        assertThat(resolver.redact("cancel", List.of(), "pnr")
                .first(PiiType.PNR)).isEmpty();
    }
}
