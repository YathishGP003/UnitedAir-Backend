package com.unitedair.ai.orchestration;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.shared.UnitedAirProperties;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblerTest {

    private final PromptAssembler assembler = new PromptAssembler(new UnitedAirProperties());

    @Test
    void prohibitedItemsQuestionTellsHostedModelToGiveGroundedSafetyGuidance() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.BAGGAGE),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("RESTRICTED_ITEMS"),
                false,
                false);

        var request = assembler.assemble(
                Role.PASSENGER,
                "can i bring weapons and knife to the flight?",
                null,
                List.of(
                        ranked(1, "KB-AIR-003",
                                "Explosive materials are not allowed."),
                        ranked(2, "KB-AIR-003",
                                "Sharp objects must be safely packed in checked baggage."),
                        ranked(3, "KB-AIR-003",
                                "Economy cabin baggage is 7 kg.")),
                List.of(),
                List.of(),
                4,
                requirements);

        assertThat(request.userPrompt())
                .contains("Safety response: begin with a direct instruction")
                .contains("explosive-material, sharp-object and approved-firearm rules")
                .contains("Do not provide concealment, evasion or weapon-use instructions");
        assertThat(request.grounding())
                .extracting(com.unitedair.ai.llm.ChatDtos.Grounding::text)
                .containsExactly(
                        "Explosive materials are not allowed.",
                        "Sharp objects must be safely packed in checked baggage.");
    }

    @Test
    void structuredToolDataIsAvailableToHostedAndOfflineGeneration() {
        ToolDtos.ToolOutcome outcome = ToolDtos.ToolOutcome.ok(
                FlightSearchTool.NAME,
                Map.of("flightNo", "UA101", "departureTime", "06:15", "totalFare", 3446),
                "One departure is available.",
                15,
                Instant.parse("2026-07-26T12:00:00Z"),
                Map.of("origin", "BLR", "destination", "DEL"));

        var request = assembler.assemble(
                Role.PASSENGER,
                "Is there a flight?",
                "Is there a flight?",
                List.of(),
                List.of(outcome),
                List.of(),
                4);

        assertThat(request.userPrompt()).contains("UA101", "06:15", "3446");
        assertThat(request.grounding()).singleElement().satisfies(block ->
                assertThat(block.text()).contains(
                        "One departure is available.", "UA101", "06:15", "3446"));
    }

    @Test
    void passengerPromptRequiresPresentationInsteadOfCopyingEvidenceTables() {
        String prompt = assembler.systemPrompt(Role.PASSENGER);

        assertThat(prompt)
                .contains("Never copy an evidence table, pipe-delimited row, parser heading")
                .contains("Translate \"Nil\" into a clear no-fee or no-refund statement")
                .contains("An isolated \"Yes\" or \"No\" must name what is true or false")
                .contains("Keep every citation handle attached to the claim it supports");
    }

    @Test
    void promptForbidsUnrequestedOrNonToolOperationalQuotes() {
        assertThat(assembler.systemPrompt(Role.PASSENGER))
                .contains("Do not add unrelated")
                .contains("policies, prices, fees or advice")
                .contains("must come from a tool")
                .contains("tool result is authoritative for that")
                .contains("booking. Never claim the general row matches");
    }

    @Test
    void multiTopicRequirementsKeepEvidenceFromEveryRequiredDocument() {
        List<RetrievalDtos.Ranked> evidence = List.of(
                ranked(1, "KB-AIR-005", "Seat products and domestic fees"),
                ranked(2, "KB-AIR-003", "Unrelated baggage rule"),
                ranked(3, "KB-AIR-006", "WCHR MEDA PETC and AVIH special services"));
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.SEATS,
                        AnswerRequirements.RequestedTopic.FARES,
                        AnswerRequirements.RequestedTopic.SPECIAL_SERVICES),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("SPECIAL_SERVICES"),
                false,
                false);

        var request = assembler.assemble(
                Role.PASSENGER,
                "What special services and seat-selection fares are available?",
                null,
                evidence,
                List.of(),
                List.of(),
                1,
                requirements);

        assertThat(request.userPrompt())
                .contains("KB-AIR-005", "KB-AIR-006")
                .contains("Requested topics")
                .contains("Required categories");
    }

    @Test
    void multipartRequirementsKeepEvidenceForEveryRequestedCategory() {
        List<RetrievalDtos.Ranked> evidence = List.of(
                ranked(1, "KB-AIR-003", "Cabin baggage | Economy | 1 piece | 7 kg"),
                ranked(2, "KB-AIR-003", "Checked baggage | Economy | Value | 15 kg"),
                ranked(3, "KB-AIR-003", "Excess baggage | Weight over free allowance | Additional charges may apply"),
                ranked(5, "KB-AIR-003", "Restricted-item fees require a separate approved rule"),
                ranked(4, "KB-AIR-003", "Restricted items | High-capacity spare lithium batteries | Not accepted"));
        AnswerRequirements requirements = AnswerRequirements.from(
                "Give cabin and checked baggage limits, excess rules and "
                        + "power-bank guidance for domestic Economy.",
                null);

        var request = assembler.assemble(
                Role.PASSENGER,
                "Give cabin and checked baggage limits, excess rules and "
                        + "power-bank guidance for domestic Economy.",
                null,
                evidence,
                List.of(),
                List.of(),
                1,
                requirements);

        assertThat(request.grounding())
                .extracting(com.unitedair.ai.llm.ChatDtos.Grounding::text)
                .containsExactlyInAnyOrder(
                        "Cabin baggage | Economy | 1 piece | 7 kg",
                        "Checked baggage | Economy | Value | 15 kg",
                        "Excess baggage | Weight over free allowance | Additional charges may apply",
                        "Restricted items | High-capacity spare lithium batteries | Not accepted");
        assertThat(request.grounding())
                .extracting(com.unitedair.ai.llm.ChatDtos.Grounding::text)
                .doesNotContain("Restricted-item fees require a separate approved rule");
    }

    @Test
    void seatUpgradeAndFfpRequirementsSelectEveryNamedPolicySection() {
        List<RetrievalDtos.Ranked> evidence = List.of(
                ranked(1, "KB-AIR-006", "Pet in Cabin PETC Policy"),
                ranked(2, "KB-AIR-005", "Seat Categories and Fees Domestic"),
                ranked(3, "KB-AIR-005", "Upgrade Pathways for Passengers eligible fare classes fee conditions"),
                ranked(4, "KB-AIR-006", "Points Accrual Rates by Fare Class distance flown"),
                ranked(5, "KB-AIR-006", "Tier Structure and Qualification Blue Silver Gold Platinum"),
                ranked(6, "KB-AIR-006", "Points Redemption Options award flight and cabin upgrade"));
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain domestic seat types and fees, upgrade eligibility and payment "
                        + "rules, and points earning, tier qualification and redemption.",
                null);

        var request = assembler.assemble(
                Role.PASSENGER,
                "Explain domestic seat types and fees, upgrade eligibility and payment "
                        + "rules, and points earning, tier qualification and redemption.",
                null,
                evidence,
                List.of(),
                List.of(),
                1,
                requirements);

        assertThat(request.grounding())
                .extracting(com.unitedair.ai.llm.ChatDtos.Grounding::text)
                .contains(
                        "Seat Categories and Fees Domestic",
                        "Upgrade Pathways for Passengers eligible fare classes fee conditions",
                        "Points Accrual Rates by Fare Class distance flown",
                        "Tier Structure and Qualification Blue Silver Gold Platinum",
                        "Points Redemption Options award flight and cabin upgrade");
    }

    @Test
    void staffMultipartPromptExplainsCoverageDimensionsWithoutSupplyingAnswers() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain PETC and AVIH procedures, revenue proration, "
                        + "upgrade inventory and retro-credit rules.",
                null);

        var request = assembler.assemble(
                Role.AIRLINE_STAFF,
                "Explain PETC and AVIH procedures, revenue proration, "
                        + "upgrade inventory and retro-credit rules.",
                null,
                List.of(ranked(
                        1, "KB-AIR-006",
                        "PETC 48 hours health certificate and AVIH animal in hold "
                                + "health certificate evidence selected at runtime")),
                List.of(),
                List.of(),
                1,
                requirements);
        String repair = assembler.repairInstruction(
                List.of("MISSING_REQUIRED_CATEGORY"), requirements);

        assertThat(request.userPrompt())
                .contains("Coverage dimensions")
                .contains("covers [AVIH, AVIH_RULES, PETC, PETC_RULES]")
                .contains("advance-request timing, eligible animals")
                .contains("IATA or bilateral basis and settlement mechanism")
                .contains("inventory size, tier-based opening windows")
                .doesNotContain("INR 2,000", "10-15%");
        assertThat(repair)
                .contains("advance-request timing, eligible animals")
                .contains("inventory size, tier-based opening windows");
    }

    @Test
    void baggageTracingPromptExposesEveryMontrealCompletenessDimension() {
        String question = "My checked bag is missing after arrival. Explain where and "
                + "when to file the PIR, the WorldTracer update milestones, which "
                + "documents I must retain, and the Montreal Convention liability boundary.";
        AnswerRequirements requirements = AnswerRequirements.from(question, null);

        var request = assembler.assemble(
                Role.PASSENGER,
                question,
                null,
                List.of(ranked(
                        1, "KB-AIR-009",
                        "Mishandled-baggage and Montreal evidence selected at runtime")),
                List.of(),
                List.of(),
                1,
                requirements);

        assertThat(request.userPrompt())
                .contains("MONTREAL_LIABILITY_BOUNDARY")
                .contains("SDR limit scope, per-passenger basis")
                .contains("not an automatic payout")
                .contains("exchange-rate/conversion boundary");
    }

    @Test
    void baggageTracingPromptDoesNotAdmitUnrequestedBaggagePolicies() {
        String question = "My checked bag is missing. Explain PIR filing, WorldTracer "
                + "milestones, claim documents and the Montreal liability boundary.";
        AnswerRequirements requirements = AnswerRequirements.from(question, null);
        List<RetrievalDtos.Ranked> evidence = List.of(
                ranked(1, "KB-AIR-009",
                        "Property Irregularity Report arrival baggage desk"),
                ranked(2, "KB-AIR-009",
                        "Internal service milestones through Day 21"),
                ranked(3, "KB-AIR-009",
                        "Montreal Convention 1,519 SDR per passenger"),
                ranked(4, "KB-AIR-003",
                        "High-capacity spare lithium batteries are restricted"));

        var request = assembler.assemble(
                Role.PASSENGER,
                question,
                null,
                evidence,
                List.of(),
                List.of(),
                20,
                requirements);

        assertThat(request.grounding())
                .extracting(com.unitedair.ai.llm.ChatDtos.Grounding::text)
                .doesNotContain("High-capacity spare lithium batteries are restricted");
    }

    @Test
    void multipartCategorySelectionDoesNotPadWithUnrequestedSections() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(AnswerRequirements.RequestedTopic.SPECIAL_SERVICES),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("PETC_RULES", "AVIH_RULES", "REVENUE_PRORATION_RULES"),
                false,
                false);
        List<RetrievalDtos.Ranked> evidence = List.of(
                ranked(1, "KB-AIR-006",
                        "PETC health certificate and 48 hours advance request"),
                ranked(2, "KB-AIR-006",
                        "AVIH animal in hold health certificate"),
                ranked(3, "KB-AIR-007",
                        "Revenue proration uses IATA proration and BSP settlement"),
                ranked(4, "KB-AIR-006",
                        "WCHR wheelchair procedure not requested"),
                ranked(5, "KB-AIR-006",
                        "MEDA medical clearance not requested"));

        List<RetrievalDtos.Ranked> selected = PromptAssembler.selectEvidence(
                evidence, 20, requirements);

        assertThat(selected)
                .extracting(ranked -> ranked.chunk().content())
                .containsExactlyInAnyOrder(
                        "PETC health certificate and 48 hours advance request",
                        "AVIH animal in hold health certificate",
                        "Revenue proration uses IATA proration and BSP settlement")
                .doesNotContain(
                        "WCHR wheelchair procedure not requested",
                        "MEDA medical clearance not requested");
    }

    @Test
    void checkedAllowanceSelectionDoesNotChooseARestrictedItemsChunk() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "What is the checked baggage allowance?",
                null);
        List<RetrievalDtos.Ranked> evidence = List.of(
                ranked(1, "KB-AIR-003",
                        "Checked Baggage Only | Sharp objects must be safely packed"),
                ranked(2, "KB-AIR-003",
                        "Free Checked Baggage Allowance | Economy | Value | 15 kg"));

        List<RetrievalDtos.Ranked> selected = PromptAssembler.selectEvidence(
                evidence, 1, requirements);

        assertThat(selected)
                .extracting(ranked -> ranked.chunk().content())
                .containsExactly(
                        "Free Checked Baggage Allowance | Economy | Value | 15 kg");
    }

    @Test
    void compositeCrossBorderRequirementKeepsEveryGovernanceSection() {
        AnswerRequirements requirements = AnswerRequirements.from(
                "Explain Conditions of Carriage, passenger rights and cross-border rules.",
                null);
        List<RetrievalDtos.Ranked> evidence = List.of(
                ranked(1, "KB-AIR-010",
                        "2 Facts Required Before Selecting a Source | origin carrier event date"),
                ranked(2, "KB-AIR-010",
                        "3 Source Selection Rules | cross-border approved source"),
                ranked(3, "KB-AIR-010",
                        "4 Effective-Version Rules | effective source and travel date"),
                ranked(4, "KB-AIR-010",
                        "6 Escalation Triggers | origin carrier or date unavailable"));

        List<RetrievalDtos.Ranked> selected = PromptAssembler.selectEvidence(
                evidence, 1, requirements);

        assertThat(selected).hasSize(4);
    }

    @Test
    void generalCancellationSelectsTheCompleteMatrixNotAContinuationChunk() {
        AnswerRequirements requirements = new AnswerRequirements(
                Set.of(
                        AnswerRequirements.RequestedTopic.CANCELLATION,
                        AnswerRequirements.RequestedTopic.REFUND),
                AnswerRequirements.Scope.GENERAL_POLICY,
                Set.of("GENERAL_CANCELLATION_REFUND_POLICY"),
                false,
                false);
        List<RetrievalDtos.Ranked> evidence = List.of(
                ranked(1, "KB-AIR-004",
                        "Any time up to 2 hours before departure | Nil | Full base fare | Yes"),
                ranked(2, "KB-AIR-004",
                        "Cancellation Fee Matrix by Fare Type | Saver / Super Saver | "
                                + "Value | Flex | Full Flex"),
                ranked(3, "KB-AIR-004",
                        "Refund to Original Payment Method | cards 5-7 working days"));

        List<RetrievalDtos.Ranked> selected = PromptAssembler.selectEvidence(
                evidence, 1, requirements);

        assertThat(selected)
                .extracting(ranked -> ranked.chunk().content())
                .containsExactlyInAnyOrder(
                        "Cancellation Fee Matrix by Fare Type | Saver / Super Saver | "
                                + "Value | Flex | Full Flex",
                        "Refund to Original Payment Method | cards 5-7 working days");
    }

    private RetrievalDtos.Ranked ranked(
            int rank, String documentCode, String content) {
        RetrievalDtos.Chunk chunk = new RetrievalDtos.Chunk(
                (long) rank, "chunk-" + rank, documentCode, documentCode,
                "section", 1, "TXT", "policy-manual", "Passenger",
                content, 0.9, 1.0);
        return new RetrievalDtos.Ranked(chunk, 0.9, 0.9, rank);
    }
}
