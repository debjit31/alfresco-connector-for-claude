package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.example.alfresco.mcp.service.alfresco.RagExtension.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits document text into embeddable chunks using one of two strategies
 * (configured via {@code rag.chunking.strategy}):
 *
 * <ul>
 *   <li><b>sliding_window</b> — fixed-size windows with overlap, truncated at
 *       a sentence boundary within the last 20% of the window when possible.</li>
 *   <li><b>paragraph</b> — split on blank lines, greedily merge short
 *       paragraphs up to the chunk size, carrying overlap between flushes.</li>
 * </ul>
 *
 * Every chunk carries {@code chunkStrategy}, {@code charStart}, {@code charEnd}
 * and {@code totalChunks} metadata, in addition to any caller-supplied keys.
 */
@Component
public class DocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunker.class);

    private final RagProperties.Chunking cfg;

    public DocumentChunker(RagProperties props) {
        this.cfg = props.getChunking();
    }

    public List<DocumentChunk> chunk(String nodeId, String content, Map<String, Object> baseMeta) {
        if (content == null || content.isBlank()) {
            log.debug("No content to chunk for node {}", nodeId);
            return List.of();
        }
        String text = content.strip();
        Map<String, Object> base = baseMeta != null ? baseMeta : Map.of();

        List<RawChunk> raw = "paragraph".equalsIgnoreCase(cfg.getStrategy())
                ? chunkByParagraph(text)
                : chunkBySlidingWindow(text);

        Object nameVal = base.get("name");
        String documentName = (nameVal != null && !String.valueOf(nameVal).isBlank())
                ? String.valueOf(nameVal) : nodeId;

        List<DocumentChunk> chunks = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            RawChunk rc = raw.get(i);
            Map<String, Object> meta = new LinkedHashMap<>(base);
            meta.put("chunkStrategy", cfg.getStrategy());
            meta.put("charStart", rc.start);
            meta.put("charEnd", rc.end);
            meta.put("totalChunks", raw.size());

            String section = detectSection(rc.text);
            meta.put("section", section);
            String contextualText = "[Document: " + documentName
                    + " | Section: " + section + "]\n" + rc.text;

            chunks.add(new DocumentChunk(
                    nodeId + "::chunk-" + i, nodeId, contextualText, i, meta));
        }
        log.info("Chunked node {} into {} chunks (strategy={})",
                nodeId, chunks.size(), cfg.getStrategy());
        return chunks;
    }

    // ── Sliding window ──────────────────────────────────────────────

    private List<RawChunk> chunkBySlidingWindow(String text) {
        int size = Math.max(cfg.getChunkSize(), 1);
        int overlap = Math.max(0, Math.min(cfg.getChunkOverlap(), size - 1));
        int len = text.length();

        List<RawChunk> out = new ArrayList<>();
        int pos = 0;
        while (pos < len) {
            int end = Math.min(pos + size, len);

            // Sentence-boundary-aware truncation (not for the final chunk).
            if (end < len) {
                int windowStart = pos + (int) (size * 0.8);
                int boundary = lastSentenceEnd(text, windowStart, end);
                if (boundary > pos) {
                    end = boundary;
                }
            }

            String slice = text.substring(pos, end).strip();
            boolean lastChunk = end >= len;
            if (slice.length() >= cfg.getMinChunkSize() || (lastChunk && !slice.isEmpty() && out.isEmpty())) {
                out.add(new RawChunk(slice, pos, end));
            }

            if (lastChunk) break;
            int next = end - overlap;
            pos = (next <= pos) ? end : next;   // guarantee forward progress
        }
        return out;
    }

    /** Index just past the last sentence terminator in [from, to), else -1. */
    private static int lastSentenceEnd(String text, int from, int to) {
        from = Math.max(from, 0);
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        return -1;
    }

    // ── Paragraph ───────────────────────────────────────────────────

    private List<RawChunk> chunkByParagraph(String text) {
        int size = Math.max(cfg.getChunkSize(), 1);
        int overlap = Math.max(0, Math.min(cfg.getChunkOverlap(), size - 1));

        List<RawChunk> out = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");

        StringBuilder buffer = new StringBuilder();
        int bufferStart = 0;
        int cursor = 0;   // running offset into the original text

        for (String para : paragraphs) {
            String p = para.strip();
            // Advance cursor to where this paragraph actually begins.
            int idx = text.indexOf(p, cursor);
            if (idx >= 0) cursor = idx;

            if (p.isEmpty()) continue;

            if (buffer.length() == 0) {
                bufferStart = cursor;
            }

            if (buffer.length() + p.length() + 1 > size && buffer.length() > 0) {
                flush(out, buffer, bufferStart, cursor);
                // Carry overlap (tail of previous buffer) into the new one.
                String carry = tail(buffer.toString(), overlap);
                buffer.setLength(0);
                buffer.append(carry);
                bufferStart = Math.max(cursor - carry.length(), 0);
            }

            if (buffer.length() > 0) buffer.append("\n\n");
            buffer.append(p);
            cursor += para.length();
        }
        flush(out, buffer, bufferStart, text.length());
        return out;
    }

    private void flush(List<RawChunk> out, StringBuilder buffer, int start, int end) {
        String chunk = buffer.toString().strip();
        if (chunk.length() >= cfg.getMinChunkSize()) {
            out.add(new RawChunk(chunk, start, end));
        }
    }

    private static String tail(String s, int n) {
        if (n <= 0 || s.length() <= n) return n <= 0 ? "" : s;
        return s.substring(s.length() - n);
    }

    // ── Section detection ───────────────────────────────────────────

    /**
     * Best-effort section label from the head of a chunk. Scans the first
     * few lines for one that looks like a heading: ALL-CAPS, a short line
     * followed by a blank line, or a short line that doesn't end with a
     * period. Falls back to "General".
     */
    private static String detectSection(String text) {
        String[] lines = text.split("\n", 12);
        for (int i = 0; i < lines.length && i < 8; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.length() >= 80) continue;

            boolean hasLetter = line.chars().anyMatch(Character::isLetter);
            if (!hasLetter) continue;

            boolean allCaps = line.equals(line.toUpperCase());
            boolean followedByBlank = i + 1 < lines.length && lines[i + 1].strip().isEmpty();
            boolean noTerminalPeriod = !line.endsWith(".");

            if (allCaps || followedByBlank || noTerminalPeriod) {
                return line;
            }
        }
        return "General";
    }

    // ── Internal value type ─────────────────────────────────────────

    private record RawChunk(String text, int start, int end) {}
}
