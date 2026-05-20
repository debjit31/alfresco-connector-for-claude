package com.example.alfresco.mcp.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Dependency-free embedding provider: hashing-based TF-IDF projected into a
 * fixed 768-dimensional space.
 *
 * <p>This is not a semantic model — it captures lexical overlap — but it lets
 * the whole RAG pipeline run with zero external services. The IDF table is
 * learned incrementally as documents are indexed (via {@link #embedBatch}),
 * so retrieval quality improves as the corpus grows.</p>
 *
 * <p>Collision mitigation: each term contributes to a primary bucket and, at
 * reduced weight, a neighbouring bucket (cheap bigram-style context spread).
 * The sign trick ({@code hash & 1}) lets opposing terms partially cancel
 * rather than always accumulate.</p>
 */
@Component
@ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "local", matchIfMissing = true)
public class LocalEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingProvider.class);

    private static final int DIMENSIONS = 768;
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private static final double NEIGHBOR_WEIGHT = 0.3;

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "if", "then", "else", "of",
            "to", "in", "on", "at", "by", "for", "with", "as", "is", "are",
            "was", "were", "be", "been", "being", "it", "its", "this", "that",
            "these", "those", "i", "you", "he", "she", "we", "they", "them",
            "his", "her", "their", "our", "your", "from", "into", "out", "up",
            "down", "no", "not", "so", "than", "too", "very", "can", "will",
            "just", "do", "does", "did", "has", "have", "had", "about", "over"
    );

    /** documentFrequency[bucket] is unused; df is tracked per-term-hash. */
    private final ConcurrentHashMap<Integer, AtomicInteger> documentFrequency = new ConcurrentHashMap<>();
    private final AtomicInteger totalDocs = new AtomicInteger(0);

    @Override
    public CompletableFuture<double[]> embed(String text) {
        // Query/ad-hoc path: do NOT mutate IDF statistics.
        return CompletableFuture.completedFuture(computeEmbedding(text, false));
    }

    @Override
    public CompletableFuture<List<double[]>> embedBatch(List<String> texts) {
        // Indexing path: update IDF tracking for each document.
        return CompletableFuture.supplyAsync(() -> {
            java.util.List<double[]> out = new java.util.ArrayList<>(texts.size());
            for (String t : texts) {
                out.add(computeEmbedding(t, true));
            }
            return out;
        });
    }

    /**
     * Compute the TF-IDF hashed embedding for {@code text}.
     *
     * @param updateIdf when true, increments document frequency / total docs
     *                  so subsequent embeddings reflect this document's terms.
     */
    double[] computeEmbedding(String text, boolean updateIdf) {
        double[] vector = new double[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }

        // ── Tokenize + term frequency ───────────────────────────────
        Map<String, Integer> termCounts = new HashMap<>();
        var matcher = TOKEN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String tok = matcher.group();
            if (tok.length() < 2 || STOPWORDS.contains(tok)) continue;
            termCounts.merge(tok, 1, Integer::sum);
        }
        if (termCounts.isEmpty()) {
            return vector;
        }

        if (updateIdf) {
            totalDocs.incrementAndGet();
            for (String term : termCounts.keySet()) {
                int h = termHash(term);
                documentFrequency.computeIfAbsent(h, k -> new AtomicInteger(0)).incrementAndGet();
            }
        }

        int docs = Math.max(totalDocs.get(), 1);

        // ── Feature hashing with TF-IDF weights ─────────────────────
        for (Map.Entry<String, Integer> e : termCounts.entrySet()) {
            String term = e.getKey();
            int count = e.getValue();

            int h = termHash(term);
            int df = documentFrequency.getOrDefault(h, ZERO).get();

            double tf = 1.0 + Math.log(count);
            double idf = Math.log((double) docs / (1.0 + df));
            double weight = tf * idf;
            if (weight == 0.0) continue;

            int bucket = Math.floorMod(h, DIMENSIONS);
            double sign = ((h & 1) == 0) ? 1.0 : -1.0;
            vector[bucket] += sign * weight;

            // Spread into the neighbouring bucket for cheap context.
            int neighbor = Math.floorMod(bucket + 1, DIMENSIONS);
            vector[neighbor] += sign * weight * NEIGHBOR_WEIGHT;
        }

        l2Normalize(vector);
        return vector;
    }

    private static final AtomicInteger ZERO = new AtomicInteger(0);

    /** MurmurHash3-style finalizer mixing of the term's hashCode. */
    private static int termHash(String term) {
        int h = term.hashCode();
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    /** In-place L2 normalization; zero vectors are left untouched. */
    private static void l2Normalize(double[] v) {
        double sumSq = 0.0;
        for (double x : v) sumSq += x * x;
        double norm = Math.sqrt(sumSq);
        if (norm <= 1e-12 || Double.isNaN(norm)) return;
        for (int i = 0; i < v.length; i++) v[i] /= norm;
    }

    @Override
    public int getDimensions() {
        return DIMENSIONS;
    }

    @Override
    public String getProviderName() {
        return "local";
    }
}