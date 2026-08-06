package com.unitedair.ai.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ingests the bundled KB corpus the first time the application starts.
 *
 * <p>Without this, a freshly unzipped copy would come up with an empty Knowledge Base and
 * answer every question with the empty-context response - technically correct, and a
 * terrible first impression. Seeding runs only when no documents exist at all, so it never
 * fights with content an administrator has uploaded.
 *
 * <p>The corpus ships as both {@code .txt} and {@code .docx} renderings of the same eight
 * documents. Both carry the same Document Code, so ingesting both would embed every
 * document twice and leave the second as the active version. Where both formats of a
 * document are present the {@code .txt} is used: extraction is exact rather than
 * reconstructed, and it costs no Tika parsing.
 */
@Component
public class KbSeeder {

    private static final Logger log = LoggerFactory.getLogger(KbSeeder.class);

    /** Recognises the KB number in filenames like "UnitedAir_AI_KB_03_Baggage...(1).txt". */
    private static final Pattern KB_NUMBER = Pattern.compile("KB[_ -]?(\\d{2})");

    private static final List<String> FORMAT_PREFERENCE = List.of(".txt", ".docx", ".pdf", ".doc", ".md");

    private final IngestionService ingestionService;
    private final KbCatalogRepository catalog;
    private final UnitedAirProperties.Ingestion config;

    public KbSeeder(IngestionService ingestionService,
                    KbCatalogRepository catalog,
                    UnitedAirProperties properties) {
        this.ingestionService = ingestionService;
        this.catalog = catalog;
        this.config = properties.getIngestion();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void seedIfEmpty() {
        if (!config.isSeedOnStartup()) {
            log.info("KB seeding disabled (unitedair.ingestion.seed-on-startup=false).");
            return;
        }
        Path directory = resolveSeedDirectory();
        if (directory == null) {
            log.warn("KB seed directory '{}' not found; starting with an empty Knowledge Base. "
                            + "Upload documents through the Admin console, or set KB_SEED_DIRECTORY.",
                    config.getSeedDirectory());
            return;
        }

        List<Path> files = selectPreferredFormats(directory).stream()
                .filter(path -> {
                    String documentCode = documentCodeForFilename(
                            path.getFileName().toString());
                    if (documentCode == null) {
                        return catalog.isEmpty();
                    }
                    return !catalog.documentCodeExists(documentCode);
                })
                .toList();
        if (files.isEmpty()) {
            log.info("All bundled Knowledge Base documents are already present.");
            return;
        }

        log.info("Seeding Knowledge Base from {} ({} documents)", directory.toAbsolutePath(), files.size());
        int succeeded = 0;
        int failed = 0;

        for (Path file : files) {
            try {
                byte[] content = Files.readAllBytes(file);
                KbDtos.IngestionResult result = ingestionService.ingest(
                        content, file.getFileName().toString(), null, "SYSTEM");
                succeeded++;
                log.info("  seeded {} - {} chunks ({} ms)",
                        result.documentCode(), result.chunksCreated(), result.ingestionTimeMs());
            } catch (Exception e) {
                failed++;
                // One malformed document must not stop the other seven from loading.
                log.error("  failed to seed {}: {}", file.getFileName(), e.toString());
            }
        }

        log.info("Knowledge Base seeding complete: {} succeeded, {} failed, {} chunks active",
                succeeded, failed, catalog.statistics().chunkCount());
    }

    /**
     * The seed directory is relative to the backend working directory by default, but a
     * packaged JAR may be launched from anywhere, so a few sensible locations are tried.
     */
    private Path resolveSeedDirectory() {
        List<Path> candidates = List.of(
                Paths.get(config.getSeedDirectory()),
                Paths.get("kb"),
                Paths.get("../kb"),
                Paths.get("../../kb"));

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Groups files by the document they represent and keeps one format each.
     * Files with no recognisable KB number are keyed by their own name, so an ad-hoc
     * document dropped into the folder is still ingested.
     */
    List<Path> selectPreferredFormats(Path directory) {
        Map<String, List<Path>> byDocument = new LinkedHashMap<>();

        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(KbSeeder::isIngestible)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(path -> {
                        String key = documentKey(path.getFileName().toString());
                        byDocument.computeIfAbsent(key, k -> new ArrayList<>()).add(path);
                    });
        } catch (IOException e) {
            log.error("Could not list {}: {}", directory, e.toString());
            return List.of();
        }

        List<Path> selected = new ArrayList<>();
        byDocument.forEach((key, paths) -> {
            paths.stream()
                    .min(Comparator.comparingInt(KbSeeder::formatRank))
                    .ifPresent(selected::add);
            if (paths.size() > 1) {
                log.debug("  {} available in {} formats; using {}",
                        key, paths.size(), selected.get(selected.size() - 1).getFileName());
            }
        });

        selected.sort(Comparator.comparing(p -> documentKey(p.getFileName().toString())));
        return selected;
    }

    private static String documentKey(String filename) {
        Matcher m = KB_NUMBER.matcher(filename.toUpperCase(Locale.ROOT));
        return m.find() ? "KB" + m.group(1) : filename.toLowerCase(Locale.ROOT);
    }

    /**
     * Filenames use two digits (KB_03), while catalog document codes use three
     * (KB-AIR-003). Keeping the conversion here prevents every startup from
     * re-ingesting all bundled documents as duplicate versions.
     */
    static String documentCodeForFilename(String filename) {
        if (filename == null) {
            return null;
        }
        Matcher matcher = KB_NUMBER.matcher(filename.toUpperCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        return "KB-AIR-%03d".formatted(Integer.parseInt(matcher.group(1)));
    }

    private static int formatRank(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (int i = 0; i < FORMAT_PREFERENCE.size(); i++) {
            if (name.endsWith(FORMAT_PREFERENCE.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static boolean isIngestible(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return FORMAT_PREFERENCE.stream().anyMatch(name::endsWith) && !name.startsWith("~$");
    }
}
