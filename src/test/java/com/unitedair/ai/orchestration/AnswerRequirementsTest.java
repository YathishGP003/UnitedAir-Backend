package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AnswerRequirementsTest {

    @Test
    void explosiveAndSharpObjectQuestionRequiresBaggageSafetyEvidence() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "can i bring weapons and knife to the flight?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.topics())
                .contains(AnswerRequirements.RequestedTopic.BAGGAGE);
        assertThat(requirements.requiredCategories()).contains("RESTRICTED_ITEMS");
        assertThat(requirements.documentCodeHints()).contains("KB-AIR-003");
    }

    @Test
    void hostedBaggageAndComplianceTopicsRequireRestrictedItemsEvidence() {
        var base = AnswerRequirements.from(
                "can i bring this to the airport?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        var result = base.withTopics(Set.of(
                AnswerRequirements.RequestedTopic.BAGGAGE,
                AnswerRequirements.RequestedTopic.COMPLIANCE));

        assertThat(result.requiredCategories()).contains("RESTRICTED_ITEMS");
        assertThat(result.documentCodeHints()).contains("KB-AIR-003", "KB-AIR-007");
    }

    @Test
    void extractsEveryTopicAndDocumentForSpecialServicesAndSeatFares() {
        var classification = classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE);

        AnswerRequirements requirements = AnswerRequirements.from(
                "What special services are provided and what are the seat selection fares?",
                classification);

        assertThat(requirements.topics()).contains(
                AnswerRequirements.RequestedTopic.SPECIAL_SERVICES,
                AnswerRequirements.RequestedTopic.SEATS,
                AnswerRequirements.RequestedTopic.FARES);
        assertThat(requirements.documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-005", "KB-AIR-006");
        assertThat(requirements.requiredCategories()).contains(
                "SPECIAL_SERVICES",
                "WHEELCHAIR",
                "UNACCOMPANIED_MINOR",
                "PETC",
                "AVIH",
                "MEDA",
                "STANDARD_ECONOMY",
                "PREFERRED_ECONOMY",
                "COMFORT",
                "BUSINESS_WINDOW",
                "BUSINESS_AISLE");
    }

    @Test
    void domesticSeatQuestionRequiresEveryPublishedDomesticSeatCategory() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What seat types and fares are available on a domestic flight?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).containsExactlyInAnyOrder(
                "STANDARD_ECONOMY",
                "PREFERRED_ECONOMY",
                "COMFORT",
                "BUSINESS_WINDOW",
                "BUSINESS_AISLE");
        assertThat(requirements.domesticOnly()).isTrue();
    }

    @Test
    void multipartBaggageQuestionRequiresEveryRequestedBaggageCategory() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Give cabin and checked baggage limits, excess rules and "
                        + "power-bank guidance for domestic Economy.",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "CABIN_BAGGAGE",
                "CHECKED_BAGGAGE",
                "EXCESS_BAGGAGE",
                "RESTRICTED_ITEMS");
    }

    @Test
    void recognisesReversedCoordinationAndHyphenatedRestrictedItems() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What are the checked and cabin baggage limits and restricted-item rules?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "CABIN_BAGGAGE", "CHECKED_BAGGAGE", "RESTRICTED_ITEMS");
    }

    @Test
    void recognisesCommonLithiumTypoAsRestrictedBaggage() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "lithum battery in checked bag ok?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.topics())
                .contains(AnswerRequirements.RequestedTopic.BAGGAGE);
        assertThat(requirements.requiredCategories())
                .contains("RESTRICTED_ITEMS")
                .doesNotContain("CHECKED_BAGGAGE");
    }

    @Test
    void multipartSpecialServiceQuestionRequiresEveryNamedService() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Compare wheelchair, unaccompanied minor, PETC, AVIH and MEDA assistance.",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "WHEELCHAIR",
                "UNACCOMPANIED_MINOR",
                "PETC",
                "AVIH",
                "MEDA");
    }

    @Test
    void generalSeatQuestionDoesNotInventADomesticOnlyScope() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What seat types, selection fees and upgrade paths are available?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "STANDARD_ECONOMY",
                "PREFERRED_ECONOMY",
                "COMFORT",
                "BUSINESS_WINDOW",
                "BUSINESS_AISLE");
        assertThat(requirements.domesticOnly()).isFalse();
    }

    @Test
    void toolRouteRequiresOperationalStateAndPolicyFirstIsReviewOnly() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Show the policy first. What would happen if I cancel?",
                classification(
                        OrchestrationDtos.Intent.TOOL_PLUS_KB,
                        OrchestrationDtos.ToolTarget.REFUND_QUOTE));

        assertThat(requirements.operationalStateRequired()).isTrue();
        assertThat(requirements.reviewOnly()).isTrue();
        assertThat(requirements.topics()).contains(
                AnswerRequirements.RequestedTopic.REFUND,
                AnswerRequirements.RequestedTopic.CANCELLATION);
    }

    @Test
    void gateChangeProcedureDoesNotRequireLiveFlightStatus() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What is the boarding override procedure, gate-change SLA "
                        + "and late-passenger rule?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.topics())
                .doesNotContain(AnswerRequirements.RequestedTopic.STATUS);
        assertThat(requirements.operationalStateRequired()).isFalse();
    }

    @Test
    void pnrUsedToIdentifyARefundDoesNotCreateASecondBookingTopic() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What is the status of the refund for the PNR [AIR-PNR-REDACTED]?",
                classification(
                        OrchestrationDtos.Intent.TOOL_CALL,
                        OrchestrationDtos.ToolTarget.REFUND_STATUS));

        assertThat(requirements.topics())
                .containsExactly(AnswerRequirements.RequestedTopic.REFUND);
    }

    @Test
    void bookingUsedAsAFareObjectDoesNotCreateASeparateBookingTopic() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What are the fees for rescheduling a Value booking?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.topics())
                .contains(AnswerRequirements.RequestedTopic.FARES)
                .doesNotContain(AnswerRequirements.RequestedTopic.BOOKING);
    }

    @Test
    void validatedSemanticTopicsArePreservedForDownstreamDocumentFiltering() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "How do I earn and redeem points, and how do the tiers work?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        AnswerRequirements merged = requirements.withTopics(
                Set.of(AnswerRequirements.RequestedTopic.FFP));

        assertThat(merged.topics()).contains(AnswerRequirements.RequestedTopic.FFP);
        assertThat(merged.documentCodeHints()).contains("KB-AIR-006");
    }

    @Test
    void semanticPlannerCannotPolluteAnExplicitMultiTopicRequest() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain PETC and AVIH procedures, revenue proration, "
                        + "upgrade inventory and retro-credit rules.",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        AnswerRequirements merged = requirements.withTopics(Set.of(
                AnswerRequirements.RequestedTopic.BOOKING,
                AnswerRequirements.RequestedTopic.DISRUPTION,
                AnswerRequirements.RequestedTopic.STATUS,
                AnswerRequirements.RequestedTopic.CHECK_IN,
                AnswerRequirements.RequestedTopic.BAGGAGE));

        assertThat(merged.topics())
                .containsExactlyInAnyOrderElementsOf(requirements.topics())
                .doesNotContain(
                        AnswerRequirements.RequestedTopic.BOOKING,
                        AnswerRequirements.RequestedTopic.DISRUPTION,
                        AnswerRequirements.RequestedTopic.STATUS,
                        AnswerRequirements.RequestedTopic.CHECK_IN,
                        AnswerRequirements.RequestedTopic.BAGGAGE);
    }

    @Test
    void multipartSeatUpgradeAndFfpQuestionRequiresEveryNamedPolicySection() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "For a domestic passenger, explain the available seat types and fees, "
                        + "the upgrade eligibility and payment rules, and how UnitedAir "
                        + "frequent-flyer points earning, tier qualification and "
                        + "redemption work.",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "STANDARD_ECONOMY",
                "PREFERRED_ECONOMY",
                "COMFORT",
                "BUSINESS_WINDOW",
                "BUSINESS_AISLE",
                "UPGRADE_PATHWAYS",
                "FFP_EARNING",
                "FFP_TIERS",
                "FFP_REDEMPTION");
        assertThat(requirements.documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-005", "KB-AIR-006");
    }

    @Test
    void detailedMealsAndAssistanceQuestionRequiresSubstantiveRulesForEveryNamedService() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Which VGML and KSML special meals can a passenger pre-order and by "
                        + "when, and what are the eligibility, request deadlines and "
                        + "required documents for WCHR wheelchair assistance, an "
                        + "unaccompanied minor, MEDA medical clearance, PETC and AVIH?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "MEAL_VGML",
                "MEAL_KSML",
                "WHEELCHAIR_RULES",
                "UNACCOMPANIED_MINOR_RULES",
                "MEDA_RULES",
                "PETC_RULES",
                "AVIH_RULES");
    }

    @Test
    void bookingPlusGeneralCancellationPolicyRequiresASeparatePolicyOverview() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What is the status of PNR ABC123 and tell me generally about "
                        + "the cancellation and refund policy?",
                classification(
                        OrchestrationDtos.Intent.TOOL_PLUS_KB,
                        OrchestrationDtos.ToolTarget.REFUND_QUOTE));

        assertThat(requirements.requiredCategories())
                .contains(
                        "BOOKING_RESULT",
                        "GENERAL_CANCELLATION_REFUND_POLICY");
    }

    @Test
    void generalCancellationPolicyIncludesTheRefundOutcomeMatrix() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What is the cancellation policy?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories())
                .contains("GENERAL_CANCELLATION_REFUND_POLICY");
    }

    @Test
    void staffFareSeatRefundAndInventoryQuestionRequiresEveryUs06PolicySection() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "For staff handling, explain the Y/B/M/K/H/Q/V/W fare classes and "
                        + "revenue or yield bands, the cabin seat configurations and "
                        + "blocked-seat rules, the cancellation and partial-refund rules, "
                        + "and the no-show, overbooking, waitlist, and denied-boarding procedures.",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "FARE_CLASS_REVENUE_BANDS",
                "CABIN_CONFIGURATION",
                "SEAT_BLOCKING_RULES",
                "REFUND_FARE_RULES",
                "NO_SHOW_RULES",
                "OVERBOOKING_THRESHOLDS",
                "WAITLIST_RULES",
                "DENIED_BOARDING_RULES");
        assertThat(requirements.documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-004", "KB-AIR-005");
    }

    @Test
    void staffBoardingServicesInterlineAndFfpQuestionRequiresEveryUs07Section() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "For airline staff, explain the boarding override, gate-change SLA and "
                        + "late-passenger procedures; WCHR, WCHC, WCHW, UM, PETC, AVIH "
                        + "and MEDA handling; codeshare, interline and proration rules; "
                        + "and FFP upgrade-inventory and retro-credit procedures.",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "BOARDING_OVERRIDE_RULES",
                "GATE_CHANGE_SLA",
                "LATE_PASSENGER_RULES",
                "WHEELCHAIR_RULES",
                "UNACCOMPANIED_MINOR_RULES",
                "PETC_RULES",
                "AVIH_RULES",
                "MEDA_RULES",
                "CODESHARE_INTERLINE_RULES",
                "REVENUE_PRORATION_RULES",
                "FFP_UPGRADE_INVENTORY",
                "FFP_RETRO_CREDIT");
        assertThat(requirements.documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-006", "KB-AIR-007");
    }

    @Test
    void staffComplianceDisruptionAgencyRightsAndCrewQuestionRequiresEveryUs08Section() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Summarize DGCA, IATA and SMS obligations; MEL and aircraft substitution "
                        + "or disruption procedures; agent commission, GDS and BSP rules; "
                        + "cross-border passenger rights and Conditions of Carriage; and "
                        + "CAR-7 FTL, duty, rest and augmented-crew limits.",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "DGCA_DAILY_OBLIGATIONS",
                "IATA_OPERATIONAL_RESOLUTIONS",
                "SMS_OBLIGATIONS",
                "MEL_PROCEDURE",
                "DISRUPTION_SUBSTITUTION_RULES",
                "AGENT_COMMISSION_RULES",
                "GDS_BSP_RULES",
                "CONDITIONS_OF_CARRIAGE_BOUNDARY",
                "CROSS_BORDER_SOURCE_RULES",
                "FTL_DTL_LIMITS",
                "REST_AUGMENTED_CREW_RULES");
        assertThat(requirements.documentCodeHints())
                .containsExactlyInAnyOrder("KB-AIR-007", "KB-AIR-010");
    }

    @Test
    void mishandledBaggageQuestionRequiresEveryUs09Section() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "My checked bag is missing after arrival. Explain PIR filing, "
                        + "WorldTracer tracing milestones, claim documents and Montreal limits.",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE));

        assertThat(requirements.requiredCategories()).contains(
                "PIR_FILING_RULES",
                "WORLDTRACER_MILESTONES",
                "BAGGAGE_CLAIM_DOCUMENTS",
                "MONTREAL_LIABILITY_BOUNDARY");
        assertThat(requirements.documentCodeHints()).contains("KB-AIR-009");
        assertThat(requirements.documentCodeHints()).doesNotContain("KB-AIR-003");
    }

    @Test
    void recognizesNaturalMissingBagWordingAsBaggage() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain PIR filing and the Montreal liability boundary for my missing bag.",
                null);

        assertThat(requirements.topics())
                .contains(AnswerRequirements.RequestedTopic.BAGGAGE);
        assertThat(requirements.requiredCategories())
                .contains("PIR_FILING_RULES", "MONTREAL_LIABILITY_BOUNDARY");
    }

    @Test
    void pnrStatusAndPolicyQuestionRequiresThatBookingsFareNotEveryFareMatrix() {
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_PLUS_KB,
                OrchestrationDtos.ToolTarget.FLIGHT_STATUS,
                1.0,
                "status plus the booking's cancellation and refund terms",
                null,
                null,
                null,
                null,
                "WJUSW5",
                "UA101",
                null,
                List.of(),
                List.of(),
                Set.of());

        AnswerRequirements requirements = AnswerRequirements.from(
                "what is status of my flight pnr [AIR-PNR-REDACTED] and what is "
                        + "the cancellation and refund policy of the flight",
                classification);

        assertThat(requirements.topics()).contains(
                AnswerRequirements.RequestedTopic.STATUS,
                AnswerRequirements.RequestedTopic.CANCELLATION,
                AnswerRequirements.RequestedTopic.REFUND);
        assertThat(requirements.requiredCategories()).doesNotContain(
                "REFUND_FARE_RULES",
                "GENERAL_CANCELLATION_REFUND_POLICY");
        assertThat(requirements.operationalStateRequired()).isTrue();
    }

    @Test
    void decisionAuditFiltersDoNotBecomeUnrelatedPolicyObligations() {
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.TOOL_CALL,
                OrchestrationDtos.ToolTarget.OPERATIONAL_DECISIONS,
                0.97,
                "Authorized operational decision audit request",
                null, null, null, null, null, null, null,
                List.of(
                        "decision:REFUND_APPROVAL",
                        "decision:UPGRADE_AUTHORIZATION",
                        "decision:BOARDING_OVERRIDE",
                        "decision:SPECIAL_SERVICE_EXCEPTION"),
                List.of(),
                Set.of());

        AnswerRequirements requirements = AnswerRequirements.from(
                "Show the audit requirements for refund approvals, "
                        + "upgrade authorizations, boarding overrides and "
                        + "special-service exceptions.",
                classification);

        assertThat(requirements.topics())
                .containsExactly(AnswerRequirements.RequestedTopic.AUDIT);
        assertThat(requirements.requiredCategories()).isEmpty();
        assertThat(requirements.operationalStateRequired()).isTrue();
    }

    @Test
    void structuredProrationCategoriesRejectUnrelatedHostedAuditDrift() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What agreement type, benefits and revenue-proration method apply to "
                        + "alliance and domestic codeshare partners?",
                classification(
                        OrchestrationDtos.Intent.KB_LOOKUP,
                        OrchestrationDtos.ToolTarget.NONE))
                .withTopics(Set.of(AnswerRequirements.RequestedTopic.AUDIT));

        assertThat(requirements.requiredCategories()).contains(
                "CODESHARE_INTERLINE_RULES",
                "REVENUE_PRORATION_RULES");
        assertThat(requirements.topics()).isEmpty();
    }

    private OrchestrationDtos.Classification classification(
            OrchestrationDtos.Intent intent,
            OrchestrationDtos.ToolTarget tool) {
        return new OrchestrationDtos.Classification(
                intent, tool, 0.9, "test",
                null, null, null, null, null, null, null,
                List.of(), List.of(), Set.of());
    }
}
