package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.unitedair.ai.llm.ChatDtos;
import org.junit.jupiter.api.Test;

class QueryTransformerTest {

    private final QueryTransformer transformer = new QueryTransformer();

    @Test
    void transformationReportsWhenHistoryWasNotUsed() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Show booking [AIR-PNR-REDACTED]."),
                new ChatDtos.HistoryTurn("ASSISTANT", "Your booking is confirmed."));
        var decision = new ContextRelevancePolicy().evaluate(
                "what is 2+2", history, null);

        var result = transformer.transform("what is 2+2", history, decision);

        assertThat(result.standalone()).isEqualTo("what is 2+2");
        assertThat(result.usedHistory()).isFalse();
        assertThat(result.supportingTurnIndexes()).isEmpty();
    }

    @Test
    void transformationReportsSupportingTurnForReferentialFollowUp() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Show booking [AIR-PNR-REDACTED]."),
                new ChatDtos.HistoryTurn("ASSISTANT", "Your booking is confirmed."));
        var decision = new ContextRelevancePolicy().evaluate(
                "is it refundable?", history, null);

        var result = transformer.transform("is it refundable?", history, decision);

        assertThat(result.standalone())
                .isEqualTo("Show booking [AIR-PNR-REDACTED]. is it refundable?");
        assertThat(result.usedHistory()).isTrue();
        assertThat(result.supportingTurnIndexes()).containsExactly(0);
    }

    @Test
    void pnrSlotAnswerCompletesTheCancellationRequestThatAskedForIt() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "I want to cancel my flight."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT",
                        "Please share the six-character booking reference (PNR)."));
        var decision = new ContextRelevancePolicy().evaluate(
                "[AIR-PNR-REDACTED]", history, "pnr");

        var result = transformer.transform(
                "[AIR-PNR-REDACTED]", history, decision);

        assertThat(result.standalone())
                .isEqualTo("I want to cancel my flight. [AIR-PNR-REDACTED]");
        assertThat(result.usedHistory()).isTrue();
        assertThat(result.supportingTurnIndexes()).containsExactly(0);
    }

    @Test
    void followUpUsesOnlyTheSuppliedSessionHistory() {
        var sessionA = List.of(
                new ChatDtos.HistoryTurn("USER", "What is the Economy baggage allowance?"),
                new ChatDtos.HistoryTurn("ASSISTANT", "It depends on the route."));

        assertThat(transformer.toStandalone("What about the limit?", sessionA))
                .contains("Economy")
                .doesNotContain("Business");
    }

    @Test
    void standaloneQuestionIsNotPollutedByHistory() {
        var history = List.of(new ChatDtos.HistoryTurn("USER", "Tell me about refunds."));

        assertThat(transformer.toStandalone(
                "What baggage is allowed in Economy class on domestic flights?", history))
                .isEqualTo("What baggage is allowed in Economy class on domestic flights?");
    }

    @Test
    void explicitCabinClassTopicStandsAloneAfterAFlightSelection() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "Show flights from Bangalore to Delhi tomorrow."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Three flights are available."),
                new ChatDtos.HistoryTurn(
                        "USER", "Which one is cheapest?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "The lowest fare is on UA101."));

        assertThat(transformer.toStandalone("What about business class?", history))
                .isEqualTo("What about business class?");
    }

    @Test
    void explicitCabinClassTopicDoesNotInheritAnIntermediateRefundQuestion() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "Show flights from Bangalore to Delhi tomorrow."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Three flights are available."),
                new ChatDtos.HistoryTurn(
                        "USER", "Is the cheapest one refundable?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "The cheapest fare is not refundable."));

        assertThat(transformer.toStandalone("What about business class?", history))
                .isEqualTo("What about business class?");
    }

    @Test
    void aLongFollowUpWithBareReferencesStillUsesPolicyContext() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "Explain overbooking limits and approval authority."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "The policy defines limits by route type."));

        assertThat(transformer.toStandalone(
                "Can we exceed that limit and who approves it?", history))
                .startsWith("Explain overbooking limits and approval authority.")
                .endsWith("Can we exceed that limit and who approves it?");
    }

    @Test
    void hyphenatedDutyTimeQuestionIsAContextAnchorForRestFollowUp() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "How should staff handle WCHR and MEDA?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Follow special-service procedures."),
                new ChatDtos.HistoryTurn(
                        "USER", "What are the DGCA CAR-7 flight-duty-time limits?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "The limits are in CAR-7."));

        assertThat(transformer.toStandalone(
                "And what mandatory rest is required?", history))
                .startsWith("What are the DGCA CAR-7 flight-duty-time limits?")
                .endsWith("And what mandatory rest is required?");
    }

    @Test
    void genericRelatedPolicySuggestionRetainsThePriorFlightContext() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "What flights are available from Bangalore to Delhi tomorrow?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Three departures are available."));

        assertThat(transformer.toStandalone(
                "Explain the related UnitedAir policy in more detail.", history))
                .startsWith("What flights are available from Bangalore to Delhi tomorrow?")
                .endsWith("Explain the related UnitedAir policy in more detail.");
    }

    @Test
    void pairedCitiesCompleteAPreviousFlightRouteClarification() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "What are the tomorrow flights?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Which origin and destination would you like?"));

        assertThat(transformer.toStandalone("London and Dubai", history))
                .contains("What are the tomorrow flights?")
                .contains("from London to Dubai");
    }

    @Test
    void labelledRouteCompletesThePreviousDateBearingFlightRequest() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Show me flights tomorrow."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Which origin and destination would you like?"));

        assertThat(transformer.toStandalone("From Bangalore to Delhi.", history))
                .startsWith("Show me flights tomorrow.")
                .endsWith("From Bangalore to Delhi.");
    }

    @Test
    void unlabelledCityPairKeepsThePreviousTravelDate() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Need flight options for tomorrow."),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Which origin and destination?"));

        assertThat(transformer.toStandalone("Madras to Hyderabad.", history))
                .startsWith("Need flight options for tomorrow.")
                .endsWith("Madras to Hyderabad.");
    }

    @Test
    void evenIfFollowupKeepsTheNoShowPolicyQuestion() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "I missed the first leg. Will you keep my return sector?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "The no-show policy applies."));

        assertThat(transformer.toStandalone(
                "Even if I call only after departure?", history))
                .startsWith("I missed the first leg.")
                .endsWith("Even if I call only after departure?");
    }

    @Test
    void laterRouteFollowUpsRetainTheEarlierDateClarification() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Show me flights tomorrow."),
                new ChatDtos.HistoryTurn("ASSISTANT", "Which route?"),
                new ChatDtos.HistoryTurn("USER", "From Bangalore to Delhi."),
                new ChatDtos.HistoryTurn("ASSISTANT", "Three flights are available."));

        assertThat(transformer.toStandalone("Which one leaves in the evening?", history))
                .startsWith("Show me flights tomorrow. From Bangalore to Delhi.")
                .endsWith("Which one leaves in the evening?");
    }

    @Test
    void standaloneVagueFailureIsNotPollutedByAnOlderSensitiveTurn() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "My refund was denied."),
                new ChatDtos.HistoryTurn("ASSISTANT", "That was referred."));

        assertThat(transformer.toStandalone("Something went wrong.", history))
                .isEqualTo("Something went wrong.");
    }

    @Test
    void anAndPrefixedPolicyFragmentKeepsThePreviousSubject() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "What is the cabin baggage allowance in domestic Economy?"),
                new ChatDtos.HistoryTurn("ASSISTANT", "Cabin baggage is 7 kg."));

        assertThat(transformer.toStandalone("And checked baggage too?", history))
                .startsWith("What is the cabin baggage allowance in domestic Economy?")
                .endsWith("And checked baggage too?");
    }

    @Test
    void internationalFollowUpUsesTheLatestCheckInAndIdentificationContext() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "And checked baggage too?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "The checked allowance depends on the fare."),
                new ChatDtos.HistoryTurn(
                        "USER", "When does check-in open, and what identification "
                                + "do I need for a domestic flight?"),
                new ChatDtos.HistoryTurn(
                        "ASSISTANT", "Domestic check-in and ID requirements apply."));

        assertThat(transformer.toStandalone("What about an international trip?", history))
                .startsWith("When does check-in open, and what identification")
                .doesNotContain("checked baggage")
                .endsWith("What about an international trip?");
    }

    @Test
    void sameTripDateChangeKeepsTheRouteContext() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Need flight options for tomorrow."),
                new ChatDtos.HistoryTurn("ASSISTANT", "Which route?"),
                new ChatDtos.HistoryTurn("USER", "Madras to Hyderabad."),
                new ChatDtos.HistoryTurn("ASSISTANT", "One flight is available."));

        assertThat(transformer.toStandalone("Same trip, but two days from now.", history))
                .startsWith("Madras to Hyderabad.")
                .endsWith("Same trip, but two days from now.");
    }

    @Test
    void gateQuestionKeepsThePriorFlightNumber() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Is UA102 running on time tomorrow?"),
                new ChatDtos.HistoryTurn("ASSISTANT", "UA102 is delayed."));

        assertThat(transformer.toStandalone("Which gate should I go to?", history))
                .startsWith("Is UA102 running on time tomorrow?")
                .endsWith("Which gate should I go to?");
    }

    @Test
    void cabinQuestionAfterBookingLookupKeepsTheBookingContext() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Pull up PNR [AIR-PNR-REDACTED], please."),
                new ChatDtos.HistoryTurn("ASSISTANT", "Your booking is confirmed."));

        assertThat(transformer.toStandalone("What cabin did I buy?", history))
                .contains("[AIR-PNR-REDACTED]")
                .endsWith("What cabin did I buy?");
    }

    @Test
    void possessiveRefundFollowUpKeepsTheBookingContext() {
        var history = List.of(
                new ChatDtos.HistoryTurn("USER", "Find [AIR-PNR-REDACTED]."),
                new ChatDtos.HistoryTurn("ASSISTANT", "Your booking is confirmed."));

        assertThat(transformer.toStandalone("Does its fare allow a refund?", history))
                .contains("[AIR-PNR-REDACTED]")
                .endsWith("Does its fare allow a refund?");
    }

    @Test
    void oversoldPolicyIsAContextAnchorForAuthorizationFollowUp() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "Operationally, what must we do when a flight is oversold "
                                + "and volunteers are insufficient?"),
                new ChatDtos.HistoryTurn("ASSISTANT", "Follow the overbooking policy."));

        assertThat(transformer.toStandalone(
                "Who may authorize exceeding those limits?", history))
                .startsWith("Operationally, what must we do when a flight is oversold")
                .endsWith("Who may authorize exceeding those limits?");
    }

    @Test
    void secondFollowUpKeepsBothTheRouteAndTheIntermediateSelection() {
        var history = List.of(
                new ChatDtos.HistoryTurn(
                        "USER", "Find a flight from Delhi to Bengaluru tomorrow."),
                new ChatDtos.HistoryTurn("ASSISTANT", "Two flights are available."),
                new ChatDtos.HistoryTurn(
                        "USER", "Which one is the later departure?"),
                new ChatDtos.HistoryTurn("ASSISTANT", "UA105 is later."));

        assertThat(transformer.toStandalone(
                "Are there business seats on that one?", history))
                .startsWith("Find a flight from Delhi to Bengaluru tomorrow.")
                .contains("Which one is the later departure?")
                .endsWith("Are there business seats on that one?");
    }
}
