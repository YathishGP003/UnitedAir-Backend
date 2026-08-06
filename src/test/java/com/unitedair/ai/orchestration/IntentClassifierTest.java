package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.unitedair.ai.privacy.PiiRedactor;
import com.unitedair.ai.privacy.PiiType;
import com.unitedair.ai.privacy.RedactionResult;
import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SRS 2.2 Routing pattern. */
class IntentClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Show flights tomorrow from Bangalore to Delhi. "
                    + "Which one leaves in the evening? Is the cheapest one refundable?",
            "Show flights tomorrow from Bangalore to Delhi. "
                    + "Is the cheapest one refundable? What about business class?"
    })
    void contextualFareFollowupsNeverBecomeGenericOperationalDataQueries(String query) {
        var result = classify(query);

        assertThat(result.tool())
                .isNotEqualTo(OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY);
        assertThat(result.intent())
                .isIn(OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.Intent.TOOL_PLUS_KB);
    }

    @Test
    void explicitBusinessClassQuestionTargetsFareClassEvidence() {
        var result = classify("What about business class?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.documentCodeHints()).contains("KB-AIR-005");
    }

    @Test
    void naturalChildTravellingAloneQuestionTargetsUnaccompaniedMinorPolicy() {
        var result = classify(
                "My ten-year-old is travelling alone. Which service and documents are required?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.documentCodeHints())
                .contains("KB-AIR-006")
                .doesNotContain("KB-AIR-002");
    }

    @Test
    void missedFirstLegAndReturnSectorTargetsNoShowPolicy() {
        var result = classify(
                "I missed the first leg. Will the airline still keep my return sector?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.documentCodeHints()).contains("KB-AIR-004");
    }

    @Test
    void explicitPnrRefundArithmeticUsesTheFixedRefundQuoteTool() {
        var result = classify(
                "For booking B6X9K2, show amount paid, cancellation fee "
                        + "and estimated refund.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.REFUND_QUOTE);
    }

    @Test
    void explicitTicketPurchaseUsesBookingCreateRoute() {
        var result = classifier.classify(
                "Please book a ticket from Bangalore to Delhi tomorrow",
                RedactionResult.unchanged(
                        "Please book a ticket from Bangalore to Delhi tomorrow"));

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.BOOK_FLIGHT);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.BOOKING_CREATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "what is cancellation policy for domestic flights",
            "If I cancel a Value fare more than 7 days before departure, what fee applies and how much do I get back?",
            "Explain the domestic refund and cancellation rules"
    })
    void cancellationPolicyWithoutAPnrStaysInGroundedPolicyQa(String query) {
        var result = classify(query);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.documentCodeHints()).contains("KB-AIR-004");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I want to cancel my flight",
            "Please cancel my booking",
            "Cancel this booking"
    })
    void cancellationActionWithoutAResolvedBookingAsksForThePnr(String query) {
        var result = classify(query);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.missingParameters()).containsExactly("pnr");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Show my current booking without asking me for the PNR.",
            "Pull up my upcoming booking.",
            "What are the details of my active reservation?"
    })
    void personalBookingLookupWithoutAResolvedBookingAsksWhichPnr(String query) {
        var result = classify(query);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
        assertThat(result.missingParameters()).containsExactly("pnr");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I want to book tickets",
            "Book me a flight from Bengaluru to Delhi tomorrow",
            "I would like to book a ticket"
    })
    void naturalPurchaseRequestsStartTheBookingWorkflow(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.BOOK_FLIGHT);
    }

    private final IntentClassifier classifier = new IntentClassifier();

    @Test
    void routesARebookingRequestForADisruptedBookingToRecovery() {
        RedactionResult redaction = new RedactionResult(
                "Find alternatives for [AIR-PNR-REDACTED] because my flight was cancelled.",
                java.util.Map.of(PiiType.PNR, java.util.List.of("B6X9K2")),
                1);

        var result = classifier.classify(
                "Find alternatives for B6X9K2 because my flight was cancelled.", redaction);

        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.DISRUPTION_RECOVERY);
    }

    @Test
    void routesFr026ToTheNewApprovedBaggageDocument() {
        var result = classify(
                "What are the WorldTracer, PIR and Montreal Convention rules for mishandled baggage?",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.documentCodeHints()).contains("KB-AIR-009");
    }

    @Test
    void routesCrossBorderGuidanceToTheApprovedJurisdictionDocument() {
        var result = classify(
                "What cross-border passenger protection sources apply to an India international route?",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.documentCodeHints()).contains("KB-AIR-010");
    }

    @Test
    void legalInterpretationBeyondApprovedTextEscalates() {
        var result = classify(
                "The rules conflict. Which law wins and where can the passenger take the airline to court?",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.ESCALATION);
        assertThat(result.escalationReason()).isEqualTo("REGULATORY_INTERPRETATION");
    }

    @Test
    void datedFlightMealQuestionUsesVerifiedAvailabilityTool() {
        var result = classify("Is VGML available on UA404 on 2026-08-17?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.MEAL_AVAILABILITY);
        assertThat(result.mealCode()).isEqualTo("VGML");
        assertThat(result.travelDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 17));
        assertThat(result.documentCodeHints()).contains("KB-AIR-006");
    }

    @Test
    void excessBaggageQuestionExtractsAllApprovedTariffDimensions() {
        var result = classify(
                "How much is 5 kg excess baggage on a domestic economy flight?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.EXCESS_BAGGAGE_QUOTE);
        assertThat(result.excessBaggageKg()).isEqualTo(5);
        assertThat(result.routeType()).isEqualTo("DOMESTIC");
        assertThat(result.cabin()).isEqualTo("Economy");
        assertThat(result.documentCodeHints()).contains("KB-AIR-003");
    }

    @Test
    void incompleteExcessBaggageQuoteAsksForTariffDimensions() {
        var result = classify("What will my extra baggage cost?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.EXCESS_BAGGAGE_QUOTE);
        assertThat(result.missingParameters())
                .contains("excessBaggageKg", "routeType", "cabin");
    }

    @Test
    void multipartBaggagePolicyDoesNotBecomeAnIncompleteTariffQuote() {
        var result = classify(
                "For domestic Economy, explain the cabin and checked baggage allowances, "
                        + "what happens with excess baggage, and whether a power bank "
                        + "can go in checked baggage.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.documentCodeHints()).contains("KB-AIR-003");
    }

    @Test
    void domesticEconomyTicketDoesNotMatchThePersonalMyTicketPhrase() {
        var result = classify(
                "What is the cabin and checked baggage allowance "
                        + "on a domestic Economy ticket?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.missingParameters()).doesNotContain("pnr");
        assertThat(result.documentCodeHints()).contains("KB-AIR-003");
    }

    @Test
    void staffRefundApprovalAuditUsesDeterministicDecisionPath() {
        var result = classify(
                "Show approved refund approvals from today",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
        assertThat(result.categoryHints())
                .contains("decision:REFUND_APPROVAL", "outcome:APPROVED");
    }

    @Test
    void namedApprovalAndExceptionAuditDoesNotUseTheGenericDataAgent() {
        var result = classify(
                "Show the audit requirements for refund approvals, upgrade "
                        + "authorizations, boarding overrides and special-service "
                        + "exceptions.",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS);
        assertThat(result.categoryHints()).containsExactlyInAnyOrder(
                "decision:REFUND_APPROVAL",
                "decision:UPGRADE_AUTHORIZATION",
                "decision:BOARDING_OVERRIDE",
                "decision:SPECIAL_SERVICE_EXCEPTION");
    }

    @Test
    void passengerCannotSearchInternalOperationalDecisions() {
        var result = classify("Show refund approvals from today", Role.PASSENGER);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.OUT_OF_SCOPE);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
    }
    private final PiiRedactor redactor = new PiiRedactor();

    private OrchestrationDtos.Classification classify(String query) {
        return classify(query, Role.PASSENGER);
    }

    private OrchestrationDtos.Classification classify(String query, Role role) {
        RedactionResult redaction = redactor.redact(query);
        return classifier.classify(query, redaction, role);
    }

    @Test
    void staffRefundQueueWordingUsesTheOperationalCaseTarget() {
        var result = classify(
                "Show me refund cases for passengers",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_CASES);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Are there any pending refunds?",
            "Show pending refund requests.",
            "Which refunds are still pending?"
    })
    void naturalStaffPendingRefundQuestionsUseTheRefundQueue(String query) {
        var result = classify(query, Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_CASES);
        assertThat(result.categoryHints()).contains("status:PENDING");
    }

    @Test
    void staffCompoundOperationalQuestionUsesTheGovernedDataAgent() {
        var result = classify(
                "Which pending refunds and open escalations need attention?",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.OPERATIONAL_DATA_QUERY);
    }

    @Test
    void passengerRefundStatusFollowUpRetainsTheRefundTargetWhileAskingForPnr() {
        var result = classify("Show me refund status for above", Role.PASSENGER);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_STATUS);
        assertThat(result.missingParameters()).containsExactly("pnr");
    }

    @Test
    void genericBookingReferenceStatusAsksForThePnrInsteadOfSearchingPolicy() {
        var result = classify(
                "what is the status on the booking reference",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
        assertThat(result.missingParameters()).containsExactly("pnr");
    }

    @Test
    void bookingReferenceIdentifiesARefundInsteadOfCreatingACompoundDataQuery() {
        var result = classify(
                "what is the status on booking reference K2MN7V refund status",
                Role.AIRLINE_STAFF);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(
                OrchestrationDtos.ToolTarget.REFUND_STATUS);
    }

    @Test
    void misspelledRefundStatusStillUsesTheRefundTarget() {
        assertThat(classify("refnd status", Role.PASSENGER).tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.REFUND_STATUS);
    }

    @Test
    void passengerCannotListOtherPassengersRefundCases() {
        var result = classify("Show refund cases", Role.PASSENGER);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.OUT_OF_SCOPE);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
    }

    @Test
    void genericStatusWithoutFlightLanguageNeverRoutesToFlightStatus() {
        assertThat(classify("What is the status?", Role.PASSENGER).tool())
                .isNotEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_STATUS);
    }

    @Test
    @DisplayName("a fraud report escalates before anything else is considered")
    void fraudEscalatesOutright() {
        // No amount of good retrieval makes answering from a policy document the right
        // response to this sentence.
        var result = classify("Someone used my card fraudulently to book a ticket");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.ESCALATION);
        assertThat(result.escalationReason()).isEqualTo("FRAUD_ALLEGATION");
    }

    @Test
    void refundDenialGoesToEscalation() {
        assertThat(classify("My refund was denied and I want it reviewed").intent())
                .isEqualTo(OrchestrationDtos.Intent.ESCALATION);
    }

    @Test
    @DisplayName("a PNR plus a refund question needs both the tool and the fee matrix")
    void pnrWithRefundRoutesToToolPlusKb() {
        var result = classify("How much will I get back if I cancel B6X9K2?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.pnr()).isEqualTo("B6X9K2");
        assertThat(result.categoryHints()).contains("fare-rule");
    }

    @Test
    void pnrWithCheckInRoutesToCheckIn() {
        var result = classify("Can I check in for B6X9K2 now?");

        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.CHECK_IN);
        assertThat(result.pnr()).isEqualTo("B6X9K2");
    }

    @Test
    void checkInTimingAndIdentificationWithoutAPnrRoutesToPolicy() {
        var result = classify(
                "When does check-in open, and what identification do I need for a domestic flight?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.categoryHints()).contains("sop");
        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-002");
    }

    @Test
    void flightNumberWithStatusRoutesToFlightStatus() {
        var result = classify("Is UA102 delayed today?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_STATUS);
        assertThat(result.flightNo()).isEqualTo("UA102");
    }

    @Test
    @DisplayName("a route in the question triggers a flight search")
    void routeTriggersSearch() {
        var result = classify("Show me flights from Bengaluru to Delhi on 2026-08-14");

        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
        assertThat(result.origin()).isEqualToIgnoringCase("Bengaluru");
        assertThat(result.destination()).isEqualToIgnoringCase("Delhi");
        assertThat(result.travelDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 14));
    }

    @Test
    void extractsAFlightRouteWhenForIntroducesTheRelativeDate() {
        var result = classify(
                "Search UnitedAir flights from Bengaluru to New Delhi for tomorrow in Economy.");

        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
        assertThat(result.origin()).isEqualToIgnoringCase("Bengaluru");
        assertThat(result.destination()).isEqualToIgnoringCase("New Delhi");
        assertThat(result.travelDate()).isEqualTo(java.time.LocalDate.now().plusDays(1));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "'I want to know about flight from Bangalore to Delhi tomorrow',Bangalore,Delhi",
            "'Flight from Bangalore to Delhi tomorrow',Bangalore,Delhi",
            "'Delhi to Bangalore tomorrow',Delhi,Bangalore",
            "'I want to fly to Delhi from Bangalore tomorrow',Bangalore,Delhi"
    })
    void parsesNaturalRoutesWithoutReversingOrCapturingQuestionLead(
            String query, String expectedOrigin, String expectedDestination) {
        var result = classify(query);

        assertThat(result.origin()).isEqualToIgnoringCase(expectedOrigin);
        assertThat(result.destination()).isEqualToIgnoringCase(expectedDestination);
    }

    @Test
    void routeWithoutDateRequestsTheMissingTravelDateBeforeToolDispatch() {
        var result = classify("Show me flights from Bengaluru to Delhi");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
        assertThat(result.missingParameters()).containsExactly("travelDate");
        assertThat(result.travelDate()).isNull();
    }

    @Test
    void naturalCityToCityRouteTriggersSearchWithoutTheWordFrom() {
        var result = classify("London to Dubai tomorrow?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
        assertThat(result.origin()).isEqualToIgnoringCase("London");
        assertThat(result.destination()).isEqualToIgnoringCase("Dubai");
        assertThat(result.travelDate()).isEqualTo(java.time.LocalDate.now().plusDays(1));
    }

    @Test
    void understandsDestinationBeforeOriginInNaturalSpeech() {
        var result = classify("Can I get down to Delhi from Bangalore tomorrow?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
        assertThat(result.origin()).isEqualToIgnoringCase("Bangalore");
        assertThat(result.destination()).isEqualToIgnoringCase("Delhi");
    }

    @Test
    @DisplayName("'from Monday to Friday' is not a route")
    void doesNotMistakeDayNamesForAirports() {
        assertThat(classify("What are the rules from Monday to Friday?").origin()).isNull();
    }

    @Test
    @DisplayName("CAR-7 is a regulation reference, not a flight number")
    void doesNotMistakeRegulationForFlightNumber() {
        var result = classify("What are the DGCA CAR-7 duty time limits?");

        assertThat(result.flightNo()).isNull();
        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.categoryHints()).contains("regulatory-circular");
    }

    @Test
    @DisplayName("a plain policy question goes to the Knowledge Base")
    void policyQuestionsGoToKb() {
        var result = classify("What is the cabin baggage allowance on domestic flights?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
    }

    @Test
    void resolvesRelativeDates() {
        assertThat(classify("Flights from BLR to DEL tomorrow").travelDate())
                .isEqualTo(java.time.LocalDate.now().plusDays(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hi", "hello there", "thank you", "thanks", "goodbye", "what can you do?"
    })
    void routesConversationToSmallTalk(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.SMALL_TALK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What can you help me with?",
            "Thanks, that sounds useful."
    })
    void routesNaturalCapabilityAndThanksPhrasesToSmallTalk(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.SMALL_TALK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Good morning there.", "Cheers, got it."
    })
    void routesNaturalGreetingAndAcknowledgementVariantsToSmallTalk(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.SMALL_TALK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hii", "hiii", "heyy", "helloo",
            "sup", "wassup", "yo", "what's up?", "how's it going?",
            "what are you doing?", "what are you doping?"
    })
    void routesRepeatedLetterGreetingVariantsToSmallTalk(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.SMALL_TALK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "can u help pls", "Book me something.", "My trip has a problem."
    })
    void clarifiesNaturalRequestsThatHaveNoSafelyIdentifiableWorkflow(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I need a human agent now.",
            "A passenger is having a severe allergic reaction onboard right now.",
            "A stolen card was used to pay for my booking."
    })
    void escalatesNaturalHumanSafetyAndFraudReports(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.ESCALATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Tell me a joke about clouds.", "What is nine plus thirteen?"
    })
    void rejectsNaturalNonAirlineRequestsWithoutSearchingPolicy(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.OUT_OF_SCOPE);
    }

    @Test
    void understandsCommonFlightSearchShorthandAndTypos() {
        var result = classify("blr 2 dubai tmrw pls");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
        assertThat(result.origin()).isEqualTo("BLR");
        assertThat(result.destination()).isEqualToIgnoringCase("dubai");
        assertThat(result.travelDate()).isEqualTo(java.time.LocalDate.now().plusDays(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Operationally, what must we do when a flight is oversold and volunteers are insufficient?"
    })
    void naturalPolicyLanguageDoesNotBecomeAFlightSearch(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
    }

    @Test
    void specificPregnancyQuestionEscalatesBecauseTheApprovedKbHasNoPregnancyRules() {
        var result = classify("Can someone who is 32 weeks pregnant with twins fly?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.ESCALATION);
        assertThat(result.escalationReason()).isEqualTo("PREGNANCY_POLICY_GAP");
    }

    @Test
    void bumpsLanguageMapsToDeniedBoardingPolicy() {
        var result = classify(
                "What happens when the airline bumps me although I hold a confirmed ticket?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.8);
        assertThat(result.documentCodeHints())
                .contains("KB-AIR-002", "KB-AIR-004");
    }

    @Test
    void wheelchairCheckInQuestionUsesSpecialServicesInsteadOfGenericCheckInPolicy() {
        var result = classify(
                "I need wheelchair help from check-in to the aircraft. "
                        + "Which SSR code and request deadline apply?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-006");
    }

    @Test
    void naturalPetLanguageUsesTheSpecialServicesDocument() {
        assertThat(classify(
                "Can my cat ride in the cabin and can a large dog travel in the hold?")
                .documentCodeHints()).containsExactly("KB-AIR-006");
    }

    @Test
    void luggageLimitSynonymsUseTheBaggageDocumentWithHighConfidence() {
        var result = classify(
                "For Economy inside India, tell me cabin and checked luggage limits together.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.8);
        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-003");
    }

    @Test
    void refundStateQuestionUsesTheReadOnlyRefundStatusOperation() {
        var result = classify("Retrieve K2MN7V and tell me its refund state.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_STATUS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "what is the status of the refund for PNR K2MN7V",
            "where is my refund for K2MN7V",
            "has the refund for K2MN7V been processed",
            "did I get a refund for my flight PNR K2MN7V",
            "was booking K2MN7V refunded",
            "refund progress K2MN7V",
            "status of teh refund of the pnr K2MN7V"
    })
    void naturalRefundSettlementQuestionsUsePersistedStatus(String query) {
        var result = classify(query);

        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_STATUS);
    }

    @Test
    void genericCancellationActionAsksForPnrInsteadOfReturningPolicyRows() {
        var result = classify("i want to cancel flight");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.missingParameters()).containsExactly("pnr");
    }

    @Test
    void introductionRequestIsSmallTalkAndBrieflyDoesNotTriggerTheFlyKeyword() {
        var result = classify("Hello, can you introduce yourself briefly?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.SMALL_TALK);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
    }

    @Test
    void medaDocumentQuestionPrefersTheSpecialServicesPolicy() {
        var result = classify("What documents are needed for MEDA medical clearance?");

        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-006");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I have severe chest pain on board right now.",
            "A passenger is unconscious and cannot breathe.",
            "There is a bomb threat on this flight."
    })
    void urgentMedicalAndSecurityReportsEscalateImmediately(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.ESCALATION);
    }

    @Test
    void requestToCarryBombAndKnifeUsesGroundedSafetyPolicy() {
        var result = classify("can i bring bomb and knife to the airport?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.escalationReason()).isEqualTo("SAFETY_POLICY");
        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-003");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "can i bring weapons and knife to the flight?",
            "can I carry bombs onto the plane?",
            "may I take explosive devices onboard?"
    })
    void pluralUnsafeTransportRequestsUseGroundedSafetyPolicy(String query) {
        var result = classify(query);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.escalationReason()).isEqualTo("SAFETY_POLICY");
        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-003");
    }

    @Test
    void ordinaryFireworksPolicyQuestionStillUsesTheKnowledgeBase() {
        assertThat(classify("Are fireworks prohibited in checked baggage?").intent())
                .isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "solve 22 * 19", "write Python code for a calculator",
            "who won the election?", "explain photosynthesis",
            "what is 2+2", "why earth is flat?", "what does terminal mean in Linux?",
            "what is a class in Java?", "what is a logic gate?"
    })
    void routesUnrelatedRequestsOutOfScope(String query) {
        assertThat(classify(query).intent()).isEqualTo(OrchestrationDtos.Intent.OUT_OF_SCOPE);
    }

    @Test
    void mixedTypoMathQuestionRemainsOutOfScopeWithoutKbOrEscalation() {
        var result = classify("wha is trignomerty 2+2");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.OUT_OF_SCOPE);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
    }

    @Test
    void asksForBothAirportsWhenAFlightRequestHasNoRoute() {
        var result = classify("Show me flights tomorrow");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.missingParameters()).containsExactly("origin", "destination");
    }

    @Test
    void asksOnlyForTheMissingOriginWhenDestinationIsPresent() {
        var result = classify("Are there flights to Delhi tomorrow?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.destination()).isEqualToIgnoringCase("Delhi");
        assertThat(result.missingParameters()).containsExactly("origin");
    }

    @Test
    void aBareHelpRequestAsksTheUserToChooseAWorkflow() {
        var result = classify("Help.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.escalationReason()).isNull();
    }

    @Test
    void routeFragmentUsesTheEarlierRelativeDateAfterRewrite() {
        var result = classify(
                "Need flight options for tomorrow. Madras to Hyderabad.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.origin()).isEqualToIgnoringCase("Madras");
        assertThat(result.destination()).isEqualToIgnoringCase("Hyderabad");
        assertThat(result.travelDate()).isEqualTo(java.time.LocalDate.now().plusDays(1));
    }

    @Test
    void relativeDaysFromNowProducesAnOperationalTravelDate() {
        var result = classify(
                "Madras to Hyderabad. Same trip, but two days from now.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.travelDate()).isEqualTo(java.time.LocalDate.now().plusDays(2));
    }

    @Test
    void aVagueFailureClarifiesInsteadOfSelectingANearbyPolicy() {
        var result = classify("Something went wrong.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.escalationReason()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me all types of policy",
            "Tell me everything",
            "I have an issue"
    })
    void genuinelyAmbiguousRequestsClarifyWithoutRetrieval(String query) {
        var result = classify(query);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
    }

    @Test
    void accountPasswordHelpDoesNotSearchPolicyOrCreateAnEscalation() {
        var result = classify("I forgot my account password.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.missingParameters()).containsExactly("account");
    }

    @Test
    void noShowWordingIsPolicyEvenWhenItContainsTheWordFlight() {
        assertThat(classify("What happens if I am a no-show on the outbound flight?").intent())
                .isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
    }

    @Test
    void contextualRouteFollowUpRetainsTheFlightSearchTool() {
        var result = classify(
                "Flights from Bangalore to Delhi tomorrow. Which one leaves in the evening?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_SEARCH);
        assertThat(result.origin()).isEqualToIgnoringCase("Bangalore");
        assertThat(result.destination()).isEqualToIgnoringCase("Delhi");
    }

    @Test
    void contextualFlightNumberRetainsTheStatusTool() {
        var result = classify("What is the status of UA101 tomorrow? And its gate?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_STATUS);
        assertThat(result.flightNo()).isEqualTo("UA101");
    }

    @Test
    void aRedactedPriorPnrPromptsForThePnrAgainBeforeARefundToolCall() {
        var result = classify(
                "Find my booking [AIR-PNR-REDACTED]. Is it refundable?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.CLARIFICATION);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.missingParameters()).containsExactly("pnr");
    }

    @Test
    void aSafelyResolvedSessionBookingSupportsARefundabilityFollowUp() {
        RedactionResult contextual = new RedactionResult(
                "Find my booking [AIR-PNR-REDACTED]. Is it refundable?",
                Map.of(PiiType.PNR, List.of("B6X9K2")),
                1);

        var result = classifier.classify(contextual.redacted(), contextual);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.pnr()).isEqualTo("B6X9K2");
        assertThat(result.missingParameters()).isEmpty();
    }

    @Test
    void aSafelyResolvedSessionBookingSupportsCancelIt() {
        RedactionResult contextual = new RedactionResult(
                "Retrieve booking [AIR-PNR-REDACTED]. Cancel it.",
                Map.of(PiiType.PNR, List.of("B6X9K2")),
                1);

        var result = classifier.classify(contextual.redacted(), contextual);

        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.REFUND_QUOTE);
        assertThat(result.pnr()).isEqualTo("B6X9K2");
    }

    @Test
    void aResolvedBookingFlightSupportsAnOnTimeFollowUp() {
        RedactionResult contextual = new RedactionResult(
                "Is that flight on time? UA404 on 2026-08-17",
                Map.of(PiiType.PNR, List.of("H3PL8M")),
                1);

        var result = classifier.classify(contextual.redacted(), contextual);

        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.FLIGHT_STATUS);
        assertThat(result.flightNo()).isEqualTo("UA404");
        assertThat(result.travelDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 17));
    }

    @Test
    void naturalCheckMeInWordingUsesTheCheckInTool() {
        assertThat(classify("Can you check me in for T7QW4Z?").tool())
                .isEqualTo(OrchestrationDtos.ToolTarget.CHECK_IN);
    }

    @Test
    void seatMapByFlightNumberUsesTheSeatMapTool() {
        var result = classify("Show the seat map for UA101 tomorrow.");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_CALL);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.SEAT_MAP);
        assertThat(result.flightNo()).isEqualTo("UA101");
    }

    @Test
    void seatProductAvailabilityIsPolicyNotFlightSearch() {
        var result = classify("What seat types, selection fees and upgrade paths are available?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-005");
    }

    @Test
    void combinedSeatAndSpecialServiceQuestionRetrievesBothPolicyDomains() {
        var result = classify(
                "What special services are provided and what are the seat selection fares?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-005", "KB-AIR-006");
    }

    @Test
    void combinedPolicyQuestionWithPnrAlsoRetrievesTheOwnedBooking() {
        var result = classify(
                "For PNR B6X9K2, what special services are available "
                        + "and what are the seat selection fares?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.TOOL_PLUS_KB);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.BOOKING_LOOKUP);
        assertThat(result.pnr()).isEqualTo("B6X9K2");
        assertThat(result.documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-005", "KB-AIR-006");
    }

    @Test
    void frequentFlyerAvailabilityIsPolicyNotFlightSearch() {
        var result = classify("How do frequent-flyer enrolment, accrual, tiers and redemption work?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-006");
    }

    @Test
    void addsExactDocumentHintsForKnownPolicyDomains() {
        assertThat(classify("What are the checked baggage limits and restricted-item rules?")
                .documentCodeHints()).containsExactly("KB-AIR-003");
        assertThat(classify("Which travel documents are needed for domestic and international journeys?")
                .documentCodeHints()).containsExactly("KB-AIR-002");
        assertThat(classify("How should staff handle WCHR, UM, PETC, AVIH and MEDA?")
                .documentCodeHints()).containsExactly("KB-AIR-006");
        assertThat(classify("What are the DGCA CAR-7 flight-duty-time limits?")
                .documentCodeHints()).containsExactly("KB-AIR-007");
        assertThat(classify("What are the no-show, overbooking, waitlist and denied-boarding rules?")
                .documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-002", "KB-AIR-004");
    }

    @Test
    void naturalTravelDocumentWordingTargetsTheTravelDocumentPolicy() {
        assertThat(classify("Which documents do I need for a domestic journey?")
                .documentCodeHints()).containsExactly("KB-AIR-002");
    }

    @Test
    void bookingWorkflowTargetsTheBookingKnowledgeDocument() {
        assertThat(classify(
                "Explain the UnitedAir booking workflow from flight search results "
                        + "to fare selection and payment.")
                .documentCodeHints()).contains("KB-AIR-001");
    }

    @Test
    void revenueBandsAndYieldRulesTargetTheSeatFareDocument() {
        assertThat(classify(
                "Explain Y, B, M, K and Q fare classes, revenue bands and yield rules.")
                .documentCodeHints()).containsExactly("KB-AIR-005");
    }

    @Test
    void boardingOverrideProcedureUsesPolicyInsteadOfDecisionHistory() {
        var result = classify(
                "What is the boarding override procedure, gate-change SLA "
                        + "and late-passenger rule?");

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.KB_LOOKUP);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
        assertThat(result.documentCodeHints()).containsExactly("KB-AIR-007");
    }

    @Test
    void deniedBoardingRightsIncludeThePassengerFacingDocument() {
        assertThat(classify("What are my rights if I am denied boarding?")
                .documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-002", "KB-AIR-004");
    }

    @Test
    void commandStyleNaturalRoutesDoNotTreatTheVerbAsPartOfTheAirport() {
        var show = classify("Show London to Dubai flights tomorrow");
        var find = classify("Find Bengaluru to Goa flights tomorrow");

        assertThat(show.origin()).isEqualTo("London");
        assertThat(show.destination()).isEqualTo("Dubai");
        assertThat(find.origin()).isEqualTo("Bengaluru");
        assertThat(find.destination()).isEqualTo("Goa");
    }

    @Test
    void compoundCasualGreetingRemainsSmallTalk() {
        assertThat(classify("Sup, what are you doing?").intent())
                .isEqualTo(OrchestrationDtos.Intent.SMALL_TALK);
    }

    @Test
    void adminSystemGovernanceQuestionRoutesToTheAdminWorkspaceBoundary() {
        RedactionResult redaction = new RedactionResult(
                "How are retrieval quality, embedding provenance and audience isolation verified?",
                Map.of(),
                0);

        var result = classifier.classify(
                redaction.redacted(), redaction, com.unitedair.ai.identity.Role.ADMIN);

        assertThat(result.intent()).isEqualTo(OrchestrationDtos.Intent.OUT_OF_SCOPE);
        assertThat(result.tool()).isEqualTo(OrchestrationDtos.ToolTarget.NONE);
    }

    @Test
    void mandatoryEscalationStillOverridesConversationalWords() {
        assertThat(classify("hello, someone used my card fraudulently").intent())
                .isEqualTo(OrchestrationDtos.Intent.ESCALATION);
    }
}
