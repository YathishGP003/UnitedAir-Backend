package com.unitedair.ai.knowledge;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;

import com.unitedair.ai.shared.ApiExceptions;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

/**
 * Extracts plain text from the three accepted document types (PDF, DOCX, TXT).
 *
 * <p>Tika is given an unbounded write limit because airline policy manuals routinely exceed
 * the default 100 000 character cap, and a silently truncated policy is worse than a failed
 * ingestion: the document would appear to ingest successfully while its later sections -
 * often the fee tables - were simply missing from the Knowledge Base.
 */
@Component
public class TextExtractor {

    public String extract(byte[] content, String filename) {
        String type = detectType(filename);

        if ("TXT".equals(type)) {
            // Tika would work here too, but reading directly avoids charset guessing on
            // files we already know are UTF-8 text.
            return new String(content, java.nio.charset.StandardCharsets.UTF_8);
        }

        try (InputStream in = new ByteArrayInputStream(content)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
            return handler.toString();
        } catch (Exception e) {
            throw new ApiExceptions.BadRequest(
                    "Could not read '" + filename + "'. It may be corrupt, encrypted, or not a "
                            + type + " file.");
        }
    }

    /** PDF | DOCX | TXT, or a BadRequest naming what was rejected. */
    public String detectType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            return "DOCX";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return "TXT";
        }
        throw new ApiExceptions.BadRequest(
                "Unsupported file type. The Knowledge Base accepts PDF, DOCX and TXT documents.");
    }
}
