package com.unitedair.ai.orchestration;

import com.unitedair.ai.knowledge.RetrievalDtos;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Independent semantic guard against admitting lexically similar off-topic evidence. */
@Component
public class QueryEvidenceRelevance {

    public Result evaluate(
            String currentQuery,
            OrchestrationDtos.Classification route,
            List<RetrievalDtos.Ranked> evidence) {
        return evaluate(
                currentQuery,
                route,
                route == null
                        ? null : AnswerRequirements.from(currentQuery, route),
                evidence);
    }

    public Result evaluate(
            String currentQuery,
            OrchestrationDtos.Classification route,
            AnswerRequirements requirements,
            List<RetrievalDtos.Ranked> evidence) {
        if (route == null || route.intent() == OrchestrationDtos.Intent.OUT_OF_SCOPE) {
            return new Result(false, "OUT_OF_SCOPE_QUERY");
        }
        if (evidence == null || evidence.isEmpty() || !route.needsKb()) {
            return new Result(true, "NO_KB_EVIDENCE_REQUIRED");
        }

        AnswerRequirements effectiveRequirements = requirements == null
                ? AnswerRequirements.from(currentQuery, route)
                : requirements;
        Set<String> expectedDocuments = new LinkedHashSet<>(
                route.documentCodeHints());
        expectedDocuments.addAll(effectiveRequirements.documentCodeHints());
        if (!expectedDocuments.isEmpty()) {
            boolean matchingDocument = evidence.stream()
                    .map(RetrievalDtos.Ranked::chunk)
                    .map(RetrievalDtos.Chunk::documentCode)
                    .anyMatch(expectedDocuments::contains);
            if (matchingDocument) {
                return new Result(true, "REQUESTED_TOPIC_DOCUMENT");
            }
        }

        String query = currentQuery == null
                ? "" : currentQuery.toLowerCase(Locale.ROOT);
        boolean airlineTerm = containsAny(query,
                "unitedair", "flight", "booking", "pnr", "fare", "baggage",
                "refund", "cancel", "check-in", "check in", "seat", "meal",
                "airport", "boarding", "passenger", "codeshare", "interline",
                "proration", "aircraft", "mel", "disruption", "worldtracer",
                "pir", "montreal", "yield", "revenue band", "ffp",
                "special service", "wheelchair", "iata", "dgca",
                "lithium", "battery", "restricted item", "dangerous goods",
                "return leg", "return flight", "no-show", "no show",
                "return sector", "missed the first leg", "missed my first leg",
                "compensation", "denied boarding", "overbooking",
                "unaccompanied minor", "unaccompanied-minor", "petc", "avih",
                "business class", "economy class", "cabin class");
        return airlineTerm
                ? new Result(true, expectedDocuments.isEmpty()
                        ? "AIRLINE_TOPIC" : "AIRLINE_TOPIC_HINT_FALLBACK")
                : new Result(false, expectedDocuments.isEmpty()
                        ? "NO_AIRLINE_TOPIC" : "EVIDENCE_TOPIC_MISMATCH");
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public record Result(boolean relevant, String reason) { }
}
