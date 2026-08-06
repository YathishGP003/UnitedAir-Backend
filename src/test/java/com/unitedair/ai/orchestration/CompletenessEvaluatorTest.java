package com.unitedair.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.knowledge.RetrievalDtos;
import org.junit.jupiter.api.Test;

class CompletenessEvaluatorTest {

    private final CompletenessEvaluator evaluator = new CompletenessEvaluator();

    @Test
    void rejectsAnAnswerThatOmitsRequestedSpecialServices() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.SPECIAL_SERVICES,
                        AnswerRequirements.RequestedTopic.SEATS,
                        AnswerRequirements.RequestedTopic.FARES),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("SPECIAL_SERVICES"),
                false,
                false);

        var result = evaluate(
                "Preferred Economy seats cost INR 400.",
                requirements,
                List.of());

        assertThat(result.complete()).isFalse();
        assertThat(result.failedGates()).contains(
                "MISSING_REQUESTED_TOPIC",
                "MISSING_REQUIRED_CATEGORY",
                "INCOMPLETE_ANSWER");
        assertThat(result.missingTopics())
                .contains(AnswerRequirements.RequestedTopic.SPECIAL_SERVICES);
    }

    @Test
    void acceptsAllRequiredDomesticSeatCategoriesWithoutInternationalPrices() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.SEATS,
                        AnswerRequirements.RequestedTopic.FARES),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of(
                        "STANDARD_ECONOMY", "PREFERRED_ECONOMY", "COMFORT",
                        "BUSINESS_WINDOW", "BUSINESS_AISLE"),
                false,
                false,
                true);

        var result = evaluate(
                """
                Standard Economy seats are included. Preferred Economy has a fee.
                Comfort provides extra legroom for a fee. Business Window and
                Business Aisle seats are included.
                """,
                requirements,
                List.of());

        assertThat(result.complete()).isTrue();
        assertThat(result.failedGates()).isEmpty();
    }

    @Test
    void acceptsInternationalFeesWhenTheSeatQuestionIsNotDomesticOnly() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.SEATS,
                        AnswerRequirements.RequestedTopic.FARES),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of(
                        "STANDARD_ECONOMY", "PREFERRED_ECONOMY", "COMFORT",
                        "BUSINESS_WINDOW", "BUSINESS_AISLE"),
                false,
                false,
                false);

        var result = evaluate(
                """
                Standard Economy seats are included. Preferred Economy costs
                INR 400 domestically and has an international fee of USD 10.
                Comfort provides extra legroom. Business Window and Business Aisle
                seats are included.
                """,
                requirements,
                List.of());

        assertThat(result.complete()).isTrue();
        assertThat(result.failedGates()).doesNotContain("OUT_OF_SCOPE_DETAIL");
    }

    @Test
    void rejectsInternationalFeesForAnExplicitDomesticSeatQuestion() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.SEATS,
                        AnswerRequirements.RequestedTopic.FARES),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of(
                        "STANDARD_ECONOMY", "PREFERRED_ECONOMY", "COMFORT",
                        "BUSINESS_WINDOW", "BUSINESS_AISLE"),
                false,
                false,
                true);

        var result = evaluate(
                """
                Standard Economy seats are included. Preferred Economy costs
                INR 400 domestically and has an international fee of USD 10.
                Comfort provides extra legroom. Business Window and Business Aisle
                seats are included.
                """,
                requirements,
                List.of());

        assertThat(result.failedGates()).contains("OUT_OF_SCOPE_DETAIL");
    }

    @Test
    void catchesAnAnswerThatContradictsAFailedOperationalLookup() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.FLIGHT_SEARCH),
                AnswerRequirements.Scope.OPERATIONAL,
                Set.of(),
                true,
                false);
        ToolDtos.ToolOutcome failure = ToolDtos.ToolOutcome.failed(
                FlightSearchTool.NAME,
                "No route",
                2,
                Instant.parse("2026-07-27T10:00:00Z"),
                Map.of());

        var result = evaluate(
                "Three flights are available.",
                requirements,
                List.of(failure));

        assertThat(result.failedGates()).contains(
                "OPERATIONAL_STATE_NOT_ANSWERED",
                "FAILED_TOOL_CONTRADICTED");
    }

    @Test
    void personalRefundQuoteDoesNotReplaceRequestedGeneralPolicyOverview() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.BOOKING,
                        AnswerRequirements.RequestedTopic.CANCELLATION,
                        AnswerRequirements.RequestedTopic.REFUND),
                AnswerRequirements.Scope.OPERATIONAL,
                Set.of("GENERAL_CANCELLATION_REFUND_POLICY"),
                true,
                false);

        var incomplete = evaluate(
                "Your Super Saver booking has an estimated refund of INR 7,460.",
                requirements,
                List.of());
        var complete = evaluate(
                "Your Super Saver booking has an estimated refund of INR 7,460. "
                        + "Generally, Value fares use timing-based cancellation fees, "
                        + "while Flex fares have different cancellation conditions. "
                        + "Refund processing depends on the original payment method.",
                requirements,
                List.of());

        assertThat(incomplete.missingCategories())
                .contains("GENERAL_CANCELLATION_REFUND_POLICY");
        assertThat(complete.missingCategories()).isEmpty();
    }

    @Test
    void bookingLookupMustActuallyStateTheRetrievedBookingResult() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.BOOKING),
                AnswerRequirements.Scope.OPERATIONAL,
                Set.of("BOOKING_RESULT"),
                true,
                false);

        assertThat(evaluate(
                "Here is the general cancellation policy for Value and Flex fares.",
                requirements,
                List.of()).missingCategories()).contains("BOOKING_RESULT");
        assertThat(evaluate(
                "Your booking is confirmed for flight UA404.",
                requirements,
                List.of()).missingCategories()).isEmpty();
    }

    @Test
    void rejectsPartialAnswersForNamedBaggageAndSpecialServiceCategories() {
        AnswerRequirements baggage = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.BAGGAGE),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("CABIN_BAGGAGE", "CHECKED_BAGGAGE",
                        "EXCESS_BAGGAGE", "RESTRICTED_ITEMS"),
                false,
                false);
        AnswerRequirements services = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.SPECIAL_SERVICES),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("WHEELCHAIR", "UNACCOMPANIED_MINOR", "PETC", "AVIH", "MEDA"),
                false,
                false);

        assertThat(evaluate(
                "Cabin baggage is limited to 7 kg.",
                baggage,
                List.of()).missingCategories())
                .contains("CHECKED_BAGGAGE", "EXCESS_BAGGAGE", "RESTRICTED_ITEMS");
        assertThat(evaluate(
                "WCHR wheelchair and PETC assistance are available.",
                services,
                List.of()).missingCategories())
                .contains("UNACCOMPANIED_MINOR", "AVIH", "MEDA");
    }

    @Test
    void coordinatedBaggageHeadingCoversBothCabinAndCheckedCategories() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.BAGGAGE),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("CABIN_BAGGAGE", "CHECKED_BAGGAGE"),
                false,
                false);

        var result = evaluate(
                """
                Domestic Economy cabin and checked baggage allowances:
                Economy permits one piece up to 7 kg and Value includes 15 kg.
                """,
                requirements,
                List.of());

        assertThat(result.missingCategories()).isEmpty();
    }

    @Test
    void acceptsTheVerifiedCheckedWeightFromAnInternationalBooking() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.BAGGAGE),
                AnswerRequirements.Scope.OPERATIONAL,
                Set.of("CHECKED_BAGGAGE"),
                false,
                false);

        var result = evaluate(
                "Your booking includes 25 kg checked baggage [T1].",
                requirements,
                List.of());

        assertThat(result.missingCategories()).isEmpty();
        assertThat(result.complete()).isTrue();
    }

    @Test
    void coordinatedBaggageHeadingDoesNotHideAMissingCheckedAllowanceFact() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.BAGGAGE),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("CABIN_BAGGAGE", "CHECKED_BAGGAGE"),
                false,
                false);

        var result = evaluate(
                """
                Domestic Economy cabin and checked baggage allowances:
                Economy permits one cabin piece up to 7 kg.
                """,
                requirements,
                List.of());

        assertThat(result.missingCategories()).containsExactly("CHECKED_BAGGAGE");
    }

    @Test
    void lithiumGuidanceSatisfiesTheRestrictedItemCategory() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.BAGGAGE),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("CABIN_BAGGAGE", "CHECKED_BAGGAGE", "RESTRICTED_ITEMS"),
                false,
                false);

        var result = evaluate(
                """
                Domestic Economy cabin and checked baggage allowances:
                Economy permits one piece up to 7 kg and Value includes 15 kg.
                High-capacity spare lithium batteries are above the permitted limits.
                """,
                requirements,
                List.of());

        assertThat(result.missingCategories()).isEmpty();
    }

    @Test
    void wheelchairCodeAndDeadlineAnswerCompletesTheNaturalAssistanceRequest() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "I need wheelchair help from check-in to the aircraft. "
                        + "Which SSR code and request deadline apply?",
                null);

        var result = evaluate(
                """
                **Wheelchair special service (WCHR)** [E1].
                WCHR - Wheelchair (Ramp) - Passenger can manage stairs alone but
                needs wheelchair for long distances - 48 hours [E1].
                """,
                requirements,
                List.of());

        assertThat(requirements.topics())
                .containsExactly(AnswerRequirements.RequestedTopic.SPECIAL_SERVICES);
        assertThat(result.complete()).isTrue();
        assertThat(result.missingTopics()).isEmpty();
        assertThat(result.missingCategories()).isEmpty();
    }

    @Test
    void publishedOverAllowanceGuidanceCompletesCompoundExcessBaggageRequest() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Give cabin and checked baggage limits, excess rules and "
                        + "power-bank guidance for domestic Economy.",
                null);

        var result = evaluate(
                """
                Domestic Economy cabin and checked baggage allowances.
                Economy permits one piece up to 7 kg. Economy Value includes 15 kg.
                Weight over free allowance: additional baggage charges may apply.
                High-capacity spare lithium batteries are above airline-permitted limits.
                """,
                requirements,
                List.of());

        assertThat(result.complete()).isTrue();
        assertThat(result.missingCategories()).isEmpty();
    }

    @Test
    void citedRestrictedItemsEvidenceSatisfiesSemanticSafetyCoverageWithoutMagicWords() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.BAGGAGE,
                        AnswerRequirements.RequestedTopic.COMPLIANCE),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("RESTRICTED_ITEMS"),
                false,
                false);
        RetrievalDtos.Chunk chunk = new RetrievalDtos.Chunk(
                1L, "restricted", "KB-AIR-003", "Baggage Policy",
                "4.1 Items Not Allowed", 2, "TXT", "sop", "Passenger",
                "Restricted and Prohibited Items. Explosive materials include "
                        + "fireworks, flares and blasting caps.",
                0.8, 0.0);
        List<RetrievalDtos.Ranked> evidence = List.of(
                new RetrievalDtos.Ranked(chunk, 0.8, 0.8, 1));

        var cited = evaluator.evaluate(
                requirements,
                "Explosive materials such as fireworks are not allowed. [E1]",
                evidence,
                List.of());
        var uncited = evaluator.evaluate(
                requirements,
                "Explosive materials such as fireworks are not allowed.",
                evidence,
                List.of());

        assertThat(cited.complete()).isTrue();
        assertThat(cited.missingTopics()).isEmpty();
        assertThat(cited.missingCategories()).isEmpty();
        assertThat(uncited.complete()).isFalse();
    }

    @Test
    void escalationQueueLanguageSatisfiesTheStaffAuditTopic() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.REFUND,
                        AnswerRequirements.RequestedTopic.AUDIT),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of(),
                false,
                false);

        var result = evaluate(
                """
                Refund cases: no pending records were found. [T1]
                Escalations: six open cases require staff review. [T2]
                """,
                requirements,
                List.of());

        assertThat(result.complete()).isTrue();
        assertThat(result.missingTopics()).isEmpty();
    }

    @Test
    void rejectsASeatOnlyAnswerWhenUpgradeAndFfpPartsWereAlsoRequested() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain domestic seat types and fees, upgrade eligibility and payment "
                        + "rules, and points earning, tier qualification and redemption.",
                null);

        var incomplete = evaluate(
                "Standard Economy, Preferred Economy, Comfort extra legroom, "
                        + "Business Window and Business Aisle seats have domestic fees.",
                requirements,
                List.of());
        var complete = evaluate(
                "Standard Economy, Preferred Economy, Comfort extra legroom, "
                        + "Business Window and Business Aisle seats have domestic fees. "
                        + "Upgrade pathways state eligible fare classes and paid, bid or "
                        + "points conditions. Points earning uses fare-class accrual rates. "
                        + "Tier qualification covers Blue, Silver, Gold and Platinum. "
                        + "Points redemption includes award flights and cabin upgrades.",
                requirements,
                List.of());

        assertThat(incomplete.missingCategories()).contains(
                "UPGRADE_PATHWAYS", "FFP_EARNING", "FFP_TIERS", "FFP_REDEMPTION");
        assertThat(complete.complete())
                .withFailMessage("Missing categories: %s; topics: %s",
                        complete.missingCategories(), complete.missingTopics())
                .isTrue();
        assertThat(complete.missingCategories()).isEmpty();
    }

    @Test
    void evidenceUnavailablePlaceholdersDoNotSatisfyDetailedMealAndAssistanceRules() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Give the VGML and KSML special meal deadlines plus eligibility, deadlines and "
                        + "documents for WCHR, unaccompanied minor, MEDA, PETC and AVIH.",
                null);

        var incomplete = evaluate(
                "VGML and KSML details are not specified. WCHR is for passengers "
                        + "who manage stairs but need a wheelchair for long distances; "
                        + "request it 48 hours ahead. Unaccompanied minor service is for "
                        + "ages 5 to 11 and needs a UM form plus pickup photo ID. "
                        + "MEDA needs MEDIF at least 48 hours ahead, with 72 hours "
                        + "recommended. PETC needs a veterinary health certificate, "
                        + "accepts cats and dogs up to 7 kg and must be booked 48 hours ahead. "
                        + "AVIH details are not available.",
                requirements,
                List.of());
        var complete = evaluate(
                "VGML is vegan and must be ordered 24 hours before departure. "
                        + "KSML is kosher and must be ordered 48 hours before departure. "
                        + "WCHR is for a passenger who can manage stairs and needs a "
                        + "wheelchair for distance; request it 48 hours before departure. "
                        + "Unaccompanied minor service is mandatory from age 5 to 11, "
                        + "requires the UM form and authorised pickup photo ID. "
                        + "MEDA requires MEDIF Parts A and B at least 48 hours before departure. "
                        + "PETC accepts cats and dogs up to 7 kg with a veterinary health "
                        + "certificate and 48-hour booking. "
                        + "AVIH carries animals in the hold on eligible international routes, "
                        + "requires a veterinary health certificate and a 72-hour request.",
                requirements,
                List.of());

        assertThat(incomplete.missingCategories()).contains(
                "MEAL_VGML", "MEAL_KSML", "AVIH_RULES");
        assertThat(complete.missingCategories()).isEmpty();
        assertThat(complete.missingTopics()).isEmpty();
        assertThat(complete.complete()).isTrue();
    }

    @Test
    void rejectsUs06AnswerThatClaimsPublishedOperationalRulesAreUnavailable() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain Y/B/M/K/H/Q/V/W revenue bands, cabin configurations, blocked "
                        + "seats, partial refunds, no-show, overbooking, waitlist and "
                        + "denied-boarding procedures.",
                null);

        var incomplete = evaluate(
                "Y through W are Economy revenue bands. Cancellation fees and no-show "
                        + "forfeiture apply. Seat blocks, overbooking, waitlist and denied "
                        + "boarding are handled operationally but the details are not "
                        + "available in the evidence.",
                requirements,
                List.of());
        var complete = evaluate(
                "Y is Full and W is Promo, with yield decreasing from unrestricted to "
                        + "scarce promotional inventory. Economy Standard has 29-31 inch "
                        + "pitch, 17-18 inch width and 3-4 inch recline; Business has "
                        + "42-78 inch pitch and 21-22 inch width. CBBG, EXST and STCR "
                        + "seat blocks require the published Revenue Management, check-in "
                        + "or medical approvals. Value cancellation costs INR 2,000 over "
                        + "7 days, INR 3,000 from 3 to 7 days, or INR 4,000 within 3 days; "
                        + "the balance after fee and taxes are refunded. Saver forfeits "
                        + "the base fare but statutory taxes are refunded. A passenger who does not "
                        + "check in or cancel is a no-show, and an outbound no-show "
                        + "cancels the return leg. Domestic short-haul overbooking is 5% "
                        + "standard and 8% peak with Revenue Management Head authority. "
                        + "The waitlist clears involuntary rebookings first, then Full "
                        + "Flex and Business Flex, then Platinum and Gold members. "
                        + "Involuntary denied boarding pays INR 2,000 below one hour, "
                        + "INR 5,000 for one to six hours, or INR 10,000 above six hours "
                        + "in Economy; volunteers instead accept the agreed gate credit.",
                requirements,
                List.of());

        assertThat(incomplete.missingCategories()).contains(
                "CABIN_CONFIGURATION",
                "SEAT_BLOCKING_RULES",
                "OVERBOOKING_THRESHOLDS",
                "WAITLIST_RULES",
                "DENIED_BOARDING_RULES");
        assertThat(complete.complete()).isTrue();
        assertThat(complete.missingCategories()).isEmpty();
    }

    @Test
    void genericFareLabelsDoNotSatisfyThePartialRefundRule() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain the cancellation and partial-refund rules for Value and Saver fares.",
                null);

        var result = evaluate(
                "Value fares have standard cancellation fees and higher refund eligibility. "
                        + "Saver fares are restricted. Statutory taxes may be refunded.",
                requirements,
                List.of());

        assertThat(result.missingCategories()).contains("REFUND_FARE_RULES");
        assertThat(result.complete()).isFalse();
    }

    @Test
    void acceptsNaturalBaseFareRefundWordingForThePublishedRefundMatrix() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.CANCELLATION,
                        AnswerRequirements.RequestedTopic.REFUND),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("REFUND_FARE_RULES"),
                false,
                false);

        var result = evaluate(
                """
                Value fare cancellation costs INR 2,000 more than 7 days before
                departure, INR 3,000 from 3 to 7 days, or INR 4,000 within 3 days.
                The remaining base fare is refunded after the applicable fee.
                Saver fares forfeit the base fare, but statutory taxes are refunded.
                """,
                requirements,
                List.of());

        assertThat(result.complete()).isTrue();
        assertThat(result.missingCategories()).isEmpty();
    }

    @Test
    void rejectsUs07AnswerThatClaimsPublishedStaffProceduresAreUnavailable() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain boarding override, gate-change SLA, late-passenger procedures, "
                        + "WCHR, WCHC, WCHW, UM, PETC, AVIH and MEDA handling, codeshare, "
                        + "interline and proration, plus FFP upgrade inventory and retro-credit.",
                null);

        var incomplete = evaluate(
                "Wheelchair assistance and MEDA are available. Boarding, proration, "
                        + "upgrade inventory and retro-credit details are not in the evidence.",
                requirements,
                List.of());
        var complete = evaluate(
                "A boarding override for a late passenger after gate closure needs a "
                        + "Duty Manager and ZA-OPS-010 log. A gate change inside 30 minutes needs staff escort, "
                        + "FIDS updates and PA announcements every 5 minutes. At 10-14 minutes "
                        + "before departure the Duty Manager decides and the bag may be offloaded. "
                        + "WCHR, WCHC and WCHW require a 48-hour request; WCHR can manage stairs "
                        + "but needs help over long distances. Unaccompanied minor service for "
                        + "ages 5-11 requires the UM form and pickup photo ID. PETC "
                        + "accepts cats or dogs up to 7 kg with a health certificate and 48-hour "
                        + "booking. AVIH uses the hold, a health certificate and 72-hour request. "
                        + "MEDA needs MEDIF within 48 hours. For codeshare, check in with the "
                        + "operating carrier and through-check interline bags to destination. "
                        + "Star Alliance revenue uses IATA proration and BSP settlement; selected "
                        + "partners use bilateral rates. The FFP upgrade pool is 10-15% of Business "
                        + "seats, opens 7 days for Platinum, 5 days for Gold and 48 hours for "
                        + "Silver, with unsold seats released 2 hours before departure. "
                        + "Retro-credit claims are accepted within 6 months and require the "
                        + "boarding pass.",
                requirements,
                List.of());

        assertThat(incomplete.missingCategories()).contains(
                "BOARDING_OVERRIDE_RULES",
                "GATE_CHANGE_SLA",
                "LATE_PASSENGER_RULES",
                "CODESHARE_INTERLINE_RULES",
                "REVENUE_PRORATION_RULES",
                "FFP_UPGRADE_INVENTORY",
                "FFP_RETRO_CREDIT");
        assertThat(complete.complete())
                .withFailMessage("Missing categories: %s; topics: %s",
                        complete.missingCategories(), complete.missingTopics())
                .isTrue();
        assertThat(complete.missingCategories()).isEmpty();
    }

    @Test
    void rejectsUs08AnswerThatOmitsPublishedComplianceAndCrewSections() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Summarize DGCA, IATA and SMS obligations, MEL and substitute aircraft "
                        + "disruption handling, agent commission, GDS and BSP rules, "
                        + "cross-border Conditions of Carriage, and CAR-7 FTL duty rest "
                        + "and augmented crew limits.",
                null);

        var incomplete = evaluate(
                "UnitedAir follows DGCA and IATA rules. Contact Compliance for the "
                        + "remaining operational details.",
                requirements,
                List.of());
        var complete = evaluate(
                "DGCA daily obligations cover MEL airworthiness, passenger rights, crew "
                        + "licensing and FTL. IATA Resolution 722 requires e-tickets, 830e "
                        + "covers Conditions of Carriage, 799 aligns booking codes and 780 "
                        + "requires annual dangerous-goods training. SMS requires MOR filing "
                        + "within 72 hours, monthly committee review and annual internal audit. "
                        + "For MEL, the LAME checks dispatch conditions, placards and logs the "
                        + "item with OCC; an unpermitted item grounds the aircraft as AOG. "
                        + "OCC arranges a substitute aircraft or rebooks passengers when the "
                        + "delay exceeds 2 hours, with weather, AOG, network and diversion "
                        + "entitlements applied. IATA agencies have 0% base commission with "
                        + "bilateral incentives; preferred accounts use PLB and OTAs settle "
                        + "weekly. GDS fares are filed 72 hours ahead; ADM is within 9 months, "
                        + "ACM within 30 days and BSP disputes within 3 months. Conditions of "
                        + "Carriage must use active UA-COC-2026-01 and must not decide disputed "
                        + "legal outcomes. Cross-border selection needs route, date, carriers "
                        + "and event, uses only the effective source and escalates conflicts. "
                        + "CAR-7 limits flight time to 8 hours daily, 40 in 7 days, 100 in 28 "
                        + "days and 1,000 yearly; standard duty is 12 hours. Augmented crew may "
                        + "work 16 hours with 2 pilots plus 1 additional pilot, followed by at "
                        + "least 12 hours rest and 36 hours after 7 consecutive duty days.",
                requirements,
                List.of());

        assertThat(incomplete.missingCategories()).contains(
                "SMS_OBLIGATIONS",
                "MEL_PROCEDURE",
                "AGENT_COMMISSION_RULES",
                "GDS_BSP_RULES",
                "CROSS_BORDER_SOURCE_RULES",
                "FTL_DTL_LIMITS",
                "REST_AUGMENTED_CREW_RULES");
        assertThat(complete.complete())
                .withFailMessage("Missing categories: %s; topics: %s",
                        complete.missingCategories(), complete.missingTopics())
                .isTrue();
    }

    @Test
    void rejectsUs09AnswerThatOmitsPirTracingDocumentsOrLiabilityBoundary() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "My checked bag is missing. Explain PIR filing, WorldTracer milestones, "
                        + "claim documents and Montreal liability limits.",
                null);

        var incomplete = evaluate(
                "Report the missing bag to the airport and contact support.",
                requirements,
                List.of());
        var complete = evaluate(
                "Report at the arrival baggage desk before leaving. Show your boarding "
                        + "pass, baggage-tag receipt and government ID; give contact and "
                        + "delivery details and collect the PIR reference. Keep interim "
                        + "purchase receipts and photographs where relevant. WorldTracer "
                        + "starts immediately, sends an update within 24 hours, has "
                        + "supervisor review on Day 3, operations review on Day 5, claims "
                        + "pre-review on Day 14 and formal assessment at Day 21. For "
                        + "Montreal-governed international carriage the current limit is "
                        + "1,519 SDR per passenger, not an automatic payout, and SDR must "
                        + "not be converted using a hard-coded exchange rate.",
                requirements,
                List.of());

        assertThat(incomplete.missingCategories()).contains(
                "PIR_FILING_RULES",
                "WORLDTRACER_MILESTONES",
                "BAGGAGE_CLAIM_DOCUMENTS",
                "MONTREAL_LIABILITY_BOUNDARY");
        assertThat(complete.complete())
                .withFailMessage("Missing categories: %s; topics: %s",
                        complete.missingCategories(), complete.missingTopics())
                .isTrue();
    }

    @Test
    void acceptsEvidenceGroundedMontrealConversionParaphrase() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("MONTREAL_LIABILITY_BOUNDARY"),
                false,
                false);

        var result = evaluate(
                "For Montreal-governed international carriage the current limit is "
                        + "1,519 SDR per passenger, not an automatic payout. Convert SDR "
                        + "using an approved current rate, not a fixed exchange rate.",
                requirements,
                List.of());

        assertThat(result.complete())
                .withFailMessage("Missing categories: %s; topics: %s",
                        result.missingCategories(), result.missingTopics())
                .isTrue();
    }

    @Test
    void acceptsUnicodeDashInLatePassengerTimingBand() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("LATE_PASSENGER_RULES"),
                false,
                false);

        var result = evaluate(
                "Late passenger handling: a passenger arriving 10–14 minutes "
                        + "before departure requires "
                        + "a Duty Manager decision, and the checked bag may need "
                        + "to be offloaded if it is already in the hold.",
                requirements,
                List.of());

        assertThat(result.missingCategories())
                .doesNotContain("LATE_PASSENGER_RULES");
    }

    private CompletenessEvaluator.Result evaluate(
            String answer,
            AnswerRequirements requirements,
            List<ToolDtos.ToolOutcome> outcomes) {
        return evaluator.evaluate(requirements, answer, List.of(), outcomes);
    }
}
