package com.unitedair.ai.orchestration;

import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import org.springframework.stereotype.Component;

/** Builds authenticated retrieval filters and applies only high-confidence document hints. */
@Component
public class RetrievalFilterFactory {

    public RetrievalDtos.Filter create(
            Role actorRole,
            OrchestrationDtos.Classification classification) {
        return create(actorRole, classification, null);
    }

    public RetrievalDtos.Filter create(
            Role actorRole,
            OrchestrationDtos.Classification classification,
            AnswerRequirements requirements) {
        RetrievalDtos.Filter filter = RetrievalDtos.Filter.forRole(actorRole);
        java.util.Set<String> documents = new java.util.LinkedHashSet<>();
        if (classification.documentCodeHints() != null) {
            documents.addAll(classification.documentCodeHints());
        }
        if (requirements != null) {
            documents.addAll(requirements.documentCodeHints());
        }
        if (!documents.isEmpty() && classification.confidence() >= 0.80) {
            return filter.withDocumentCodes(documents);
        }
        return filter;
    }
}
