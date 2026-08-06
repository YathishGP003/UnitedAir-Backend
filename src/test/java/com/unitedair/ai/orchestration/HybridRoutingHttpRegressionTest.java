package com.unitedair.ai.orchestration;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import com.unitedair.ai.privacy.PiiRedactor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Public-chat routing regressions for failures originally observed through the
 * synchronous HTTP endpoint. The live smoke suite exercises the controller itself;
 * these tests keep the routing contract fast and deterministic on every Maven run.
 */
class HybridRoutingHttpRegressionTest {

    private final IntentClassifier classifier = new IntentClassifier();
    private final PiiRedactor redactor = new PiiRedactor();
    private final ContextRelevancePolicy contextPolicy = new ContextRelevancePolicy();
    private final ConversationResponder responder =
            new ConversationResponder(mock(ChatGateway.class));

    @Test
    void unrelatedQuestionsCannotInheritBookingOrBaggageContext() {
        List<ChatDtos.HistoryTurn> history = List.of(
                new ChatDtos.HistoryTurn("USER",
                        "Show booking [AIR-PNR-REDACTED]"),
                new ChatDtos.HistoryTurn("ASSISTANT",
                        "Your booking is UA102 and baggage has a flat charge."));

        for (String question : List.of("what is 2+2", "why earth is flat?")) {
            var decision = contextPolicy.evaluate(question, history, null);
            var route = classify(question, Role.PASSENGER);

            assertThat(decision.useHistory()).isFalse();
            assertThat(route.intent()).isEqualTo(OrchestrationDtos.Intent.OUT_OF_SCOPE);
            assertThat(route.needsTool()).isFalse();
            var response = responder.respond(
                    route.intent(), question, history, route.missingParameters());
            assertThat(response.text()).startsWith("I can only help with UnitedAir");
            assertThat(response.followups()).isEmpty();
        }
    }

    @Test
    void conversationalVariantsStayConversational() {
        for (String question : List.of(
                "sup", "what are you doing?", "hiiiiiii", "thank you")) {
            var route = classify(question, Role.PASSENGER);
            assertThat(route.intent()).isEqualTo(OrchestrationDtos.Intent.SMALL_TALK);
            assertThat(route.needsTool()).isFalse();
        }
    }

    @Test
    void cancellationThenBarePnrPreservesOnlyThePendingSlot() {
        var cancellation = classify("cancel my flight", Role.PASSENGER);
        assertThat(cancellation.intent())
                .isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(cancellation.missingParameters()).containsExactly("pnr");

        List<ChatDtos.HistoryTurn> history = List.of(
                new ChatDtos.HistoryTurn("USER", "cancel my flight"),
                new ChatDtos.HistoryTurn("ASSISTANT",
                        "Please share the six-character booking reference."));
        assertThat(contextPolicy.evaluate("X2LTWZ", history, "PNR").useHistory())
                .isTrue();
    }

    @Test
    void refundSettlementAndCompoundStaffOperationsUseLiveDataPaths() {
        assertThat(classify(
                "status of teh refund of the pnr X2LTWZ",
                Role.PASSENGER).tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.REFUND_STATUS);
        assertThat(classify(
                "pending refunds and open escalations",
                Role.AIRLINE_STAFF).tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY);
    }

    @Test
    void bookingAndFlightSearchLanguageDoesNotFallIntoPolicyRetrieval() {
        var booking = classify(
                "book flight from Chennai to Goa today",
                Role.PASSENGER);
        assertThat(booking.intent()).isEqualTo(OrchestrationDtos.Intent.BOOK_FLIGHT);
        assertThat(booking.tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.BOOKING_CREATE);
        assertThat(booking.origin()).isEqualTo("Chennai");
        assertThat(booking.destination()).isEqualTo("Goa");
    }

    private OrchestrationDtos.Classification classify(String query, Role role) {
        return classifier.classify(query, redactor.redact(query), role);
    }
}
