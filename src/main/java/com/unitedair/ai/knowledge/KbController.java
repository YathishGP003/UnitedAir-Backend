package com.unitedair.ai.knowledge;

import java.io.IOException;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.orchestration.AnswerRequirements;
import com.unitedair.ai.orchestration.RetrievalQueryExpander;
import com.unitedair.ai.shared.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Knowledge Base management.
 *
 * <p>FR-031 restricts ingestion to Admins. That is enforced here with {@code @PreAuthorize}
 * on the methods themselves rather than only in the URL rules of {@code SecurityConfig},
 * so the restriction travels with the operation.
 *
 * <p>Read endpoints are available to any authenticated user, because Staff need to see
 * which policy version is currently published in order to trust the answers they are given.
 */
@RestController
@RequestMapping("/kb")
@Tag(name = "Knowledge Base", description = "Document ingestion, versioning and catalogue")
public class KbController {

    private static final Set<String> FILE_TYPES = Set.of("PDF", "DOCX", "TXT");
    private static final Set<String> CATEGORIES = Set.of(
            "policy-manual", "sop", "fare-rule", "regulatory-circular");
    private static final Pattern TAG_KEY = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final int MAX_TAGS = 12;

    private final IngestionService ingestionService;
    private final KbCatalogRepository catalog;
    private final HybridRetriever retriever;
    private final CurrentUser currentUser;
    private final PolicyImpactService policyImpactService;
    private final KbQualityService qualityService;
    private final IngestionAttemptService ingestionAttempts;
    private final RetrievalQueryExpander queryExpander;

    public KbController(IngestionService ingestionService,
                        KbCatalogRepository catalog,
                        HybridRetriever retriever,
                        CurrentUser currentUser,
                        PolicyImpactService policyImpactService,
                        KbQualityService qualityService,
                        IngestionAttemptService ingestionAttempts,
                        RetrievalQueryExpander queryExpander) {
        this.ingestionService = ingestionService;
        this.catalog = catalog;
        this.retriever = retriever;
        this.currentUser = currentUser;
        this.policyImpactService = policyImpactService;
        this.qualityService = qualityService;
        this.ingestionAttempts = ingestionAttempts;
        this.queryExpander = queryExpander;
    }

