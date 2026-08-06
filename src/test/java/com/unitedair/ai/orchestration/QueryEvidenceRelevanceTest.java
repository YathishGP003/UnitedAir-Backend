package com.unitedair.ai.orchestration;

import com.unitedair.ai.knowledge.RetrievalDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QueryEvidenceRelevanceTest {

    private final QueryEvidenceRelevance relevance = new QueryEvidenceRelevance();

    @Test
    void flatBaggageEvidenceIsIrrelevantToFlatEarthQuestion() {
        var route = classification(OrchestrationDtos.Intent.OUT_OF_SCOPE);
        var result = relevance.evaluate(
                "why earth is flat?",
                route,
                List.of(ranked("KB-AIR-003", "Extra baggage piece - a flat charge applies.")));

        assertThat(result.relevant()).isFalse();
        assertThat(result.reason()).isEqualTo("OUT_OF_SCOPE_QUERY");
    }

    @Test
    void cancellationEvidenceIsRelevantToCancellationQuestion() {
        var route = classification(OrchestrationDtos.Intent.KB_LOOKUP);
        var result = relevance.evaluate(
                "what is the cancellation policy?",
                route,
                List.of(ranked("KB-AIR-004", "Passenger-initiated cancellation policy.")));

        assertThat(result.relevant()).isTrue();
    }

    @Test
    void classifierDocumentHintsRemainAuthoritativeForSpecializedStaffTopics() {
        assertRelevant(
                "Explain Y, B, M, K and Q fare classes, revenue bands and yield rules.",
                "KB-AIR-005", Set.of("KB-AIR-005"));
        assertRelevant(
                "Explain codeshare routing, interline conditions and proration.",
                "KB-AIR-007", Set.of("KB-AIR-007"));
        assertRelevant(
                "How are MEL queries, aircraft substitutions and disruptions handled?",
                "KB-AIR-007", Set.of("KB-AIR-007"));
        assertRelevant(
                "What are the WorldTracer, PIR and Montreal Convention rules "
                        + "for mishandled baggage?",
                "KB-AIR-009", Set.of("KB-AIR-009"));
    }

    @Test
    void validAirlinePolicyEvidenceSurvivesNarrowHintMismatches() {
        assertRelevant(
                "Can I carry spare lithium batteries?",
                "KB-AIR-003", Set.of("KB-AIR-002"));
        assertRelevant(
                "What happens to the return leg after an outbound no-show?",
                "KB-AIR-004", Set.of("KB-AIR-002"));
        assertRelevant(
                "What compensation applies after denied boarding?",
                "KB-AIR-010", Set.of("KB-AIR-002"));
        assertRelevant(
                "How does the unaccompanied-minor service work?",
                "KB-AIR-006", Set.of("KB-AIR-002"));
        assertRelevant(
                "When does check-in open and what identification is needed "
                        + "for a domestic flight? What about an international trip?",
                "KB-AIR-002", Set.of("KB-AIR-003"));
        assertRelevant(
                "What is the Value fare cancellation fee? And for Flex?",
                "KB-AIR-004", Set.of("KB-AIR-002"));
        assertRelevant(
                "Explain the no-show, overbooking, waitlist and denied-boarding rules. "
                        + "Can we exceed that limit and who approves it?",
                "KB-AIR-007", Set.of("KB-AIR-002"));
        assertRelevant(
                "What are the DGCA CAR-7 flight-duty-time limits? "
                        + "And what mandatory rest is required?",
                "KB-AIR-007", Set.of("KB-AIR-002"));
    }

    @Test
    void validatedSemanticTopicCanAuthorizeMatchingLoyaltyEvidence() {
        var route = classification(OrchestrationDtos.Intent.KB_LOOKUP);
        var requirements = AnswerRequirements.from(
                "How do I earn and redeem points, and how do tiers work?", route)
                .withTopics(Set.of(AnswerRequirements.RequestedTopic.FFP));

        var result = relevance.evaluate(
                "How do I earn and redeem points, and how do tiers work?",
                route,
                requirements,
                List.of(ranked("KB-AIR-006", "Frequent flyer accrual redemption and tiers.")));

        assertThat(result.relevant()).isTrue();
        assertThat(result.reason()).isEqualTo("REQUESTED_TOPIC_DOCUMENT");
    }

    private void assertRelevant(
            String query,
            String evidenceDocument,
            Set<String> documentHints) {
        var route = classification(
                OrchestrationDtos.Intent.KB_LOOKUP, documentHints);
        assertThat(relevance.evaluate(
                query, route, List.of(ranked(evidenceDocument, query))).relevant())
                .isTrue();
    }

    private static OrchestrationDtos.Classification classification(
            OrchestrationDtos.Intent intent) {
        return classification(intent, Set.of());
    }

    private static OrchestrationDtos.Classification classification(
            OrchestrationDtos.Intent intent,
            Set<String> documentHints) {
        return new OrchestrationDtos.Classification(
                intent, OrchestrationDtos.ToolTarget.NONE, 0.9, "test",
                null, null, null, null, null, null, null,
                List.of(), List.of(), documentHints);
    }

    private static RetrievalDtos.Ranked ranked(String code, String content) {
        var chunk = new RetrievalDtos.Chunk(
                1L, "chunk-1", code, "Document", "Section", 1,
                "pdf", "policy", "Passenger", content, 0.9, 0.9);
        return new RetrievalDtos.Ranked(chunk, 0.9, 0.9, 1);
    }
}
