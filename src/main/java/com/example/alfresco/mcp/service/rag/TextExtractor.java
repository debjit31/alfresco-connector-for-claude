package com.example.alfresco.mcp.service.rag;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Plain-text extraction for binary document formats (PDF, DOCX, PPTX, XLSX,
 * legacy Office, RTF, …) using Apache Tika.
 *
 * <p>This sits in front of the RAG pipeline: binary content downloaded from
 * Alfresco as raw bytes is run through Tika so the chunker/embedder see real
 * prose instead of ZIP/OLE binary garbage (e.g. {@code PK} for a
 * DOCX).</p>
 *
 * <p>Fault-tolerant by contract: extraction failures are logged and yield an
 * empty string — this class never throws. Extraction is capped at 500K
 * characters via a Tika write limit; if the limit is hit the truncated text
 * is returned rather than discarded.</p>
 */
@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    /** Cap extraction so a huge document can't blow up memory / context. */
    private static final int WRITE_LIMIT = 500_000;

    /** {@link AutoDetectParser} is thread-safe and reusable. */
    private final Parser parser = new AutoDetectParser();

    public TextExtractor() {
        // AutoDetectParser lazily class-loads its parsers on first parse
        // (~2s one-time cost). That hit is logged in extractText().
        log.info("TextExtractor initialised (Tika AutoDetectParser; parsers load on first use)");
    }

    /**
     * Extract plain text from binary {@code content}. Format is auto-detected
     * by Tika; {@code mimeType} is only a hint and may be null.
     *
     * @return extracted text, or "" on any failure (never throws)
     */
    public String extractText(byte[] content, String mimeType) {
        if (content == null || content.length == 0) {
            log.debug("extractText: empty content (mimeType={})", mimeType);
            return "";
        }

        long started = System.currentTimeMillis();
        BodyContentHandler handler = new BodyContentHandler(WRITE_LIMIT);
        Metadata metadata = new Metadata();
        if (mimeType != null && !mimeType.isBlank()) {
            metadata.set(Metadata.CONTENT_TYPE, mimeType);
        }

        try (InputStream in = new ByteArrayInputStream(content)) {
            parser.parse(in, handler, metadata, new ParseContext());
        } catch (SAXException e) {
            // BodyContentHandler signals the write limit via a SAXException
            // subclass — that's expected truncation, not a real failure.
            if (isWriteLimitReached(e)) {
                String partial = handler.toString();
                log.warn("Tika hit {}-char write limit for mimeType={}; returning {} chars (truncated)",
                        WRITE_LIMIT, mimeType, partial.length());
                return partial;
            }
            log.warn("Tika SAX failure for mimeType={} ({} bytes): {}",
                    mimeType, content.length, e.toString());
            return "";
        } catch (TikaException | IOException e) {
            log.warn("Tika extraction failed for mimeType={} ({} bytes): {}",
                    mimeType, content.length, e.toString());
            return "";
        }

        String text = handler.toString();
        log.info("Tika extracted {} chars from mimeType={} ({} bytes) in {}ms",
                text.length(), mimeType, content.length, System.currentTimeMillis() - started);
        return text;
    }

    /**
     * Whether this MIME type is something we can usefully turn into text.
     * Text types pass through (no extraction needed); the listed binary
     * office/document formats are extractable. Everything else is false.
     */
    public boolean canExtract(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String mt = mimeType.toLowerCase().trim();
        if (mt.startsWith("text/")) {
            return true;
        }
        return switch (mt) {
            case "application/pdf",
                 "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // .docx
                 "application/vnd.openxmlformats-officedocument.presentationml.presentation", // .pptx
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",          // .xlsx
                 "application/msword",          // .doc
                 "application/vnd.ms-excel",    // .xls
                 "application/vnd.ms-powerpoint", // .ppt
                 "application/rtf" -> true;
            default -> false;
        };
    }

    /**
     * True when the MIME type is NOT a {@code text/*} type — i.e. it should be
     * downloaded as raw bytes and run through Tika rather than read directly
     * as a UTF-8 string. An unknown/blank type is treated as binary so Tika's
     * content-based auto-detection can still recover the text.
     */
    public boolean isBinaryFormat(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return true;
        }
        return !mimeType.toLowerCase().trim().startsWith("text/");
    }

    /** Detect Tika's write-limit signal without binding to a version-specific class. */
    private static boolean isWriteLimitReached(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getClass().getName().toLowerCase().contains("writelimit")) {
                return true;
            }
            if (c == c.getCause()) break;
        }
        return false;
    }
}
