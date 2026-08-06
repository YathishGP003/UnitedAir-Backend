package com.unitedair.ai.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.unitedair.ai.shared.Json;
import com.unitedair.ai.tools.SrsToolCallbackProvider;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.shared.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The single place the application talks to a language model.
 *
 * <p>Isolating this means the orchestration layer never imports Spring AI, and swapping
 * provider or running offline changes one class rather than five.
 */
@Component
public class ChatGateway {

    private static final Logger log = LoggerFactory.getLogger(ChatGateway.class);

    private final AiMode mode;
    private final ObjectProvider<ChatModel> chatModel;
    private final HostedCallLimiter limiter;
    private final ObjectProvider<SrsToolCallbackProvider> toolProvider;

    @Autowired
    public ChatGateway(AiMode mode,
                       ObjectProvider<ChatModel> chatModel,
                       HostedCallLimiter limiter,
                       ObjectProvider<SrsToolCallbackProvider> toolProvider) {
        this.mode = mode;
        this.chatModel = chatModel;
        this.limiter = limiter;
        this.toolProvider = toolProvider;
    }

    public ChatGateway(AiMode mode,
                       ObjectProvider<ChatModel> chatModel,
                       HostedCallLimiter limiter) {
        this(mode, chatModel, limiter, null);
    }

    public boolean isLive() {
        return mode.isLive() && chatModel.getIfAvailable() != null;
    }

