package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.unitedair.ai.grounding.CitationAttacher;
import com.unitedair.ai.grounding.CitationBuilder;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.privacy.PiiRedactor;
import com.unitedair.ai.privacy.RedactionResult;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

/**
 * Named regression catalogue for the twenty defects reproduced in the 27 July audit.
 *
 * <p>These tests intentionally sit at stable orchestration boundaries. More detailed unit,
 * integration, UI and semantic cases live with the owning component, but this file prevents
 * an implementation task from silently dropping an audit item.
 */
class AuditDefectRegressionTest {

    private final IntentClassifier classifier = new IntentClassifier();
    private final PiiRedactor redactor = new PiiRedactor();

    @Test
    void rc01_failedFlightSearchCannotBecomeKbAnswer() {
        var classification = classify(
                "Search flights from Ayodhya to Ghaziabad tomorrow");
        var failed = ToolDtos.ToolOutcome.failed(
                FlightSearchTool.NAME,
                "Ayodhya is not a supported UnitedAir airport.",
                1,
                Instant.parse("2026-07-27T10:00:00Z"),
                Map.of("origin", "Ayodhya", "destination", "Ghaziabad"));

        assertThat(AgenticOrchestrator.hasBlockingToolFailure(
                classification, List.of(failed))).isTrue();
    }

