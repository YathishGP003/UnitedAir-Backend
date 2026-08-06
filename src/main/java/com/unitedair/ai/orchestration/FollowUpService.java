package com.unitedair.ai.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.unitedair.ai.grounding.CitationBuilder;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.knowledge.RetrievalDtos;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.stereotype.Component;

/** Builds actor-aware follow-ups that retain the handles proving they are answerable. */
@Component
public class FollowUpService {

    public List<FollowUpSuggestion> suggest(
            Role role,
            List<RetrievalDtos.Ranked> evidence,
            List<ToolDtos.ToolOutcome> toolOutcomes,
            boolean escalated) {
        Role actor = role == null ? Role.PASSENGER : role;
        if (escalated) {
            return List.of(
                    new FollowUpSuggestion(
                            "Would you like the escalation reference and contact channel?",
                            actor, List.of("T1"), FollowUpKind.ESCALATION_CHANNEL),
                    new FollowUpSuggestion(
                            "Shall I summarise the details recorded for the support team?",
                            actor, List.of("T1"), FollowUpKind.ESCALATION_CHANNEL));
        }

        List<FollowUpSuggestion> suggestions = new ArrayList<>();
        addToolSuggestions(suggestions, actor, toolOutcomes);
        if (suggestions.size() < 2 && evidence != null) {
            for (RetrievalDtos.Ranked ranked : evidence) {
                String section = ranked.chunk().section();
                if (section == null || section.isBlank()
                        || "Introduction".equalsIgnoreCase(section)) {
                    continue;
                }
                String cleaned = section
                        .replaceAll("^\\d+(?:\\.\\d+)*\\.?\\s*", "")
                        .trim();
                if (cleaned.length() < 4) {
                    continue;
                }
                String prefix = actor == Role.PASSENGER
                        ? "As a Passenger, would you like me to explain "
                        : actor == Role.AIRLINE_STAFF
                                ? "As Airline Staff, would you like to review "
                                : "As an Admin, would you like to inspect ";
                suggestions.add(new FollowUpSuggestion(
                        prefix + lowerFirst(cleaned) + "?",
                        actor,
                        List.of(CitationBuilder.handleFor(ranked.rank())),
                        FollowUpKind.POLICY));
                if (suggestions.size() >= 2) {
                    break;
                }
            }
        }
        if (!suggestions.isEmpty()) {
            FollowUpSuggestion anchor = suggestions.getFirst();
            if (distinct(suggestions).size() < 2) {
                suggestions.add(new FollowUpSuggestion(
                        actor == Role.PASSENGER
                                ? "As a Passenger, would you like to see how this applies to your journey?"
                                : actor == Role.AIRLINE_STAFF
                                        ? "As Airline Staff, would you like to review the next operational step?"
                                        : "As an Admin, would you like to inspect the governing source metadata?",
                        actor,
                        anchor.sourceCitationHandles(),
                        FollowUpKind.POLICY));
            }
            if (distinct(suggestions).size() < 2) {
                suggestions.add(new FollowUpSuggestion(
                        "Would you like another detail from the same verified source?",
                        actor,
                        anchor.sourceCitationHandles(),
                        FollowUpKind.POLICY));
            }
        }
        return distinct(suggestions).stream().limit(2).toList();
    }

    public List<String> texts(List<FollowUpSuggestion> suggestions) {
        return suggestions == null
                ? List.of()
                : suggestions.stream().map(FollowUpSuggestion::text).toList();
    }

    private void addToolSuggestions(
            List<FollowUpSuggestion> suggestions,
            Role role,
            List<ToolDtos.ToolOutcome> outcomes) {
        if (outcomes == null) {
            return;
        }
        int handle = 0;
        for (ToolDtos.ToolOutcome outcome : outcomes) {
            if (!outcome.success()) {
                continue;
            }
            String source = "T" + (++handle);
            if ("FlightSearchTool".equals(outcome.toolName())) {
                suggestions.add(suggestion(
                        role,
                        "compare the baggage included with each fare",
                        source));
                suggestions.add(suggestion(
                        role,
                        "review which displayed fares are refundable",
                        source));
                return;
            }
            if ("BookingManagementTool".equals(outcome.toolName())) {
                suggestions.add(suggestion(
                        role,
                        "review the fare and baggage rules for this booking",
                        source));
                suggestions.add(suggestion(
                        role,
                        "check the verified refund conditions for this booking",
                        source));
                return;
            }
            if ("CheckInStatusTool".equals(outcome.toolName())) {
                suggestions.add(suggestion(
                        role,
                        "check the latest gate and flight status",
                        source));
                suggestions.add(suggestion(
                        role,
                        "review the check-in and boarding requirements",
                        source));
                return;
            }
            if ("OperationalDataAgent".equals(outcome.toolName())) {
                String dataset = outcome.request() == null
                        ? "records"
                        : String.valueOf(outcome.request()
                                .getOrDefault("dataset", "records"))
                                .toLowerCase(Locale.ROOT)
                                .replace('_', ' ');
                suggestions.add(suggestion(
                        role,
                        "filter these verified " + dataset + " by status or date",
                        source));
                suggestions.add(suggestion(
                        role,
                        "review the next action for one of these verified records",
                        source));
                return;
            }
        }
    }

    private FollowUpSuggestion suggestion(Role role, String action, String handle) {
        String prefix = role == Role.PASSENGER
                ? "As a Passenger, would you like to "
                : role == Role.AIRLINE_STAFF
                        ? "As Airline Staff, would you like to "
                        : "As an Admin, would you like to ";
        return new FollowUpSuggestion(
                prefix + action + "?",
                role,
                List.of(handle),
                FollowUpKind.ACTION);
    }

    private List<FollowUpSuggestion> distinct(List<FollowUpSuggestion> values) {
        Set<String> seen = new LinkedHashSet<>();
        return values.stream()
                .filter(value -> seen.add(value.text().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String lowerFirst(String value) {
        return value.isEmpty()
                ? value
                : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    public record FollowUpSuggestion(
            String text,
            Role actorRole,
            List<String> sourceCitationHandles,
            FollowUpKind kind) {

        public FollowUpSuggestion {
            sourceCitationHandles = sourceCitationHandles == null
                    ? List.of() : List.copyOf(sourceCitationHandles);
        }
    }

    public enum FollowUpKind {
        POLICY,
        ACTION,
        ESCALATION_CHANNEL
    }
}
