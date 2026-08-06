package com.unitedair.ai.orchestration;

import com.unitedair.ai.llm.ChatDtos;
import com.unitedair.ai.llm.ChatGateway;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Handles safe turns that intentionally do not need tools or Knowledge Base evidence. */
@Component
public class ConversationResponder {

    private static final String SMALL_TALK_SYSTEM = """
            You are UnitedAir AI. Reply naturally and briefly to this conversational turn.
            Be warm and professional. Do not invent flight, booking, policy, account, or
            operational facts. Keep the reply within the UnitedAir travel-assistant role.
            Return only the response text, with no labels or Markdown heading.
            """;
    private static final String SCOPED_REFUSAL_SYSTEM = """
            You are the safety and scope response layer for UnitedAir AI.
            Do not answer unrelated questions and never execute, simulate, or explain
            destructive database/system operations.
            If the request concerns weapons, explosives, violence, evasion, or an active
            safety threat, refuse help with harm, advise the user not to bring dangerous
            items to the airport, and direct an immediate threat to airport security or
            local emergency services. Do not provide operational details that facilitate
            harm. Otherwise, briefly say that you can only assist with UnitedAir travel.
            Return only the response text, with no labels or Markdown heading.
            """;
    private static final List<String> AIRLINE_FOLLOWUPS = List.of(
            "Would you like me to search for a UnitedAir flight?",
            "Can I help with baggage, check-in, a booking or a refund?");

    private final ChatGateway chatGateway;

    public ConversationResponder(ChatGateway chatGateway) {
        this.chatGateway = chatGateway;
    }

    public Response respond(OrchestrationDtos.Intent route,
                            String query,
                            List<ChatDtos.HistoryTurn> history,
                            List<String> missingParameters) {
        return switch (route) {
            case SMALL_TALK -> smallTalk(query, history);
            case BOOK_FLIGHT -> deterministic(
                    "New ticket booking is available from the Passenger workspace.");
            case CLARIFICATION -> deterministic(clarification(missingParameters));
            case OUT_OF_SCOPE -> outOfScope(query, history);
            default -> throw new IllegalArgumentException("Unsupported conversational route: " + route);
        };
    }

    private Response smallTalk(String query, List<ChatDtos.HistoryTurn> history) {
        String fallback = offlineSmallTalk(query);
        ChatDtos.ChatResult result = chatGateway.completeDirect(
                SMALL_TALK_SYSTEM, history, query, fallback);
        result = nonNullResult(result, fallback);
        String text = usable(result.text(), fallback);
        return new Response(text, AIRLINE_FOLLOWUPS, withText(result, text));
    }

    private Response deterministic(String text) {
        return new Response(
                text,
                AIRLINE_FOLLOWUPS,
                new ChatDtos.ChatResult(
                        text, 0, 0, "deterministic-conversation",
                        ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                        null));
    }

    private Response outOfScope(String query, List<ChatDtos.HistoryTurn> history) {
        String fallback = safetySensitive(query)
                ? "I can’t help with bringing bombs, explosives, or weapons to an airport. "
                    + "Do not bring them. If there is an explosive device or an immediate "
                    + "threat, move away and contact airport security or local emergency "
                    + "services now."
                : "I can only help with UnitedAir flights, bookings, baggage, "
                    + "refunds, check-in, seats, and airline services.";
        ChatDtos.ChatResult result = chatGateway.completeDirect(
                SCOPED_REFUSAL_SYSTEM, history, query, fallback);
        result = nonNullResult(result, fallback);
        String text = usable(result.text(), fallback);
        return new Response(
                text,
                List.of(),
                withText(result, text));
    }

    private static boolean safetySensitive(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return lower.contains("bomb")
                || lower.contains("explosive")
                || lower.contains("weapon")
                || lower.contains("knife")
                || lower.contains("gun")
                || lower.contains("hijack");
    }

    private static ChatDtos.ChatResult nonNullResult(
            ChatDtos.ChatResult result, String fallback) {
        if (result != null) {
            return result;
        }
        return new ChatDtos.ChatResult(
                fallback, 0, 0, "deterministic-conversation",
                ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                "MODEL_RESULT_UNAVAILABLE");
    }

    private static String usable(String generated, String fallback) {
        return generated == null || generated.isBlank() ? fallback : generated.trim();
    }

    private static ChatDtos.ChatResult withText(
            ChatDtos.ChatResult original, String text) {
        return new ChatDtos.ChatResult(
                text,
                original.promptTokens(),
                original.completionTokens(),
                original.model(),
                original.generationSource(),
                original.degradedReason());
    }

    private String clarification(List<String> missing) {
        List<String> values = missing == null ? List.of() : missing;
        boolean origin = values.contains("origin");
        boolean destination = values.contains("destination");
        if (origin && destination) {
            return "Which origin and destination cities or airport codes would you like to search?";
        }
        if (origin) {
            return "Which origin city or airport code will you be departing from?";
        }
        if (destination) {
            return "Which destination city or airport code would you like to fly to?";
        }
        if (values.contains("pnr")) {
            return "Please share the six-character booking reference (PNR) for that request.";
        }
        if (values.contains("flightnumber")) {
            return "Which UnitedAir flight number would you like me to check?";
        }
        if (values.contains("date") || values.contains("travelDate")) {
            return "What travel date should I use?";
        }
        if (values.contains("request")) {
            return """
                    I can help, but I need to know which area you mean:
                    1. Search for a flight or check a flight status
                    2. Work with an existing booking, check-in or refund
                    3. Explain baggage, documents or another travel policy
                    Which one is closest?""";
        }
        if (values.contains("account")) {
            return "Use your company sign-in password reset option or contact your UnitedAir "
                    + "access administrator. For security, do not share your password in chat.";
        }
        return "What journey or booking details should I use for that request?";
    }

    private String offlineSmallTalk(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (lower.contains("thank")) {
            return "You’re welcome! I can help with your UnitedAir journey whenever you’re ready.";
        }
        if (lower.contains("bye") || lower.contains("goodbye")) {
            return "Safe travels! I’ll be here if you need more UnitedAir help.";
        }
        if (lower.contains("what can") || lower.contains("who are")) {
            return "I’m UnitedAir AI. I can help with flights, bookings, baggage, refunds and check-in.";
        }
        return "Hello! I’m UnitedAir AI. How can I help with your journey today?";
    }

    public record Response(
            String text,
            List<String> followups,
            ChatDtos.ChatResult modelResult) { }
}
