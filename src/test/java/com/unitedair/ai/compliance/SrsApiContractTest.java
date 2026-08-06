package com.unitedair.ai.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.unitedair.ai.audit.AuditController;
import com.unitedair.ai.knowledge.KbController;
import com.unitedair.ai.knowledge.KbDtos;
import com.unitedair.ai.orchestration.ChatController;
import com.unitedair.ai.orchestration.OrchestrationDtos;
import com.unitedair.ai.tools.ToolController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class SrsApiContractTest {

    private static final Path ROOT = Path.of("..");

    @Test
    void allEightExactSrsEndpointsArePublishedWithTheRequiredMethods() {
        assertPost(ChatController.class, "sync", "/ai/airline/chat", "/sync");
        assertPost(ChatController.class, "stream", "/ai/airline/chat", "/async");
        assertGet(AuditController.class, "sessionTrail", "", "/audit/{sessionId}");
        assertPost(KbController.class, "ingest", "/kb", "/ingest");
        assertPost(ToolController.class, "searchPost", "/tools", "/flights/search");
        assertGet(ToolController.class, "booking", "/tools", "/booking/{pnr}");
        assertGet(ToolController.class, "flightStatus", "/tools", "/flightstatus/{flightNo}");
        assertPost(ToolController.class, "escalate", "/tools", "/escalation/create");
    }

    @Test
    void requestResponseCitationFailureAndIngestionSchemasAreTyped() {
        assertThat(componentNames(OrchestrationDtos.ChatRequest.class))
                .containsExactly("message", "sessionId", "deepSearch");
        assertThat(componentNames(OrchestrationDtos.ChatResponse.class)).contains(
                "answer", "status", "citations", "followups", "toolCalls",
                "sessionId", "traceId", "generationSource", "operationalFailure",
                "proposedAction", "commerce");
        assertThat(componentNames(OrchestrationDtos.CitationView.class)).contains(
                "documentCode", "documentTitle", "section", "page", "toolName",
                "toolOperation", "provider", "providerLive", "retrievedAt");
        assertThat(componentNames(OrchestrationDtos.OperationalFailureView.class))
                .containsExactly("toolFamily", "operation", "code", "message", "details");
        assertThat(componentNames(KbDtos.IngestionResult.class)).containsExactly(
                "documentId", "versionId", "jobUuid", "documentCode", "title",
                "chunksCreated", "ingestionTimeMs", "metadata");
    }

    @Test
    void authenticationRoleOwnershipRedactionAndAuditCorrelationAreMandatory()
            throws IOException {
        String security = read(
                "backend/src/main/java/com/unitedair/ai/identity/SecurityConfig.java");
        String tools = read(
                "backend/src/main/java/com/unitedair/ai/tools/ToolController.java");
        String audit = read(
                "backend/src/main/java/com/unitedair/ai/audit/AuditController.java");
        String logger = read(
                "backend/src/main/java/com/unitedair/ai/tools/ToolInvocationLogger.java");
        String orchestrator = read(
                "backend/src/main/java/com/unitedair/ai/orchestration/AgenticOrchestrator.java");

        assertThat(security)
                .contains(".anyRequest().authenticated()")
                .contains(".requestMatchers(HttpMethod.POST,   \"/kb/**\").hasRole(\"ADMIN\")");
        Method ingest = method(KbController.class, "ingest");
        assertThat(ingest.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
        assertThat(tools).contains("BookingAccess.from(currentUser.require())");
        assertThat(audit)
                .contains("This conversation belongs to another user.")
                .contains("currentUser.require()");
        assertThat(logger)
                .contains("TraceContext.sessionUuid()")
                .contains("request_json")
                .contains("already-redacted arguments");
        assertThat(orchestrator)
                .contains("pnrContextRedactor.redact")
                .contains("toolOrchestrator");
    }

    @Test
    void sseOrdersEventsAndCompletesWithTheSameValidatedResponseShape() throws IOException {
        String source = read(
                "backend/src/main/java/com/unitedair/ai/orchestration/ChatController.java");
        int start = source.indexOf("send(emitter, \"start\"");
        int text = source.indexOf("streamText(emitter, displayAnswer(answer.answer()))");
        int complete = source.indexOf("send(emitter, \"complete\"", text);

        assertThat(start).isNotNegative();
        assertThat(text).isGreaterThan(start);
        assertThat(complete).isGreaterThan(text);
        assertThat(source)
                .contains("toResponse(answer, (System.nanoTime() - started) / 1_000_000)")
                .contains("emitter.complete()")
                .contains("send(emitter, \"error\"");
    }

    private static void assertPost(
            Class<?> controller, String methodName, String prefix, String path) {
        Method method = method(controller, methodName);
        assertThat(prefix(controller)).isEqualTo(prefix);
        assertThat(method.getAnnotation(PostMapping.class)).isNotNull();
        assertThat(mappingPaths(
                method.getAnnotation(PostMapping.class).value(),
                method.getAnnotation(PostMapping.class).path())).contains(path);
    }

    private static void assertGet(
            Class<?> controller, String methodName, String prefix, String path) {
        Method method = method(controller, methodName);
        assertThat(prefix(controller)).isEqualTo(prefix);
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(mappingPaths(
                method.getAnnotation(GetMapping.class).value(),
                method.getAnnotation(GetMapping.class).path())).contains(path);
    }

    private static String prefix(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        if (mapping == null) {
            return "";
        }
        List<String> paths = mappingPaths(mapping.value(), mapping.path());
        return paths.isEmpty() ? "" : paths.getFirst();
    }

    private static List<String> mappingPaths(String[] values, String[] paths) {
        return Stream.concat(Arrays.stream(values), Arrays.stream(paths)).distinct().toList();
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative));
    }
}