    /** SRS 5: {@code POST /kb/ingest}. Returns 201 with the SRS 4.1.5 body. */
    @PostMapping(path = {"/ingest", "/documents"}, consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Ingest a KB document (Admin only, FR-031)")
    public ResponseEntity<KbDtos.IngestionResult> ingest(@RequestParam("file") MultipartFile file) {
        if (file == null) {
            throw new ApiExceptions.BadRequest("No file was uploaded.");
        }
        CurrentUser.Authenticated admin = currentUser.require();

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ApiExceptions.BadRequest("The uploaded file could not be read.");
        }

        KbDtos.IngestionResult result = ingestionService.ingest(
                content,
                file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename(),
                admin.id(),
                admin.role().name());

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/documents")
    @Operation(summary = "List KB documents with their active version")
    public List<KbDtos.DocumentSummary> documents() {
        return catalog.listDocuments();
    }

    @GetMapping("/documents/{documentCode}/versions")
    @Operation(summary = "Version history for one document")
    public List<KbDtos.VersionSummary> versions(@PathVariable String documentCode) {
        if (!catalog.documentCodeExists(documentCode)) {
            throw new ApiExceptions.NotFound("No KB document with code " + documentCode);
        }
        return catalog.listVersions(documentCode);
    }

    @GetMapping("/statistics")
    @Operation(summary = "Knowledge Base totals for the Admin console")
    public KbDtos.KbStatistics statistics() {
        return catalog.statistics();
    }

    @GetMapping("/impact")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Report KB version and declared SRS impact")
    public PolicyImpactService.PolicyImpactSummary impact() {
        return policyImpactService.current();
    }

    @GetMapping("/quality")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Inspect corpus and role-filtered retrieval quality")
    public KbDtos.KbQualityReport quality() {
        return qualityService.inspect();
    }

    @GetMapping("/ingestion-attempts")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List durable KB ingestion attempts, including pre-validation failures")
    public List<IngestionAttemptService.AttemptView> ingestionAttempts(
            @RequestParam(defaultValue = "25") int limit) {
        return ingestionAttempts.recent(limit);
    }

    /**
     * Runs the real retrieval pipeline against the caller's own role filter. This is a
     * diagnostic surface: it shows exactly what the assistant would be allowed to see for a
     * given question, which is the quickest way to confirm that audience filtering works.
     */
    @PostMapping("/search")
    @Operation(summary = "Search the KB directly, using the caller's audience filter")
    public List<KbDtos.SearchHit> search(@RequestBody KbDtos.SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new ApiExceptions.BadRequest("A query is required.");
        }

        RetrievalDtos.Filter filter = RetrievalDtos.Filter.forRole(currentUser.role());
        if (request.categories() != null && !request.categories().isEmpty()) {
            Set<String> categories = request.categories().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!CATEGORIES.containsAll(categories)) {
                throw new ApiExceptions.BadRequest(
                        "Unknown document category. Allowed: " + CATEGORIES);
            }
            filter = filter.withCategories(categories);
        }
        if (request.fileTypes() != null && !request.fileTypes().isEmpty()) {
            Set<String> fileTypes = request.fileTypes().stream()
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!FILE_TYPES.containsAll(fileTypes)) {
                throw new ApiExceptions.BadRequest(
                        "Unknown file type. Allowed: " + FILE_TYPES);
            }
            filter = filter.withFileTypes(fileTypes);
        }
        if (request.documentCodes() != null && !request.documentCodes().isEmpty()) {
            filter = filter.withDocumentCodes(Set.copyOf(request.documentCodes()));
        }
        if (request.effectiveFrom() != null && request.effectiveTo() != null
                && request.effectiveFrom().isAfter(request.effectiveTo())) {
            throw new ApiExceptions.BadRequest(
                    "effectiveFrom must be on or before effectiveTo.");
        }
        if (request.effectiveFrom() != null || request.effectiveTo() != null) {
            filter = filter.withEffectiveRange(
                    request.effectiveFrom(), request.effectiveTo());
        }
        Map<String, String> tags = request.tags() == null ? Map.of() : request.tags();
        if (tags.size() > MAX_TAGS) {
            throw new ApiExceptions.BadRequest(
                    "At most " + MAX_TAGS + " metadata tags may be supplied.");
        }
        for (var tag : tags.entrySet()) {
            if (tag.getKey() == null || !TAG_KEY.matcher(tag.getKey()).matches()) {
                throw new ApiExceptions.BadRequest(
                        "Tag keys must start with a letter and contain only letters, "
                                + "digits, dot, underscore or hyphen.");
            }
            if (tag.getValue() == null || tag.getValue().isBlank()
                    || tag.getValue().length() > 160) {
                throw new ApiExceptions.BadRequest(
                        "Tag values must contain 1 to 160 characters.");
            }
        }
        if (!tags.isEmpty()) {
            filter = filter.withTags(Map.copyOf(tags));
        }

        RetrievalDtos.Result result = retriever.retrieve(
                request.query(), filter, RetrievalDtos.Lane.FAST, 1);
        AnswerRequirements requirements =
                AnswerRequirements.from(request.query(), null);
        result = retriever.augmentFocused(
                result, queryExpander.coverageQueries(requirements));

        return result.evidence().stream()
                .map(ranked -> {
                    RetrievalDtos.Chunk chunk = ranked.chunk();
                    return new KbDtos.SearchHit(
                            chunk.documentCode(),
                            chunk.documentTitle(),
                            chunk.section(),
                            chunk.page(),
                            chunk.category(),
                            chunk.audience(),
                            chunk.vectorScore(),
                            chunk.lexicalScore(),
                            HybridRetriever.relevance(chunk),
                            ranked.rank(),
                            excerpt(chunk.content()));
                })
                .toList();
    }

    private static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String collapsed = content.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 400 ? collapsed : collapsed.substring(0, 400) + "...";
    }
}
