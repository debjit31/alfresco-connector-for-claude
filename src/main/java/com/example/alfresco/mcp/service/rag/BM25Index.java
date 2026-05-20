package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.service.alfresco.RagExtension.DocumentChunk;
import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * In-process Okapi BM25 keyword index for the BM25/Lucene leg of hybrid
 * retrieval (Stage 4b in the pipeline architecture).
 *
 * <p>Maintains an inverted index of term → posting lists, and computes BM25
 * scores at query time. Documents are automatically indexed when chunks are
 * upserted and removed when a node is deleted, keeping this index in lockstep
 * with the dense vector store.</p>
 *
 * <p>Thread-safe via {@link ConcurrentHashMap} for the top-level structures;
 * individual posting lists are copy-on-write during upsert to avoid read-side
 * locking during search.</p>
 *
 * <h3>BM25 parameters</h3>
 * <ul>
 *   <li><b>k1 = 1.2</b> — term frequency saturation; standard Lucene default.</li>
 *   <li><b>b = 0.75</b> — length normalization; 0=no normalization, 1=full.</li>
 * </ul>
 */
@Component
public class BM25Index {

    private static final Logger log = LoggerFactory.getLogger(BM25Index.class);

    // ── BM25 tuning constants ────────────────────────────────────────
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+");

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "if", "then", "else", "of",
            "to", "in", "on", "at", "by", "for", "with", "as", "is", "are",
            "was", "were", "be", "been", "being", "it", "its", "this", "that",
            "these", "those", "i", "you", "he", "she", "we", "they", "them",
            "his", "her", "their", "our", "your", "from", "into", "out", "up",
            "down", "no", "not", "so", "than", "too", "very", "can", "will",
            "just", "do", "does", "did", "has", "have", "had", "about", "over"
    );

    // ── Data structures ──────────────────────────────────────────────

    /** Per-document record: the chunk + its term frequencies + total term count. */
    private record DocEntry(DocumentChunk chunk, Map<String, Integer> termFreqs, int totalTerms) {}

    /** Inverted index: term → set of chunkIds containing it. */
    private final ConcurrentHashMap<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();

    /** Forward index: chunkId → doc entry. */
    private final ConcurrentHashMap<String, DocEntry> documents = new ConcurrentHashMap<>();

    /** nodeId → set of chunkIds (mirrors VectorStore's secondary index). */
    private final ConcurrentHashMap<String, Set<String>> nodeToChunkIds = new ConcurrentHashMap<>();

    /** Running average document length for BM25 normalization. */
    private volatile double avgDocLength = 0.0;

    // ── Indexing ─────────────────────────────────────────────────────

    /**
     * Index a batch of chunks. Called in lockstep with vector upsertBatch.
     */
    public void indexBatch(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        for (DocumentChunk chunk : chunks) {
            indexChunk(chunk);
        }
        recalculateAvgDocLength();
        log.debug("BM25 indexed batch of {} chunks (total docs: {})", chunks.size(), documents.size());
    }

    private void indexChunk(DocumentChunk chunk) {
        List<String> tokens = tokenize(chunk.text());
        Map<String, Integer> termFreqs = new HashMap<>();
        for (String token : tokens) {
            termFreqs.merge(token, 1, Integer::sum);
        }

        DocEntry entry = new DocEntry(chunk, termFreqs, tokens.size());
        documents.put(chunk.chunkId(), entry);

        // Update inverted index
        for (String term : termFreqs.keySet()) {
            invertedIndex.computeIfAbsent(term, k -> ConcurrentHashMap.newKeySet())
                    .add(chunk.chunkId());
        }

        // Track nodeId → chunkIds
        nodeToChunkIds.computeIfAbsent(chunk.nodeId(), k -> ConcurrentHashMap.newKeySet())
                .add(chunk.chunkId());
    }

    /**
     * Remove all BM25 data for a given node. Returns the count of chunks removed.
     */
    public int deleteByNodeId(String nodeId) {
        Set<String> chunkIds = nodeToChunkIds.remove(nodeId);
        if (chunkIds == null || chunkIds.isEmpty()) return 0;

        int removed = 0;
        for (String chunkId : chunkIds) {
            DocEntry entry = documents.remove(chunkId);
            if (entry != null) {
                removed++;
                // Clean inverted index
                for (String term : entry.termFreqs().keySet()) {
                    Set<String> postings = invertedIndex.get(term);
                    if (postings != null) {
                        postings.remove(chunkId);
                        if (postings.isEmpty()) {
                            invertedIndex.remove(term);
                        }
                    }
                }
            }
        }
        recalculateAvgDocLength();
        log.debug("BM25 deleted {} chunks for node {}", removed, nodeId);
        return removed;
    }

    /**
     * Clear the entire BM25 index.
     */
    public void clear() {
        invertedIndex.clear();
        documents.clear();
        nodeToChunkIds.clear();
        avgDocLength = 0.0;
        log.info("BM25 index cleared");
    }

    // ── Search ───────────────────────────────────────────────────────

    /**
     * BM25 keyword search. Returns up to {@code topK} results ordered by
     * descending BM25 score.
     *
     * @param query  natural language query (tokenized internally)
     * @param topK   maximum number of results
     * @param nodeIds optional filter; null/empty means all nodes
     */
    public List<SearchHit> search(String query, int topK, Set<String> nodeIds) {
        if (query == null || query.isBlank() || documents.isEmpty() || topK <= 0) {
            return List.of();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return List.of();

        boolean filtered = nodeIds != null && !nodeIds.isEmpty();
        int N = documents.size();
        double avgDl = this.avgDocLength;
        if (avgDl <= 0) avgDl = 1.0;

        // Score every candidate document that contains at least one query term
        Map<String, Double> scores = new HashMap<>();

        for (String term : queryTerms) {
            Set<String> postings = invertedIndex.get(term);
            if (postings == null || postings.isEmpty()) continue;

            int df = postings.size();
            // IDF: log((N - df + 0.5) / (df + 0.5) + 1)
            double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1.0);

            for (String chunkId : postings) {
                if (filtered) {
                    DocEntry entry = documents.get(chunkId);
                    if (entry == null || !nodeIds.contains(entry.chunk().nodeId())) continue;
                }

                DocEntry entry = documents.get(chunkId);
                if (entry == null) continue;

                int tf = entry.termFreqs().getOrDefault(term, 0);
                int dl = entry.totalTerms();

                // BM25 score for this term-document pair
                double tfNorm = (tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * (dl / avgDl)));
                double score = idf * tfNorm;

                scores.merge(chunkId, score, Double::sum);
            }
        }

        // Sort by score descending and take topK
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    DocEntry entry = documents.get(e.getKey());
                    return new SearchHit(
                            entry.chunk().chunkId(),
                            entry.chunk().nodeId(),
                            entry.chunk().text(),
                            e.getValue(),
                            entry.chunk().metadata());
                })
                .collect(Collectors.toList());
    }

    // ── Stats ────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDocuments", documents.size());
        stats.put("vocabularySize", invertedIndex.size());
        stats.put("avgDocLength", Math.round(avgDocLength * 100.0) / 100.0);
        stats.put("nodesIndexed", nodeToChunkIds.size());
        return stats;
    }

    public boolean isIndexed(String nodeId) {
        Set<String> ids = nodeToChunkIds.get(nodeId);
        return ids != null && !ids.isEmpty();
    }

    // ── Tokenization ─────────────────────────────────────────────────

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String tok = matcher.group();
            if (tok.length() >= 2 && !STOPWORDS.contains(tok)) {
                tokens.add(tok);
            }
        }
        return tokens;
    }

    private void recalculateAvgDocLength() {
        if (documents.isEmpty()) {
            avgDocLength = 0.0;
            return;
        }
        long totalLength = 0;
        for (DocEntry entry : documents.values()) {
            totalLength += entry.totalTerms();
        }
        avgDocLength = (double) totalLength / documents.size();
    }
}

