package com.unitedair.ai.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unitedair.ai.grounding.EmptyContextPolicy;
import com.unitedair.ai.privacy.PiiRedactor;
import com.unitedair.ai.privacy.PiiType;
import org.junit.jupiter.api.Test;

class SrsSystemConstraintTest {

    private static final Path ROOT = Path.of("..");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exactEmptyContextAndBoundedConversationRulesAreConfigured() throws IOException {
        assertThat(EmptyContextPolicy.EMPTY_CONTEXT_ANSWER).isEqualTo(
                "No matching policy found. Please contact your UnitedAir Customer Support Manager.");

        String yaml = read("backend/src/main/resources/application.yml");
        String memory = read(
                "backend/src/main/java/com/unitedair/ai/conversation/ChatMemoryStore.java");
        String sessions = read(
                "backend/src/main/java/com/unitedair/ai/conversation/SessionService.java");
        assertThat(yaml)
                .contains("max-turns: 10")
                .contains("session-timeout-minutes: 30")
                .contains("minimum: 2")
                .contains("allow-empty-context: false");
        assertThat(memory).contains("WHERE session_uuid = :s").contains("evicted = TRUE");
        assertThat(sessions)
                .contains("expireIdleSessions")
                .contains("clearActiveMemoryForUser");
    }

    @Test
    void allTenPiiClassesUseTheExactRequiredTokens() {
        assertThat(PiiType.values()).hasSize(10);
        assertThat(List.of(PiiType.values()).stream().map(PiiType::token)).containsExactlyInAnyOrder(
                "[AIR-PNR-REDACTED]",
                "[AIR-PASSPORT-NO-REDACTED]",
                "[PAN-REDACTED]",
                "[AADHAAR-REDACTED]",
                "[AIR-FFP-ID-REDACTED]",
                "[PHONE-REDACTED]",
                "[EMAIL-REDACTED]",
                "[AIR-CARD-NO-REDACTED]",
                "[DOB-REDACTED]",
                "[ADDRESS-REDACTED]");

        String input = """
                PNR B6X9K2, passport P1234567, PAN ABCDE1234F, Aadhaar 1234 5678 9012,
                FFP ZA-12345678, phone 9876543210, email passenger@example.com,
                card 4111 1111 1111 1111, DOB 22/04/1985,
                address 45, Nehru Place, New Delhi 110019
                """;
        var result = new PiiRedactor().redact(input);
        assertThat(result.redactionCount()).isEqualTo(10);
        assertThat(result.redacted()).doesNotContain(
                "B6X9K2", "P1234567", "ABCDE1234F", "9876543210",
                "passenger@example.com", "4111 1111 1111 1111", "22/04/1985");
        assertThat(result.detectionSummary()).hasSize(10);
    }

    @Test
    void responseClassMatrixHasExecutableEvidenceForEveryRequiredRoute() throws IOException {
        JsonNode rows = JSON.readTree(ROOT.resolve(
                "docs/test-results/srs-semantic-results.json").toFile());
        assertThat(rows.size()).isGreaterThanOrEqualTo(150);

        Set<String> statuses = StreamSupport.stream(rows.spliterator(), false)
                .peek(row -> {
                    assertThat(row.path("passed").asBoolean())
                            .as(row.path("id").asText()).isTrue();
                    assertThat(row.path("traceId").asText()).isNotBlank();
                    assertThat(row.path("failures")).isEmpty();
                    if (Set.of("GROUNDED", "TOOL_GROUNDED", "ESCALATED", "ERROR")
                            .contains(row.path("status").asText())) {
                        assertThat(row.path("citationCount").asInt())
                                .as(row.path("id").asText() + " must carry evidence")
                                .isPositive();
                    }
                })
                .map(row -> row.path("status").asText())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(statuses).containsExactlyInAnyOrder(
                "CONVERSATIONAL",
                "CLARIFICATION",
                "GROUNDED",
                "TOOL_GROUNDED",
                "ESCALATED",
                "OUT_OF_SCOPE",
                "ERROR");
    }

    @Test
    void roleScopeCurrentVersionCitationAndFailureBoundariesAreEnforcedInCode()
            throws IOException {
        String repository = read(
                "backend/src/main/java/com/unitedair/ai/knowledge/KbRepository.java");
        String citations = read(
                "backend/src/main/java/com/unitedair/ai/grounding/GroundingDtos.java");
        String controller = read(
                "backend/src/main/java/com/unitedair/ai/orchestration/ChatController.java");
        String logger = read(
                "backend/src/main/java/com/unitedair/ai/tools/ToolInvocationLogger.java");

        assertThat(repository)
                .contains("v.status = 'ACTIVE'")
                .contains("audience")
                .contains("effective_from")
                .contains("effective_to");
        assertThat(citations)
                .contains("documentCode")
                .contains("section")
                .contains("page")
                .contains("toolOperation")
                .contains("retrievedAt");
        assertThat(controller).contains("OperationalFailureView");
        assertThat(logger)
                .contains("OperationalFailure")
                .contains("sessionUuid")
                .contains("record");
    }

    @Test
    void incompleteAnswersTriggerDeeperEvidenceBeforeRepair() throws IOException {
        String orchestrator = read(
                "backend/src/main/java/com/unitedair/ai/orchestration/AgenticOrchestrator.java");

        assertThat(orchestrator)
                .contains("gate.equals(\"MISSING_REQUESTED_TOPIC\")")
                .contains("gate.equals(\"MISSING_REQUIRED_CATEGORY\")")
                .contains("gate.equals(\"INCOMPLETE_ANSWER\")");
    }

    @Test
    void releaseVerificationUsesTheIsolatedTestDatabase() throws IOException {
        String gate = read("verify-srs-completion.ps1");
        String assembledVerifier = read("verify.ps1");
        String cleanup = read("scripts/clean-demo-state.ps1");
        String complianceReport = read("scripts/generate-compliance-report.ps1");
        String bootstrap = read("bootstrap.ps1");

        assertThat(gate).contains(
                "Save-Environment 'DB_URL' 'jdbc:mariadb://localhost:3306/unitedair_test'");
        assertThat(gate.lines()
                .filter(line -> line.contains("Database='unitedair_test'"))
                .count()).isEqualTo(3);
        assertThat(assembledVerifier)
                .contains("[string]$Database = 'unitedair'")
                .contains("\"--database=$Database\"");
        assertThat(cleanup)
                .contains("Read-EnvValue 'DB_URL'")
                .contains("Cannot derive the cleanup database from DB_URL");
        assertThat(complianceReport)
                .contains("[string]$Database = 'unitedair'")
                .contains("\"--database=$Database\"");
        assertThat(bootstrap).contains("CREATE DATABASE IF NOT EXISTS unitedair_test");
    }

    @Test
    void releaseVerificationOrdersSemanticTestsAndReportByTheirEvidenceDependencies()
            throws IOException {
        String gate = read("verify-srs-completion.ps1");

        int semanticEvaluation = gate.indexOf(
                "Invoke-GateScript 'adversarial and regression semantic conversations'");
        int complianceReport = gate.indexOf(
                "Invoke-GateScript 'generated compliance report'");
        int backendTests = gate.indexOf("Invoke-Native 'backend tests'");

        assertThat(semanticEvaluation).isPositive();
        assertThat(backendTests).isGreaterThan(semanticEvaluation);
        assertThat(complianceReport).isGreaterThan(backendTests);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative));
    }
}
