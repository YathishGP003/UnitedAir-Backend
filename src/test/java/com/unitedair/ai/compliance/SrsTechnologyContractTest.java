package com.unitedair.ai.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import com.unitedair.ai.tools.BookingManagementTool;
import com.unitedair.ai.tools.CheckInStatusTool;
import com.unitedair.ai.tools.EscalationTool;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.SrsToolCallbackProvider;
import com.unitedair.ai.tools.ToolDtos;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

class SrsTechnologyContractTest {

    private static final Path ROOT = Path.of("..");

    @Test
    void javaSpringAiHostedModelAndEmbeddingConfigurationMatchTheSrs() throws IOException {
        String pom = read("backend/pom.xml");
        String yaml = read("backend/src/main/resources/application.yml");

        assertThat(pom)
                .contains("<java.version>21</java.version>")
                .contains("<version>3.5.16</version>")
                .contains("<spring-ai.version>1.1.8</spring-ai.version>");
        assertThat(yaml)
                .contains("https://models.github.ai/inference")
                .contains("model: ${LLM_CHAT_MODEL:openai/gpt-4.1}")
                .contains("model: ${LLM_EMBED_MODEL:openai/text-embedding-3-small}")
                .contains("dimensions: ${LLM_EMBED_DIMENSIONS:1536}")
                .contains("path: /swagger-ui.html");
    }

    @Test
    void mariaDbHasVectorCosineLexicalFusionAndPostRetrievalReranking() throws IOException {
        String migration = read("backend/src/main/resources/db/migration/V3__knowledge.sql");
        String retriever = read(
                "backend/src/main/java/com/unitedair/ai/knowledge/HybridRetriever.java");

        assertThat(migration)
                .contains("VECTOR(1536)")
                .contains("FULLTEXT INDEX")
                .contains("VECTOR INDEX")
                .contains("DISTANCE=cosine");
        assertThat(retriever)
                .contains("Post-retrieval reranking")
                .contains("rerank(query, fused)")
                .contains("Comparator.comparingDouble(Fused::rerankScore).reversed()")
                .contains("rrfScore");
    }

    @Test
    void exactlyFourGovernedToolFamiliesExposeAllNineteenOperations() {
        assertThat(SrsToolCallbackProvider.FAMILIES).containsExactlyInAnyOrder(
                FlightSearchTool.NAME,
                BookingManagementTool.NAME,
                CheckInStatusTool.NAME,
                EscalationTool.NAME);

        Set<String> operations = new LinkedHashSet<>();
        operations.addAll(enumNames(ToolDtos.FlightSearchOperation.values()));
        operations.addAll(enumNames(ToolDtos.BookingManagementOperation.values()));
        operations.addAll(enumNames(ToolDtos.CheckInStatusOperation.values()));
        operations.addAll(enumNames(ToolDtos.EscalationOperation.values()));
        assertThat(operations).hasSize(19).contains(
                "SEARCH_FLIGHTS",
                "RETRIEVE_BOOKING",
                "PROPOSE_CANCELLATION",
                "GET_FLIGHT_STATUS",
                "PROPOSE_CHECK_IN",
                "CREATE_ESCALATION");

        for (Class<?> family : Set.of(
                FlightSearchTool.class,
                BookingManagementTool.class,
                CheckInStatusTool.class,
                EscalationTool.class)) {
            assertThat(Arrays.stream(family.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Tool.class))
                    .map(Method::getName)
                    .toList())
                    .as("%s must expose one governed Spring AI callback", family.getSimpleName())
                    .hasSize(1)
                    .contains("invoke");
        }
    }

    @Test
    void literalSrsThresholdPairIsConfigured() throws IOException {
        String yaml = read("backend/src/main/resources/application.yml");

        assertThat(yaml)
                .contains("similarity-threshold: ${RAG_SIMILARITY_THRESHOLD:0.50}")
                .contains("escalation-confidence: ${RAG_ESCALATION_CONFIDENCE:0.40}")
                .contains("allow-empty-context: false");
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(values).map(Enum::name).forEach(result::add);
        return result;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative));
    }
}