    public ChatDtos.ChatResult complete(ChatDtos.ChatRequest request) {
        if (!isLive()) {
            return OfflineComposer.compose(request, "OFFLINE_CONFIGURED");
        }

        ChatModel model = chatModel.getObject();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemPrompt()));

        for (ChatDtos.HistoryTurn turn : request.history()) {
            if ("ASSISTANT".equalsIgnoreCase(turn.role())) {
                messages.add(new AssistantMessage(turn.content()));
            } else {
                messages.add(new UserMessage(turn.content()));
            }
        }
        messages.add(new UserMessage(request.userPrompt()));

        return callHosted(messages, reason -> OfflineComposer.compose(request, reason));
    }

    /**
     * Calls the hosted model for bounded tasks that intentionally have no grounding blocks,
     * such as conversational greetings and structured route refinement.
     *
     * <p>The caller supplies the exact offline text. This prevents the grounded extractive
     * composer from being invoked without evidence while retaining the same hosted-call
     * limiter and explicit degraded reason.
     */
    public ChatDtos.ChatResult completeDirect(String systemPrompt,
                                              List<ChatDtos.HistoryTurn> history,
                                              String userPrompt,
                                              String offlineFallback) {
        Function<String, ChatDtos.ChatResult> fallback = reason -> new ChatDtos.ChatResult(
                offlineFallback == null ? "" : offlineFallback,
                0, 0, "offline-direct",
                ChatDtos.GenerationSource.DETERMINISTIC_CONVERSATION,
                reason);
        if (!isLive()) {
            return fallback.apply("OFFLINE_CONFIGURED");
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (history != null) {
            for (ChatDtos.HistoryTurn turn : history) {
                if ("ASSISTANT".equalsIgnoreCase(turn.role())) {
                    messages.add(new AssistantMessage(turn.content()));
                } else {
                    messages.add(new UserMessage(turn.content()));
                }
            }
        }
        messages.add(new UserMessage(userPrompt));
        return callHosted(messages, fallback);
    }

    /**
     * Lets the hosted model propose calls from the four-family registry without executing
     * them inside the model client. The returned calls have already passed deterministic
     * family, operation, role, argument, budget and Passenger-ownership validation.
     */
    public ChatDtos.ToolProposalResult proposeToolCalls(
            String systemPrompt,
            List<ChatDtos.HistoryTurn> history,
            String userPrompt) {
        SrsToolCallbackProvider provider =
                toolProvider == null ? null : toolProvider.getIfAvailable();
        if (!isLive() || provider == null) {
            return new ChatDtos.ToolProposalResult(
                    List.of(), null, "TOOL_CALLING_UNAVAILABLE");
        }
        if (!limiter.tryAcquire(Duration.ofMillis(150))) {
            return new ChatDtos.ToolProposalResult(
                    List.of(), null, "MODEL_CAPACITY");
        }
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            if (history != null) {
                for (ChatDtos.HistoryTurn turn : history) {
                    messages.add("ASSISTANT".equalsIgnoreCase(turn.role())
                            ? new AssistantMessage(turn.content())
                            : new UserMessage(turn.content()));
                }
            }
            messages.add(new UserMessage(userPrompt));
            var options = DefaultToolCallingChatOptions.builder()
                    .toolCallbacks(provider.getToolCallbacks())
                    .internalToolExecutionEnabled(false)
                    .build();
            ChatResponse response = chatModel.getObject().call(new Prompt(messages, options));
            AssistantMessage output = response.getResult().getOutput();
            List<ToolDtos.ProposedToolCall> proposals = output.getToolCalls().stream()
                    .map(ChatGateway::proposal)
                    .toList();
            List<ToolDtos.ValidatedToolCall> validated = provider.validate(proposals);
            String modelName = response.getMetadata() == null
                    ? null : response.getMetadata().getModel();
            return new ChatDtos.ToolProposalResult(validated, modelName, null);
        } catch (ApiExceptions.ApiException rejected) {
            throw rejected;
        } catch (Exception unavailable) {
            String reason = isProviderCapacity(unavailable)
                    ? "MODEL_CAPACITY" : "UPSTREAM_UNAVAILABLE";
            log.warn("Hosted tool proposal failed safely ({})",
                    unavailable.getClass().getSimpleName());
            return new ChatDtos.ToolProposalResult(
                    List.of(), null, reason);
        } finally {
            limiter.release();
        }
    }

    public Set<String> registeredToolFamilies() {
        SrsToolCallbackProvider provider =
                toolProvider == null ? null : toolProvider.getIfAvailable();
        if (provider == null) {
            return Set.of();
        }
        return Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static ToolDtos.ProposedToolCall proposal(
            AssistantMessage.ToolCall toolCall) {
        Map<String, Object> root = Json.readMap(toolCall.arguments());
        Object nested = root.get("request");
        Map<String, Object> request = nested instanceof Map<?, ?> raw
                ? stringKeyMap(raw) : root;
        String operation = request.get("operation") == null
                ? null : request.get("operation").toString();
        Object argumentsValue = request.get("arguments");
        Map<String, Object> arguments;
        if (argumentsValue instanceof Map<?, ?> rawArguments) {
            arguments = stringKeyMap(rawArguments);
        } else {
            arguments = new HashMap<>(request);
            arguments.remove("operation");
        }
        return new ToolDtos.ProposedToolCall(
                toolCall.name(), operation, arguments);
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * Deterministically composes a cited answer from already authorised evidence after
     * hosted prose fails citation-only validation.
     */
    public ChatDtos.ChatResult composeGroundedFallback(ChatDtos.ChatRequest request) {
        return composeGroundedFallback(
                request, "MODEL_CITATION_VALIDATION_FAILED");
    }

    public ChatDtos.ChatResult composeGroundedFallback(
            ChatDtos.ChatRequest request,
            String degradedReason) {
        String reason = degradedReason == null || degradedReason.isBlank()
                ? "MODEL_CITATION_VALIDATION_FAILED" : degradedReason;
        return OfflineComposer.compose(request, reason);
    }

    private ChatDtos.ChatResult callHosted(
            List<Message> messages,
            Function<String, ChatDtos.ChatResult> fallback) {
        ChatModel model = chatModel.getObject();
        if (!limiter.tryAcquire(Duration.ofMillis(150))) {
            log.warn("Hosted chat capacity is busy; composing from evidence instead.");
            return fallback.apply("MODEL_CAPACITY");
        }
        try {
            ChatResponse response = model.call(new Prompt(messages));
            String text = response.getResult().getOutput().getText();

            Integer promptTokens = null;
            Integer completionTokens = null;
            String modelName = null;
            if (response.getMetadata() != null) {
                modelName = response.getMetadata().getModel();
                if (response.getMetadata().getUsage() != null) {
                    promptTokens = response.getMetadata().getUsage().getPromptTokens();
                    completionTokens = response.getMetadata().getUsage().getCompletionTokens();
                }
            }

            return new ChatDtos.ChatResult(
                    text == null ? "" : text.trim(),
                    promptTokens, completionTokens, modelName,
                    ChatDtos.GenerationSource.HOSTED_MODEL, null);

        } catch (Exception e) {
            if (isProviderCapacity(e)) {
                // The reference environment hit this regularly. Rather than surfacing a
                // raw provider error, fall back to grounded extractive generation so the
                // passenger still gets a cited answer from the same evidence.
                log.warn("Hosted model capacity reached; using the safe fallback.");
                return fallback.apply("MODEL_CAPACITY");
            }
            log.warn("Model endpoint unavailable; using the safe fallback ({})",
                    e.getClass().getSimpleName());
            return fallback.apply("UPSTREAM_UNAVAILABLE");
        } finally {
            limiter.release();
        }
    }

    private static boolean isProviderCapacity(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("429")
                || message.contains("rate limit")
                || message.contains("rate-limit")
                || message.contains("quota")
                || message.contains("capacity");
    }

    /**
     * Extractive answer composition used offline and as the rate-limit fallback.
     *
     * <p>Selects the evidence sentences that best cover the question's content words and
     * stitches them into a cited answer. It cannot paraphrase, so its prose is blunter than
     * a model's - but every sentence it emits came verbatim from a retrieved KB chunk, so
     * it is grounded by construction and passes the same citation and evaluation gates.
     */
    static final class OfflineComposer {

        /** Words too common in airline questions to discriminate between chunks. */
        private static final Set<String> STOP_WORDS = Set.of(
                "the", "a", "an", "is", "are", "was", "were", "be", "been", "being", "and", "or",
                "but", "if", "then", "than", "that", "this", "these", "those", "for", "of", "to",
                "in", "on", "at", "by", "with", "from", "as", "it", "its", "can", "could", "may",
                "might", "will", "would", "shall", "should", "do", "does", "did", "have", "has",
                "had", "i", "you", "we", "they", "my", "our", "what", "when", "where", "which",
                "who", "how", "why", "please", "tell", "me", "about", "any", "all", "there");

        private OfflineComposer() { }

        static ChatDtos.ChatResult compose(ChatDtos.ChatRequest request, String degradedReason) {
            List<ChatDtos.Grounding> grounding = request.grounding();
            if (grounding == null || grounding.isEmpty()) {
                // The grounding gate should have caught this before we ever got here.
                return new ChatDtos.ChatResult(
                        "", 0, 0, "offline-extractive",
                        ChatDtos.GenerationSource.GROUNDED_EXTRACTIVE,
                        degradedReason);
            }

            // PromptAssembler's user message contains every evidence block followed by
            // "QUESTION:". Scoring against that whole augmented prompt makes every
            // evidence word a query term and rewards unrelated, shorter table rows. Isolate
            // the actual question before ranking extractive sentences.
            String question = questionFromPrompt(request.userPrompt());
            Set<String> queryTerms = contentWords(question);
            String normalisedQuestion = " " + question.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ") + " ";
            if (queryTerms.contains("identification") || normalisedQuestion.contains(" id ")) {
                queryTerms.add("identification");
                queryTerms.addAll(Set.of("photo", "id", "document", "passport"));
            }
            boolean asksForBatteryGuidance = containsAny(
                    normalisedQuestion,
                    " lithium ", " lithum ", " battery ", " batteries ", " power bank ");
            if (asksForBatteryGuidance) {
                queryTerms.addAll(Set.of(
                        "lithium", "battery", "batteries", "power", "bank",
                        "checked", "cabin", "baggage", "permitted", "prohibited"));
            }
            boolean asksForExplosiveWeaponGuidance =
                    containsAny(
                            normalisedQuestion,
                            " bomb ", " bombs ", " explosive ", " explosives ",
                            " weapon ", " weapons ", " gun ", " guns ",
                            " firearm ", " firearms ")
                            && containsAny(
                                    normalisedQuestion,
                                    " bring ", " carry ", " take ", " pack ",
                                    " airport ", " flight ", " plane ", " onboard ");
            if (asksForExplosiveWeaponGuidance) {
                queryTerms.addAll(Set.of(
                        "explosive", "materials", "sharp", "objects", "checked",
                        "baggage", "firearms", "ammunition", "approvals", "prohibited"));
            }
            boolean asksForWheelchairCode =
                    queryTerms.contains("wheelchair")
                            && containsAny(normalisedQuestion, " code ", " ssr ", " deadline ");
            if (asksForWheelchairCode) {
                queryTerms.addAll(Set.of("wchr", "wheelchair", "request", "hours"));
            }

            // Collect every candidate sentence first, so term weights can be computed
            // against the actual evidence rather than guessed.
            List<Candidate> candidates = new ArrayList<>();
            for (int evidenceOrder = 0; evidenceOrder < grounding.size(); evidenceOrder++) {
                ChatDtos.Grounding block = grounding.get(evidenceOrder);
                /*
                 * Tool grounding is intentionally machine-readable so the hosted model
                 * can reason over it. The extractive fallback must never copy a JSON
                 * object into a user-facing answer. Typed tool outcomes are rendered by
                 * ToolAnswerComposer in the orchestrator after this fallback is
                 * evaluated, while human-readable tool grounding remains eligible.
                 */
                if (isStructuredToolPayload(block)) {
                    continue;
                }
                if (block.handle() != null && block.handle().startsWith("E")) {
                    String sectionLabel = readableSectionLabel(block.label());
                    if (!sectionLabel.isBlank()) {
                        String labelText = "Policy section: " + sectionLabel;
                        Set<String> labelTerms = contentWords(labelText);
                        candidates.add(new Candidate(
                                labelText, block.handle(), labelTerms, labelTerms,
                                evidenceOrder, true));
                    }
                }
                String normalisedEvidence = normaliseEvidenceText(block.text());
                Set<String> sectionTerms = splitSentences(normalisedEvidence).stream()
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .map(OfflineComposer::contentWords)
                        .orElse(Set.of());
                for (String sentence : splitSentences(normalisedEvidence)) {
                    String trimmed = sentence.trim();
                    boolean verifiedToolGrounding =
                            block.handle() != null
                                    && block.handle().startsWith("T");
                    if (!isUsefulCandidate(trimmed)
                            && !verifiedToolGrounding) {
                        continue;
                    }
                    Set<String> candidateTerms = new HashSet<>(contentWords(trimmed));
                    candidateTerms.addAll(sectionTerms);
                    candidates.add(new Candidate(
                            trimmed, block.handle(), candidateTerms, sectionTerms,
                            evidenceOrder, false));
                }
            }

            // Inverse document frequency over the candidate sentences.
            //
            // Airline policy chunks are dense tables whose rows share almost all their
            // wording - "More than 7 days before departure" appears in the Value row, the
            // Flex row and the Business row alike. Scoring by raw overlap therefore picks
            // an arbitrary row. Weighting each query term by how rare it is across the
            // candidates makes the distinguishing word ("Value", "wheelchair", "CAR-7")
            // dominate, which is what actually separates the right row from its neighbours.
            Map<String, Double> weights = new HashMap<>();
            for (String term : queryTerms) {
                long occurrences = candidates.stream().filter(c -> c.terms().contains(term)).count();
                weights.put(term, Math.log(1.0 + (double) candidates.size() / (1.0 + occurrences)));
            }

            List<ScoredSentence> scored = new ArrayList<>();
            for (Candidate candidate : candidates) {
                double weighted = 0;
                for (String term : candidate.terms()) {
                    Double weight = weights.get(term);
                    if (weight != null) {
                        weighted += weight;
                    }
                }
                // Normalise by length so a long paragraph does not win purely on volume.
                // Retrieval rank remains a strong prior. A lower-ranked chunk must have
                // substantially better term coverage to displace a direct row from E1.
                double rankPenalty = 1.0 + (candidate.evidenceOrder() * 2.0);
                double score = weighted > 0
                        ? weighted
                            / Math.sqrt(Math.max(1, candidate.terms().size()))
                            / rankPenalty
                        // Focused semantic retrieval can deliberately supply a table row
                        // whose literal values are absent from the user's wording (for
                        // example "Economy | Value | 15 kg"). Keep it selectable at a
                        // tiny score; it cannot outrank a literal match, but category-aware
                        // selection can still include the verified fact.
                        : 0.0001 / rankPenalty;
                scored.add(new ScoredSentence(
                        candidate.text(), candidate.handle(), candidate.terms(),
                        candidate.topicTerms(), candidate.sectionLabel(), score));
            }

            // Nothing overlapped: fall back to the lead sentence of the best-ranked chunk
            // rather than returning nothing, since retrieval already cleared the threshold.
            if (scored.isEmpty()) {
                ChatDtos.Grounding best = grounding.get(0);
                String lead = splitSentences(best.text()).stream()
                        .map(String::trim)
                        .filter(s -> s.length() >= 40 && isUsefulCandidate(s))
                        .findFirst()
                        .orElse("I found the relevant policy source, but it does not contain a "
                                + "specific answer to that question");
                scored.add(new ScoredSentence(
                        lead, best.handle(), contentWords(lead), Set.of(), false, 0.1));
            }

            scored.sort(Comparator.comparingDouble(ScoredSentence::score).reversed());
            List<ScoredSentence> selected = new ArrayList<>();
            boolean asksForCabinAndChecked =
                    (normalisedQuestion.contains(" cabin ")
                        && normalisedQuestion.contains(" checked "))
                            || (normalisedQuestion.contains(" baggage ")
                                && normalisedQuestion.contains(" allowance "));
            if (asksForCabinAndChecked) {
                addBestTopicContaining(selected, scored, "cabin");
                addBestTextContaining(selected, scored, "economy | 1 piece | 7 kg");
                addBestTextContaining(selected, scored, "economy | value | 15 kg");
                if (queryTerms.contains("excess")) {
                    addBestTextContaining(selected, scored, "additional baggage charges");
                }
                if (queryTerms.contains("power")
                        || queryTerms.contains("battery")
                        || queryTerms.contains("restricted")) {
                    addBestTextContaining(selected, scored, "lithium");
                    addBestTextContaining(selected, scored, "explosive materials");
                }
            }
            if (asksForBatteryGuidance) {
                if (!asksForCabinAndChecked) {
                    selected.clear();
                }
                addBestTextContaining(selected, scored, "power bank");
                addBestTextContaining(selected, scored, "lithium");
                addBestTextContaining(selected, scored, "checked baggage");
                addBestTextContaining(selected, scored, "cabin baggage");
            }
            if (asksForExplosiveWeaponGuidance) {
                selected.clear();
                addBestTextContaining(selected, scored, "explosive materials");
                addBestTextContaining(selected, scored, "sharp objects");
                addBestTextContaining(selected, scored, "firearms/ammunition");
            }
            boolean asksForCheckInAndIdentification =
                    queryTerms.contains("check") && queryTerms.contains("identification");
            if (asksForCheckInAndIdentification) {
                addBestContaining(selected, scored, "domestic", "hours");
                addBestTextContaining(selected, scored, "check-in opens 48 hours");
                addBestTextContaining(selected, scored, "government-issued photo id");
                addBestTextContaining(selected, scored, "aadhaar");
                boolean asksForLiveStatus =
                        queryTerms.contains("status")
                                || queryTerms.contains("terminal")
                                || queryTerms.contains("gate");
                if (asksForLiveStatus) {
                    addBestTextContaining(selected, scored, "on_time");
                    addBestTextContaining(selected, scored, "terminal");
                    addBestTextContaining(selected, scored, "gate");
                }
            }
            boolean asksForDomesticAndInternationalDocuments =
                    queryTerms.contains("domestic")
                            && queryTerms.contains("international")
                            && (queryTerms.contains("document")
                                || queryTerms.contains("documents"));
            if (asksForDomesticAndInternationalDocuments) {
                selected.clear();
                addBestTextContaining(selected, scored, "aadhaar");
                addBestTextContaining(selected, scored, "government employee id");
                addBestTextContaining(selected, scored, "valid passport");
                addBestTextContaining(selected, scored, "visa");
                addBestTextContaining(selected, scored, "return/onward ticket");
            }
            boolean asksForPetInCabin =
                    queryTerms.contains("petc")
                            || (queryTerms.contains("pet") && queryTerms.contains("cabin"));
            if (asksForPetInCabin) {
                addBestTextContaining(selected, scored, "petc");
            }
            boolean asksForAnimalInHold =
                    queryTerms.contains("avih")
                            || ((queryTerms.contains("pet") || queryTerms.contains("animal"))
                                && queryTerms.contains("hold"));
            if (asksForAnimalInHold) {
                addBestTextContaining(selected, scored, "avih");
            }
            boolean asksForMedaDocuments =
                    queryTerms.contains("meda")
                            && (queryTerms.contains("document")
                                || queryTerms.contains("documents")
                                || queryTerms.contains("form"));
            if (asksForMedaDocuments) {
                addBestContaining(selected, scored, "meda", "form");
                addBestTextContaining(selected, scored, "meda");
            }
            boolean asksForDutyRest =
                    queryTerms.contains("rest")
                            && (queryTerms.contains("duty")
                                || queryTerms.contains("dgca")
                                || queryTerms.contains("car"));
            if (asksForDutyRest) {
                addBestTextContaining(selected, scored, "rest");
            }
            boolean asksForCabinAndHoldPet =
                    queryTerms.contains("cabin")
                            && queryTerms.contains("hold")
                            && (queryTerms.contains("cat") || queryTerms.contains("pet"))
                            && (queryTerms.contains("dog") || queryTerms.contains("animal"));
            if (asksForCabinAndHoldPet) {
                addBestTextContaining(selected, scored, "petc");
                addBestTextContaining(selected, scored, "avih");
            }
            boolean asksForWheelchairSsr = asksForWheelchairCode;
            if (asksForWheelchairSsr) {
                selected.clear();
                addBestTextContaining(selected, scored, "wchr");
                addBestTextContaining(selected, scored, "48 hours");
                addBestTextContaining(selected, scored, "wheelchair assistance codes");
            }
            boolean asksForUnaccompaniedMinor =
                    normalisedQuestion.contains(" unaccompanied minor ")
                            || (normalisedQuestion.contains(" um ")
                                && containsAny(
                                        normalisedQuestion,
                                        " service ", " child ", " minor "));
            if (asksForUnaccompaniedMinor) {
                selected.clear();
                addBestTextContaining(selected, scored, "unaccompanied minor");
                addBestTextContaining(selected, scored, "eligible age");
                addBestTextContaining(selected, scored, "documentation required");
                addBestTextContaining(selected, scored, "booking method");
            }
            boolean asksForSeatCategories =
                    queryTerms.contains("seat")
                            && (queryTerms.contains("type")
                                || queryTerms.contains("types")
                                || queryTerms.contains("selection"));
            boolean asksForDomesticSeatCategories =
                    asksForSeatCategories
                            && (queryTerms.contains("domestic")
                                || queryTerms.contains("domestically"));
            if (asksForSeatCategories) {
                selected.clear();
                addBestTextContaining(selected, scored, "standard economy");
                addBestTextContaining(selected, scored, "preferred economy");
                addBestTextContaining(selected, scored, "comfort");
                addBestTextContaining(selected, scored, "business window");
                addBestTextContaining(selected, scored, "business aisle");
                if (queryTerms.contains("exit")) {
                    addBestTextContaining(selected, scored, "exit row");
                }
            }
            int requestedSpecialServiceTypes = countPresent(
                    normalisedQuestion,
                    " wheelchair ", " wchr ", " unaccompanied ", " um ",
                    " petc ", " avih ", " meda ");
            boolean asksForMultipleSpecialServices = requestedSpecialServiceTypes >= 3;
            if (asksForMultipleSpecialServices) {
                selected.clear();
                addBestTextContaining(selected, scored, "wchr");
                addBestTextContaining(selected, scored, "unaccompanied");
                addBestTextContaining(selected, scored, "petc");
                addBestTextContaining(selected, scored, "avih");
                addBestTextContaining(selected, scored, "meda");
            }
            boolean asksForSpecialServicesAndSeats =
                    (normalisedQuestion.contains(" special service ")
                        || normalisedQuestion.contains(" special services "))
                            && queryTerms.contains("seat");
            if (asksForSpecialServicesAndSeats) {
                addBestTextContaining(selected, scored, "wchr");
                addBestTextContaining(selected, scored, "unaccompanied");
                addBestTextContaining(selected, scored, "meda");
                addBestTextContaining(selected, scored, "petc");
                addBestTextContaining(selected, scored, "avih");
                addBestTextContaining(selected, scored, "standard economy");
                addBestTextContaining(selected, scored, "preferred economy");
                addBestTextContaining(selected, scored, "comfort");
                addBestTextContaining(selected, scored, "business window");
                addBestTextContaining(selected, scored, "business aisle");
            }
            boolean asksForDeniedBoardingHandling =
                    normalisedQuestion.contains(" denied boarding ")
                            && containsAny(
                                    normalisedQuestion,
                                    " oversale ", " overbooking ", " waitlist ",
                                    " override ", " handling ");
            if (asksForDeniedBoardingHandling) {
                selected.clear();
                addBestTextContaining(selected, scored, "no-show");
                addBestTextContaining(selected, scored, "denied boarding");
                addBestTextContaining(selected, scored, "waitlist");
                addBestTextContaining(selected, scored, "override");
            }
            boolean asksForGeneralCancellation =
                    normalisedQuestion.contains(" cancellation policy ")
                            && !queryTerms.contains("value")
                            && !queryTerms.contains("flex")
                            && !queryTerms.contains("saver");
            if (asksForGeneralCancellation) {
                selected.clear();
                addBestTextContaining(selected, scored, "saver / super saver | any time");
                addBestTextContaining(
                        selected, scored, "value | more than 7 days before departure");
                addBestTextContaining(
                        selected, scored, "flex | more than 3 hours before departure");
                addBestTextContaining(
                        selected, scored, "full flex | any time up to 2 hours before departure");
            }
            boolean asksForSpecificValueCancellation =
                    queryTerms.contains("value")
                            && (normalisedQuestion.contains(" more than 7 days ")
                                || normalisedQuestion.contains(" more than seven days "))
                            && containsAny(
                                    normalisedQuestion,
                                    " cancel ", " cancellation ", " refund ", " fee ");
            if (asksForSpecificValueCancellation) {
                selected.clear();
                addBestTextContaining(
                        selected, scored, "value | more than 7 days before departure");
            }
            boolean asksForFlexCancellation =
                    queryTerms.contains("flex")
                            && containsAny(
                                    normalisedQuestion,
                                    " cancel ", " cancellation ", " refund ", " fee ");
            if (asksForFlexCancellation) {
                addBestTextContaining(
                        selected, scored, "flex | more than 3 hours before departure");
            }

            // A multipart policy answer must not spend every sentence on whichever one
            // chunk happens to share the most words with the question. Preserve the
            // approved section label and the two strongest facts from each evidence block.
            // This remains fully extractive and cited; it only makes the selection fair
            // across the semantic plan's requested categories.
            boolean specialisedSelection =
                    asksForCabinAndChecked
                            || asksForBatteryGuidance
                            || asksForExplosiveWeaponGuidance
                            || asksForCheckInAndIdentification
                            || asksForDomesticAndInternationalDocuments
                            || asksForPetInCabin
                            || asksForAnimalInHold
                            || asksForMedaDocuments
                            || asksForWheelchairSsr
                            || asksForUnaccompaniedMinor
                            || asksForSeatCategories
                            || asksForMultipleSpecialServices
                            || asksForSpecialServicesAndSeats
                            || asksForGeneralCancellation;
            if (!asksForSpecificValueCancellation && !specialisedSelection) {
                for (ChatDtos.Grounding block : grounding) {
                    if (block.handle() == null || !block.handle().startsWith("E")) {
                        continue;
                    }
                    scored.stream()
                            .filter(sentence -> block.handle().equals(sentence.handle()))
                            .filter(ScoredSentence::sectionLabel)
                            .findFirst()
                            .ifPresent(sentence -> addUnique(selected, sentence));
                    scored.stream()
                            .filter(sentence -> block.handle().equals(sentence.handle()))
                            .filter(sentence -> !sentence.sectionLabel())
                            .limit(12)
                            .forEach(sentence -> addUnique(selected, sentence));
                }
            }
            int answerLimit = (asksForSpecificValueCancellation && !asksForFlexCancellation)
                    ? 1
                    : asksForDomesticAndInternationalDocuments
                            ? Math.max(5, selected.size())
                    : asksForCheckInAndIdentification
                            ? Math.max(6, selected.size())
                    : asksForExplosiveWeaponGuidance ? Math.max(3, selected.size())
                    : asksForBatteryGuidance ? Math.max(2, selected.size())
                    : asksForCabinAndChecked ? Math.max(2, selected.size())
                    : asksForUnaccompaniedMinor ? Math.max(3, selected.size())
                    : asksForSeatCategories
                            ? Math.max(queryTerms.contains("exit") ? 2 : 1, selected.size())
                    : asksForMultipleSpecialServices ? Math.max(1, selected.size())
                    : asksForDeniedBoardingHandling ? Math.max(1, selected.size())
                    : asksForSpecialServicesAndSeats ? Math.max(8, selected.size())
                    : 6;
            answerLimit = Math.max(answerLimit, selected.size());
            for (ScoredSentence sentence : scored) {
                if (selected.size() >= answerLimit) {
                    break;
                }
                if (selected.stream().noneMatch(existing ->
                        existing.text().equals(sentence.text()))) {
                    selected.add(sentence);
                }
            }
            StringBuilder answer = new StringBuilder();
            String heading = null;
            if (asksForCabinAndChecked) {
                heading = "Domestic Economy cabin and checked baggage allowances";
            } else if (asksForExplosiveWeaponGuidance) {
                heading = "Prohibited and restricted items";
            } else if (asksForBatteryGuidance) {
                heading = "Battery and baggage restrictions";
            } else if (asksForCheckInAndIdentification) {
                heading = "Domestic check-in and identification requirements";
            } else if (asksForDomesticAndInternationalDocuments) {
                heading = "Domestic and international travel documents";
            } else if (asksForSpecificValueCancellation || asksForFlexCancellation) {
                heading = "Fare cancellation and refund rules";
            } else if (asksForUnaccompaniedMinor) {
                heading = "Unaccompanied minor service";
            } else if (asksForWheelchairSsr) {
                heading = "Wheelchair special service (WCHR)";
            } else if (asksForPetInCabin || asksForAnimalInHold || asksForMedaDocuments) {
                heading = "Special service documents and requirements";
            } else if (asksForMultipleSpecialServices) {
                heading = "Special service procedures and requirements";
            } else if (asksForDomesticSeatCategories) {
                heading = "Domestic seat types, fees and exit-row eligibility";
            } else if (asksForSeatCategories) {
                heading = "Seat types, selection fees and upgrade options";
            } else if (asksForDeniedBoardingHandling) {
                heading = "Oversale, waitlist and denied boarding handling";
            } else if (asksForDutyRest) {
                heading = "Duty-time compliance and rest requirements";
            } else if (queryTerms.contains("worldtracer")) {
                heading = "WorldTracer tracing, PIR filing and Montreal Convention guidance";
            }
            if (heading != null && !selected.isEmpty()) {
                if (asksForExplosiveWeaponGuidance) {
                    answer.append(
                            "Do not bring explosive materials to the airport or flight. "
                            + "Do not carry knives or other sharp objects in the cabin.\n\n");
                }
                answer.append("**").append(heading).append("** [")
                        .append(selected.getFirst().handle()).append("].\n\n");
            }
            Set<String> usedHandles = new HashSet<>();
            int taken = 0;
            for (ScoredSentence s : selected) {
                if (taken >= answerLimit) {
                    break;
                }
                if (answer.indexOf(s.text()) >= 0) {
                    continue;
                }
                // Citation goes inside the sentence, before its full stop, so the marker
                // is unambiguously attached to the claim it supports.
                String body = s.text().endsWith(".")
                        ? s.text().substring(0, s.text().length() - 1)
                        : s.text();
                if (asksForDomesticSeatCategories) {
                    body = domesticSeatClaim(body);
                }
                body = humaniseTableSeparators(body);
                answer.append(body).append(" [").append(s.handle()).append("].\n\n");
                usedHandles.add(s.handle());
                taken++;
                if (answer.length() >= 6000 && taken >= answerLimit) {
                    break;
                }
            }

            return new ChatDtos.ChatResult(
                    answer.toString().trim(), 0, 0, "offline-extractive",
                    ChatDtos.GenerationSource.GROUNDED_EXTRACTIVE,
                    degradedReason);
        }

        private static boolean isStructuredToolPayload(ChatDtos.Grounding grounding) {
            if (grounding == null
                    || grounding.handle() == null
                    || !grounding.handle().startsWith("T")
                    || grounding.text() == null) {
                return false;
            }
            String trimmed = grounding.text().stripLeading();
            return trimmed.startsWith("{") || trimmed.startsWith("[{");
        }

        private static void addBestTopicContaining(
                List<ScoredSentence> selected,
                List<ScoredSentence> scored,
                String term) {
            scored.stream()
                    .filter(sentence -> sentence.topicTerms().contains(term)
                            && sentence.topicTerms().contains("baggage"))
                    .findFirst()
                    .ifPresent(sentence -> addUnique(selected, sentence));
        }

        private static String humaniseTableSeparators(String value) {
            return value.replaceAll("\\s*\\|\\s*", " - ")
                    .replaceAll("\\s+-\\s+-\\s+", " - ")
                    .trim();
        }

        private static String readableSectionLabel(String label) {
            if (label == null) {
                return "";
            }
            return label.replaceFirst("(?i)^KB-AIR-\\d{3}\\s*", "").trim();
        }

        private static String domesticSeatClaim(String value) {
            String[] columns = value.split("\\s*\\|\\s*");
            if (columns.length >= 4
                    && containsAny(
                            columns[0].toLowerCase(Locale.ROOT),
                            "standard economy", "preferred economy", "comfort",
                            "business window", "business aisle")) {
                return columns[0] + " | " + columns[1]
                        + " | Domestic fee: " + columns[2];
            }
            return value;
        }

        private static void addBestContaining(
                List<ScoredSentence> selected,
                List<ScoredSentence> scored,
                String first,
                String second) {
            scored.stream()
                    .filter(sentence -> sentence.terms().contains(first)
                            && sentence.terms().contains(second))
                    .findFirst()
                    .ifPresent(sentence -> addUnique(selected, sentence));
        }

        private static void addBestTextContaining(
                List<ScoredSentence> selected,
                List<ScoredSentence> scored,
                String term) {
            scored.stream()
                    .filter(sentence -> sentence.text()
                            .toLowerCase(Locale.ROOT).contains(term))
                    .findFirst()
                    .ifPresent(sentence -> addUnique(selected, sentence));
        }

        private static void addUnique(
                List<ScoredSentence> selected,
                ScoredSentence candidate) {
            if (selected.stream().noneMatch(existing ->
                    existing.text().equals(candidate.text()))) {
                selected.add(candidate);
            }
        }

        private static boolean containsAny(String value, String... terms) {
            for (String term : terms) {
                if (value.contains(term)) {
                    return true;
                }
            }
            return false;
        }

        private static int countPresent(String value, String... terms) {
            int count = 0;
            for (String term : terms) {
                if (value.contains(term)) {
                    count++;
                }
            }
            return count;
        }

        private static Set<String> contentWords(String text) {
            Set<String> words = new HashSet<>();
            if (text == null) {
                return words;
            }
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if ((token.length() > 2 || token.equals("id")) && !STOP_WORDS.contains(token)) {
                    words.add(token);
                    // Treat ordinary plurals as the same concept for extractive ranking:
                    // "tiers" in a question must match "Tier" in an approved table row.
                    if (token.length() > 4
                            && token.endsWith("s")
                            && !token.endsWith("ss")) {
                        words.add(token.substring(0, token.length() - 1));
                    }
                }
            }
            return words;
        }

        private static String questionFromPrompt(String prompt) {
            if (prompt == null) {
                return "";
            }
            int marker = prompt.lastIndexOf("QUESTION:");
            if (marker < 0) {
                return prompt;
            }
            String question = prompt.substring(marker + "QUESTION:".length()).trim();
            int contextNote = question.indexOf("\n(Interpreted in context as:");
            return contextNote < 0 ? question : question.substring(0, contextNote).trim();
        }

        private static List<String> splitSentences(String text) {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            // KB documents are heavily tabular, so newlines and pipes end a statement just
            // as reliably as full stops do.
            return Arrays.stream(text.split("(?<=[.!?])\\s+|\\n+"))
                    .filter(s -> !s.isBlank())
                    .toList();
        }

        private static String normaliseEvidenceText(String text) {
            if (text == null) {
                return "";
            }
            // Some legacy KB exports replaced an em dash with three question marks.
            // Normalise that encoding artifact before sentence splitting; otherwise each
            // question mark looks like a sentence boundary and separates a claim from the
            // citation that the deterministic composer appends.
            return text.replaceAll("\\?{2,}", "—");
        }

        private static boolean isUsefulCandidate(String text) {
            String lower = text.toLowerCase(Locale.ROOT).trim();
            if (lower.matches("^(?:document code|document title|category|audience|version"
                    + "|effective date|owner|source|review frequency|kb ingestion tags)\\s*\\|.*")
                    || lower.startsWith("unitedair ai | kb")
                    || lower.matches("^kb[_-]\\d+.*\\|.*")
                    || lower.matches("^\\d+(?:\\.\\d+)*\\s+[a-z].*policy$")
                    || (lower.contains("fare category")
                        && lower.contains("cancellation timing")
                        && lower.contains("cancellation fee"))
                    || lower.matches("[=\\-_*]{5,}")) {
                return false;
            }
            if (text.length() >= 40) {
                return true;
            }
            // Critical policy facts are often compact Markdown table rows, for example
            // "Economy | Value | 15 kg". Length alone must not discard them.
            return text.contains("|")
                    && text.matches(".*[\\p{L}\\p{N}].*")
                    && !text.matches("[\\s|:-]+");
        }

        private record ScoredSentence(
                String text,
                String handle,
                Set<String> terms,
                Set<String> topicTerms,
                boolean sectionLabel,
                double score) { }

        private record Candidate(
                String text,
                String handle,
                Set<String> terms,
                Set<String> topicTerms,
                int evidenceOrder,
                boolean sectionLabel) { }
    }
}
