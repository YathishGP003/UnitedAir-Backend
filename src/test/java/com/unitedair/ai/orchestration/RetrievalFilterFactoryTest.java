package com.unitedair.ai.orchestration;

import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalFilterFactoryTest {

    private final RetrievalFilterFactory factory = new RetrievalFilterFactory();

    @Test
    void appliesExactDocumentHintsWithoutWeakeningActorAudienceScope() {
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE,
                0.9,
                "Baggage policy",
                null, null, null, null, null, null, null,
                List.of(), List.of(), Set.of("KB-AIR-003"));

        var passenger = factory.create(Role.PASSENGER, classification);

        assertThat(passenger.documentCodes()).containsExactly("KB-AIR-003");
        assertThat(passenger.audiences()).containsExactlyInAnyOrderElementsOf(
                Role.PASSENGER.readableAudiences());
    }

    @Test
    void leavesBroadRetrievalBroadWhenNoExactHintExists() {
        var classification = new OrchestrationDtos.Classification(
                OrchestrationDtos.Intent.KB_LOOKUP,
                OrchestrationDtos.ToolTarget.NONE,
                0.6,
                "Uncertain policy",
                null, null, null, null, null, null, null,
                List.of(), List.of(), Set.of());

        assertThat(factory.create(Role.AIRLINE_STAFF, classification).documentCodes()).isEmpty();
    }
}