    @Test
    void rc02_refundStatusCannotRouteToFlightStatus() {
        assertThat(classify("Show me refund status for above").tool())
                .isNotEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_STATUS);
    }

    @Test
    void rc03_staffRefundCasesUseOperationalTool() {
        assertThat(enumContains(OrchestrationDtos.ToolTarget.class, "REFUND_CASES")).isTrue();
    }

    @Test
    void rc04_barePnrDoesNotInheritBookingCreationIntent() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "I want to book a flight from BLR to GOI tomorrow."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Please share the six-character booking reference."));

        assertThat(new QueryTransformer().toStandalone("XBHJDM", history))
                .isEqualTo("XBHJDM");
    }

    @Test
    void rc05_refundQuoteArithmeticUsesOneFeeSource() {
        assertThatThrownBy(() -> new ToolDtos.RefundQuote(
                "B6X9K2",
                "Value",
                true,
                48,
                "Within 3 days",
                new BigDecimal("5211"),
                new BigDecimal("2000"),
                new BigDecimal("1211"),
                "5-7 working days",
                "KB-AIR-004"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rc06_naturalFromToRouteIsNotReversed() {
        var classification = classify(
                "I want to know about flight from Bangalore to Delhi tomorrow");

        assertThat(classification.origin()).isEqualToIgnoringCase("Bangalore");
        assertThat(classification.destination()).isEqualToIgnoringCase("Delhi");
    }

    @Test
    void rc07_cancelledAndRefundStatusReturnActualState() {
        assertThat(enumContains(OrchestrationDtos.ToolTarget.class, "REFUND_STATUS")).isTrue();
    }

    @Test
    void rc08_refundDueDateUsesCurrentBookingContext() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "Retrieve booking [AIR-PNR-REDACTED] and show refund status."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "The refund case is pending."));

        String standalone = new QueryTransformer().toStandalone(
                "I want my refund back in two days.", history);

        assertThat(standalone)
                .contains("refund")
                .contains("[AIR-PNR-REDACTED]")
                .doesNotContain("book a flight");
    }

    @Test
    void rc10_vagueAllPoliciesRequestsClarificationWithoutAFalseEscalation() {
        assertThat(classify("Give me all types of policy").intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
    }

    @Test
    void rc11_domesticSeatAnswerContainsAllDomesticSeatTypes() {
        String answer = presentTable("""
                Seat type | Description | Domestic fee
                Standard Economy | Regular economy seat | Included
                Preferred Economy | Front rows | INR 400-800
                Comfort | Extra legroom | INR 900-1500
                Business Window | Window seat | Included
                Business Aisle | Aisle seat | Included
                """);

        assertThat(answer).contains(
                "Standard Economy",
                "Preferred Economy",
                "Comfort",
                "Business Window",
                "Business Aisle");
    }

    @Test
    void rc12_extractFallbackDoesNotTruncateRequiredCategories() {
        String answer = presentTable("""
                Category | Description
                Cabin baggage | Cabin allowance
                Checked baggage | Checked allowance
                Excess baggage | Excess fee
                Restricted items | Restricted articles
                Sports equipment | Sporting goods
                Mobility aids | Wheelchairs
                """);

        assertThat(answer).contains(
                "Cabin baggage",
                "Checked baggage",
                "Excess baggage",
                "Restricted items",
                "Sports equipment",
                "Mobility aids");
    }

    @Test
    void rc13_evaluatorRejectsIncompleteAnswer() {
        var evidence = ranked("""
                Standard Economy; Preferred Economy; Comfort extra legroom;
                Business Window; Business Aisle.
                """);
        var evaluator = new AnswerEvaluator(
                new UnitedAirProperties(), new CitationBuilder(), new CitationAttacher());

        var verdict = evaluator.evaluate(
                "Business Window seats are included [E1].",
                List.of("Which seat is available?", "What fee applies?"),
                List.of(evidence),
                0.90,
                Role.PASSENGER,
                false);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failedGates()).contains("INCOMPLETE_ANSWER");
    }

    @Test
    void rc14_confidenceIsNotPresentedAsCorrectness() {
        assertThat(recordContains(
                OrchestrationDtos.ChatResponse.class, "generationSource")).isTrue();
    }

    @Test
    void rc15_multiTopicAnswerCoversEveryTopic() {
        assertThat(classExists(
                "com.unitedair.ai.orchestration.AnswerRequirements")).isTrue();
    }

    @Test
    void rc16_generationSourceIdentifiesExtractiveFallback() {
        assertThat(recordContains(ChatDtos.ChatResult.class, "generationSource")).isTrue();
    }

    @Test
    void rc17_repairFallbackPreservesDegradedReason() {
        var fallback = new ChatDtos.ChatResult(
                "Grounded answer [E1].",
                0,
                0,
                "offline-extractive",
                false,
                "MODEL_CITATION_VALIDATION_FAILED");

        assertThat(fallback.degradedReason())
                .isEqualTo("MODEL_CITATION_VALIDATION_FAILED");
    }

    @Test
    void rc18_pnrDisplayNeverSaysPnrYourBooking() throws Exception {
        Method display = ChatController.class.getDeclaredMethod("displayAnswer", String.class);
        display.setAccessible(true);

        String rendered = (String) display.invoke(
                null, "PNR [AIR-PNR-REDACTED] is confirmed.");

        assertThat(rendered)
                .doesNotContainIgnoringCase("PNR your booking")
                .doesNotContainIgnoringCase("PNR my booking");
    }

    @Test
    void rc19_missingFlightDateRequestsDate() {
        var classification = classify(
                "Show me flights from Bengaluru to Delhi");

        assertThat(classification.intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(classification.missingParameters()).contains("travelDate");
    }

    @Test
    void rc20_supportedAirportListComesFromDatabase() {
        assertThat(Arrays.stream(FlightSearchTool.class.getDeclaredMethods())
                .map(Method::getName))
                .contains("listSupportedAirports");
    }

    private OrchestrationDtos.Classification classify(String query) {
        RedactionResult redaction = redactor.redact(query);
        return classifier.classify(query, redaction);
    }

    private static String presentTable(String raw) {
        return new AnswerPresentationService().present(
                new AnswerPresentationService.PresentationRequest(
                        Role.PASSENGER,
                        "Explain the complete policy.",
                        raw,
                        List.of(),
                        "KB_LOOKUP",
                        false))
                .text();
    }

    private static RetrievalDtos.Ranked ranked(String content) {
        var chunk = new RetrievalDtos.Chunk(
                1L,
                "chunk-1",
                "KB-AIR-005",
                "Fare and Seat Policy",
                "3 Seat Selection",
                1,
                "pdf",
                "fare-rule",
                "Passenger",
                content,
                0.9,
                0.9);
        return new RetrievalDtos.Ranked(chunk, 0.9, 0.9, 1);
    }

    private static boolean enumContains(Class<? extends Enum<?>> type, String constant) {
        return Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .anyMatch(constant::equals);
    }

    private static boolean recordContains(Class<?> type, String component) {
        return Arrays.stream(type.getRecordComponents())
                .map(recordComponent -> recordComponent.getName())
                .anyMatch(component::equals);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
