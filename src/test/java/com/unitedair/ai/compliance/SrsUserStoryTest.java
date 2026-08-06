package com.unitedair.ai.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SrsUserStoryTest {

    private static final Path ROOT = Path.of("..");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, List<String>> STORY_FRS = Map.of(
            "US-01", List.of("FR-001", "FR-002", "FR-003", "FR-004"),
            "US-02", List.of("FR-005", "FR-013", "FR-014"),
            "US-03", List.of("FR-006"),
            "US-04", List.of("FR-007", "FR-008"),
            "US-05", List.of("FR-009", "FR-010", "FR-011", "FR-012"),
            "US-06", List.of("FR-016", "FR-017", "FR-018", "FR-019"),
            "US-07", List.of("FR-020", "FR-021", "FR-022", "FR-023"),
            "US-08", List.of("FR-024", "FR-025", "FR-027", "FR-028", "FR-029"),
            "US-09", List.of("FR-015", "FR-026"),
            "US-10", List.of("FR-028", "FR-030", "FR-031", "FR-032"));

    @Test
    void manifestContainsAllThirtyTwoFunctionalRequirementsAndTenStories()
            throws IOException {
        JsonNode manifest = manifest();
        Set<String> frs = stream(manifest)
                .filter(row -> "FUNCTIONAL_REQUIREMENT".equals(row.path("kind").asText()))
                .map(row -> row.path("id").asText())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> stories = stream(manifest)
                .filter(row -> "USER_STORY".equals(row.path("kind").asText()))
                .map(row -> row.path("id").asText())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(frs).hasSize(32);
        assertThat(frs).contains(
                java.util.stream.IntStream.rangeClosed(1, 32)
                        .mapToObj(number -> "FR-%03d".formatted(number))
                        .toArray(String[]::new));
        assertThat(stories).containsExactlyInAnyOrderElementsOf(STORY_FRS.keySet());
    }

    @ParameterizedTest(name = "{0} has complete executable evidence")
    @MethodSource("stories")
    void eachUserStoryHasPassingRoleAwareEvidence(
            String storyId, String actorScope, List<String> linkedFrs) throws IOException {
        JsonNode manifest = manifest();
        JsonNode story = stream(manifest)
                .filter(row -> storyId.equals(row.path("id").asText()))
                .findFirst()
                .orElseThrow();
        String actorTerm = actorScope.split("/")[0].replace("Airline ", "");
        assertThat(story.path("actor").asText()).containsIgnoringCase(actorTerm);
        assertThat(story.path("priority").asText()).isIn("HIGH", "CRITICAL");

        JsonNode semantic = semantic();
        for (String fr : linkedFrs) {
            List<JsonNode> evidence = stream(semantic)
                    .filter(row -> StreamSupport.stream(
                            row.path("requirementIds").spliterator(), false)
                            .anyMatch(id -> fr.equals(id.asText())))
                    .toList();
            assertThat(evidence)
                    .as("%s must have semantic evidence for %s", storyId, fr)
                    .isNotEmpty()
                    .allSatisfy(row -> {
                        assertThat(row.path("passed").asBoolean()).isTrue();
                        assertThat(row.path("failures")).isEmpty();
                        assertThat(row.path("traceId").asText()).isNotBlank();
                    });
        }
    }

    @ParameterizedTest(name = "{0} has a real executed API outcome")
    @MethodSource("stories")
    void eachUserStoryHasExecutedOutcomeEvidenceRatherThanOnlyManifestText(
            String storyId, String actorScope, List<String> linkedFrs)
            throws IOException {
        List<JsonNode> executions = stream(semantic())
                .filter(row -> StreamSupport.stream(
                        row.path("requirementIds").spliterator(), false)
                        .map(JsonNode::asText)
                        .anyMatch(linkedFrs::contains))
                .filter(row -> row.path("passed").asBoolean())
                .filter(row -> !row.path("status").asText().isBlank())
                .filter(row -> !Set.of("ERROR", "EMPTY_CONTEXT")
                        .contains(row.path("status").asText()))
                .filter(row -> !row.path("generationSource").asText().isBlank())
                .filter(row -> !row.path("answer").asText().isBlank())
                .filter(row -> !row.path("traceId").asText().isBlank())
                .toList();

        assertThat(executions)
                .as("%s must be supported by an executed response contract", storyId)
                .isNotEmpty()
                .allSatisfy(row -> {
                    assertThat(row.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
                    assertThat(row.path("answer").asText())
                            .doesNotStartWith("{")
                            .doesNotContain("<!doctype", "stack trace");
                });
    }

    @Test
    void semanticCatalogueCoversEveryFrAndAllThreeActorsWithoutLeakageFailures()
            throws IOException {
        JsonNode semantic = semantic();
        Set<String> tags = stream(semantic)
                .flatMap(row -> StreamSupport.stream(
                        row.path("requirementIds").spliterator(), false))
                .map(JsonNode::asText)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> actors = stream(semantic)
                .map(row -> row.path("role").asText())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(tags).contains(
                java.util.stream.IntStream.rangeClosed(1, 32)
                        .mapToObj(number -> "FR-%03d".formatted(number))
                        .toArray(String[]::new));
        assertThat(actors).containsExactlyInAnyOrder(
                "PASSENGER", "AIRLINE_STAFF", "ADMIN");
        assertThat(stream(semantic)).allSatisfy(row -> {
            assertThat(row.path("passed").asBoolean()).isTrue();
            assertThat(row.path("failures")).isEmpty();
        });
    }

    @Test
    void fr021KnowledgeBaseContainsAnActualAvihHandlingProcedure()
            throws IOException {
        String specialServices = Files.readString(ROOT.resolve(
                "kb/KB_06_Special_Services_Assistance_And_FFP(1).txt"));

        assertThat(specialServices)
                .contains("Animal in Hold (AVIH) Handling")
                .contains("72 hours")
                .contains("temperature-controlled and ventilated hold")
                .contains("Station Supervisor and Load Controller");
        assertThat(specialServices)
                .containsIgnoringCase("veterinarian health certificate");
    }

    static Stream<Arguments> stories() {
        return Stream.of(
                Arguments.of("US-01", "Passenger", STORY_FRS.get("US-01")),
                Arguments.of("US-02", "Passenger", STORY_FRS.get("US-02")),
                Arguments.of("US-03", "Passenger", STORY_FRS.get("US-03")),
                Arguments.of("US-04", "Passenger", STORY_FRS.get("US-04")),
                Arguments.of("US-05", "Passenger", STORY_FRS.get("US-05")),
                Arguments.of("US-06", "Airline Staff", STORY_FRS.get("US-06")),
                Arguments.of("US-07", "Airline Staff", STORY_FRS.get("US-07")),
                Arguments.of("US-08", "Airline Staff", STORY_FRS.get("US-08")),
                Arguments.of("US-09", "Passenger", STORY_FRS.get("US-09")),
                Arguments.of("US-10", "Staff", STORY_FRS.get("US-10")));
    }

    private static JsonNode manifest() throws IOException {
        return JSON.readTree(ROOT.resolve(
                "docs/test-data/srs-requirements.json").toFile());
    }

    private static JsonNode semantic() throws IOException {
        return JSON.readTree(ROOT.resolve(
                "docs/test-results/srs-semantic-results.json").toFile());
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
