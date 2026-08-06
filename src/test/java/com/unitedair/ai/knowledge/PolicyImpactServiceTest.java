package com.unitedair.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PolicyImpactServiceTest {

    @Test
    void reportsDeclaredRequirementCoverageAndTheAuthoritativeBoundary() {
        var rows = List.of(
                new PolicyImpactService.DocumentImpact("KB_01", "FR-001, FR-002", 1),
                new PolicyImpactService.DocumentImpact("KB_03", "FR-006, FR-026", 2));

        var summary = PolicyImpactService.summarize(rows, 3);

        assertThat(summary.documents()).isEqualTo(2);
        assertThat(summary.versionedDocuments()).isEqualTo(1);
        assertThat(summary.inactiveCitationCount()).isEqualTo(3);
        assertThat(summary.declaredFunctionalRequirements())
                .contains("FR-001", "FR-002", "FR-006", "FR-026");
        assertThat(summary.authoritativeBoundaries()).anyMatch(
                text -> text.contains("FR-026") && text.contains("WorldTracer"));
    }
}
