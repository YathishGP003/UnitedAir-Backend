package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class RetrievalQueryExpanderTest {

    private final RetrievalQueryExpander expander = new RetrievalQueryExpander();

    @Test
    void expandsTravelDocumentLanguageWithPublishedPolicyTerms() {
        assertThat(expander.expand(
                "Which documents do I need for a domestic journey?",
                Set.of("KB-AIR-002")))
                .contains("government issued photo ID")
                .contains("acceptable travel documents");
    }

    @Test
    void expandsCheckInTimingAndIdentificationWithPublishedPolicyTerms() {
        assertThat(expander.expand(
                "When does check-in open, and what identification do I need for a domestic flight?",
                Set.of("KB-AIR-002")))
                .contains("check-in opening and closing times")
                .contains("government issued photo ID")
                .contains("acceptable travel documents");
    }

    @Test
    void expandsMedaDocumentationWithThePublishedFormName() {
        assertThat(expander.expand(
                "What documents must we collect for MEDA?",
                Set.of("KB-AIR-006")))
                .contains("MEDIF")
                .contains("Medical Information Form");
    }

    @Test
    void expandsDeniedBoardingRightsWithoutAddingAnAnswer() {
        assertThat(expander.expand(
                "What are my rights if I am denied boarding?",
                Set.of("KB-AIR-002", "KB-AIR-004")))
                .contains("involuntary denied boarding compensation")
                .doesNotContain("INR");
    }

    @Test
    void distinguishesCabinAllowanceFromTheCheckedBaggageTable() {
        assertThat(expander.expand(
                "What is the cabin baggage allowance in domestic Economy?",
                Set.of("KB-AIR-003")))
                .contains("allowed pieces")
                .contains("maximum weight per piece")
                .doesNotContain("free checked baggage");
    }

    @Test
    void expandsCombinedCabinAndCheckedLuggageLanguage() {
        assertThat(expander.expand(
                "For Economy inside India, tell me cabin and checked luggage limits together.",
                Set.of("KB-AIR-003")))
                .contains("cabin baggage allowance")
                .contains("checked baggage free allowance")
                .contains("domestic");
    }

    @Test
    void expandsPowerBankLanguageToPublishedBatteryTerms() {
        assertThat(expander.expand(
                "Can I pack a power bank in my suitcase and another in cabin baggage?",
                Set.of("KB-AIR-003")))
                .contains("lithium battery")
                .contains("carry-on")
                .contains("checked baggage");
    }

    @Test
    void expandsWeaponAndKnifeQuestionToThePublishedSafetySections() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "can i bring weapons and knife to the flight?",
                null);

        assertThat(expander.expand(
                "can i bring weapons and knife to the flight?",
                Set.of("KB-AIR-003"),
                requirements))
                .contains("Items Not Allowed")
                .contains("Explosive materials")
                .contains("Sharp objects")
                .contains("Checked Baggage Only");
        assertThat(expander.coverageQueries(requirements))
                .contains("4.1 Items Not Allowed", "4.2 Checked Baggage Only");
    }

    @Test
    void expandsNaturalPetLanguageToPublishedSsrTerms() {
        assertThat(expander.expand(
                "Can my cat ride in the cabin and can a large dog travel in the hold?",
                Set.of("KB-AIR-006")))
                .contains("PETC")
                .contains("AVIH");
    }

    @Test
    void expandsBumpedPassengerLanguageToDeniedBoardingTerms() {
        assertThat(expander.expand(
                "What happens when the airline bumps me although I hold a confirmed ticket?",
                Set.of("KB-AIR-002", "KB-AIR-004")))
                .contains("involuntary denied boarding")
                .contains("compensation");
    }

    @Test
    void expandsNaturalWheelchairRequestToTheMatchingSsrRow() {
        assertThat(expander.expand(
                "I need wheelchair help from check-in to the aircraft. "
                        + "Which SSR code and request deadline apply?",
                Set.of("KB-AIR-006")))
                .contains("WCHR")
                .contains("long distances")
                .contains("48 hours");
    }

    @Test
    void expandsNaturalChildTravellingAloneLanguageToPublishedUmTerms() {
        assertThat(expander.expand(
                "My ten-year-old is travelling alone. "
                        + "Which service and documents are required?",
                Set.of("KB-AIR-006")))
                .contains("Unaccompanied Minor")
                .contains("UM Form")
                .contains("emergency contact")
                .contains("photo ID");
    }

    @Test
    void expandsMultiServiceRequestsWithEveryPublishedSectionName() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.SPECIAL_SERVICES),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("SPECIAL_SERVICES"),
                false,
                false);

        assertThat(expander.expand(
                "Compare wheelchair, unaccompanied minor, PETC, AVIH and MEDA assistance.",
                Set.of("KB-AIR-006"),
                requirements))
                .contains("Wheelchair Assistance Codes")
                .contains("Unaccompanied Minor UM Service")
                .contains("Pet in Cabin PETC Policy")
                .contains("Animal in Hold AVIH Policy")
                .contains("Medical Clearance MEDA Procedure");
    }

    @Test
    void expandsGeneralCancellationPolicyToPublishedFareMatrixTerms() {
        assertThat(expander.expand(
                "What is the cancellation policy for domestic flights?",
                Set.of("KB-AIR-004")))
                .contains("cancellation fee")
                .contains("fare brand")
                .contains("timing band")
                .contains("refund eligibility")
                .doesNotContain("INR");
    }

    @Test
    void expandsBusinessClassQuestionToPublishedFareBrands() {
        assertThat(expander.expand(
                "What about business class?",
                Set.of("KB-AIR-005")))
                .contains("Business Saver")
                .contains("Business Flex")
                .contains("booking class");
    }

    @Test
    void expandsMissedFirstLegToPublishedNoShowTerms() {
        assertThat(expander.expand(
                "I missed the first leg. Will the airline keep my return sector?",
                Set.of("KB-AIR-004")))
                .contains("outbound no-show")
                .contains("round-trip booking")
                .contains("return leg");
    }

    @Test
    void createsFocusedCoverageQueriesForEveryNamedMultipartCategory() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Give cabin and checked baggage limits, excess rules and "
                        + "power-bank guidance for domestic Economy.",
                null);

        assertThat(expander.coverageQueries(requirements))
                .containsExactlyInAnyOrder(
                        "2.1 Cabin Baggage Allowance by Travel Class",
                        "3.1 Free Checked Baggage Allowance",
                        "3.2 Excess Baggage Fee Schedule",
                        "4.1 Items Not Allowed",
                        "4.2 Checked Baggage Only");
    }

    @Test
    void topicOnlyBookingPolicyStillGetsAnExactSemanticSectionQuery() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain the UnitedAir booking workflow from flight search results "
                        + "to fare selection and payment.",
                null);

        assertThat(requirements.requiredCategories()).isEmpty();
        assertThat(expander.coverageQueries(requirements))
                .contains("3.1 STEP-BY-STEP BOOKING PROCESS");
        assertThat(requirements.documentCodeHints()).contains("KB-AIR-001");
    }

    @Test
    void expandsRestrictedBatteryRequirementEvenWhenTheOriginalLithiumWordIsMisspelled() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "lithum battery in checked bag ok?",
                null);

        assertThat(expander.expand(
                "lithum battery in checked bag ok?",
                Set.of("KB-AIR-003"),
                requirements))
                .contains("lithium battery")
                .contains("prohibited checked baggage");
    }

    @Test
    void semanticRestrictedItemsPlanProducesAFocusedPolicyQuery() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "can i bring this to the airport?", null)
                .withTopics(Set.of(
                        AnswerRequirements.RequestedTopic.BAGGAGE,
                        AnswerRequirements.RequestedTopic.COMPLIANCE));

        assertThat(expander.coverageQueries(requirements))
                .contains("4.1 Items Not Allowed", "4.2 Checked Baggage Only");
        assertThat(expander.expand(
                "can i bring this to the airport?",
                Set.of(),
                requirements))
                .contains("prohibited checked baggage");
    }

    @Test
    void createsFocusedCoverageQueriesForEveryNamedSpecialService() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Compare wheelchair, unaccompanied minor, PETC, AVIH and MEDA assistance.",
                null);

        assertThat(expander.coverageQueries(requirements))
                .contains(
                        "3.1 Wheelchair Assistance Codes and Procedures",
                        "3.2 Unaccompanied Minor UM Service",
                        "3.4 Pet in Cabin PETC Policy",
                        "3.5 Animal in Hold AVIH Handling",
                        "3.3 Medical Clearance MEDA Procedure");
    }

    @Test
    void createsFocusedQueriesForGenericSpecialServicesAndSeatFares() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What special services are provided and what are the seat selection fares?",
                null);

        assertThat(expander.coverageQueries(requirements))
                .contains(
                        "3.1 Wheelchair Assistance Codes and Procedures",
                        "3.2 Unaccompanied Minor UM Service",
                        "3.4 Pet in Cabin PETC Policy",
                        "3.5 Animal in Hold AVIH Handling",
                        "3.3 Medical Clearance MEDA Procedure",
                        "3.1 Seat Categories and Fees");
    }

    @Test
    void createsFocusedQueriesForSeatUpgradeAndEveryNamedFfpSection() {
        String query = "Explain domestic seat types and fees, upgrade eligibility and "
                + "payment rules, plus frequent-flyer points earning, tier "
                + "qualification and redemption.";
        AnswerRequirements requirements = AnswerRequirements.from(query, null);

        assertThat(expander.expand(
                query,
                Set.of("KB-AIR-005", "KB-AIR-006"),
                requirements))
                .contains("Standard Economy Preferred Economy Comfort")
                .contains("Upgrade Pathways for Passengers")
                .contains("Points Accrual Rates by Fare Class")
                .contains("Tier Structure and Qualification")
                .contains("Points Redemption Options");
        assertThat(expander.coverageQueries(requirements))
                .contains(
                        "3.1 Seat Categories and Fees",
                        "5.1 Upgrade Pathways for Passengers",
                        "4.2 Points Accrual Rates by Fare Class",
                        "4.1 Tier Structure and Qualification",
                        "4.4 Points Redemption Options");
    }

    @Test
    void createsFocusedQueriesForNamedMealsAndDetailedAssistanceRules() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Give the VGML and KSML special meal deadlines plus eligibility, deadlines and "
                        + "documents for WCHR, unaccompanied minor, MEDA, PETC and AVIH.",
                null);

        assertThat(expander.coverageQueries(requirements))
                .contains(
                        "2.1 Available Meal Codes and Descriptions",
                        "3.1 Wheelchair Assistance Codes and Procedures",
                        "3.2 Unaccompanied Minor UM Service",
                        "3.3 Medical Clearance MEDA Procedure",
                        "3.4 Pet in Cabin PETC Policy",
                        "3.5 Animal in Hold AVIH Handling");
    }

    @Test
    void createsFocusedQueriesForEveryUs06StaffPolicySection() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain Y/B/M/K/H/Q/V/W revenue bands, cabin configurations, blocked "
                        + "seats, partial refunds, no-show, overbooking, waitlist and "
                        + "denied-boarding procedures.",
                null);

        assertThat(expander.coverageQueries(requirements))
                .contains(
                        "2.2 Booking Class Codes and Revenue Bands",
                        "2.1 UnitedAir Cabin Class Overview",
                        "3.3 Seat Blocking Rules Airline Staff",
                        "2.1 Cancellation Fee Matrix by Fare Type",
                        "5.1 No-Show Rules",
                        "6.1 Overbooking Thresholds",
                        "6.3 Waitlist Priority Rules",
                        "6.2 Involuntary Denied Boarding Compensation DGCA CAR");
    }

    @Test
    void createsFocusedQueriesForEveryUs07StaffPolicySection() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain boarding override, gate-change SLA, late-passenger procedures, "
                        + "WCHR, WCHC, WCHW, UM, PETC, AVIH and MEDA handling, codeshare, "
                        + "interline and proration, plus FFP upgrade inventory and retro-credit.",
                null);

        assertThat(expander.coverageQueries(requirements))
                .contains(
                        "2.1 Boarding Override Procedures",
                        "2.2 Gate Change Notification SLAs",
                        "2.3 Late Passenger Handling Protocol",
                        "3.1 Codeshare Booking Rules",
                        "3.2 Alliance and Partner Booking Codes",
                        "5.1 Upgrade Pool Management",
                        "4.2 Points Accrual Rates by Fare Class "
                                + "Retroactive claims accepted up to 6 months boarding pass");
    }

    @Test
    void createsFocusedQueriesForEveryUs08StaffPolicySection() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Summarize DGCA, IATA and SMS obligations, MEL and substitute aircraft "
                        + "disruption handling, agent commission, GDS and BSP rules, "
                        + "cross-border Conditions of Carriage, and CAR-7 FTL duty rest "
                        + "and augmented crew limits.",
                null);

        assertThat(expander.coverageQueries(requirements))
                .contains(
                        "4.1 Key DGCA Civil Aviation Requirements CARs for Daily Operations",
                        "4.2 Key IATA Resolutions for Operational Compliance",
                        "4.3 Safety Management System SMS Obligations",
                        "5.1 MEL Query Procedure",
                        "5.2 Disruption Management Protocols",
                        "6.1 Travel Agent Commission Structure",
                        "6.2 GDS Booking Codes and BSP Reconciliation",
                        "7.1 Source and Interpretation Boundary",
                        "3 Source Selection Rules",
                        "2 Facts Required Before Selecting a Source",
                        "4 Effective-Version Rules",
                        "6 Escalation Triggers",
                        "8.1 DGCA CAR-7 FTL Limits Key Parameters",
                        "8.2 Crew Scheduling Constraints");
    }

    @Test
    void createsFocusedQueriesForEveryUs09BaggageTracingSection() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "My checked bag is missing. Explain PIR filing, WorldTracer milestones, "
                        + "claim documents and Montreal liability limits.",
                null);

        assertThat(expander.coverageQueries(requirements))
                .contains(
                        "1 Report the bag at the arrival baggage-service desk before leaving the airport",
                        "3.3 UnitedAir Internal Service Milestones",
                        "4 Filing and Notice Windows",
                        "5 Montreal Convention Boundary and Current Limit");
    }
}
