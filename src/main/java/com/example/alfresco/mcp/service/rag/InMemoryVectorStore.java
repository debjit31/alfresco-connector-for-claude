package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.example.alfresco.mcp.service.alfresco.RagExtension.DocumentChunk;
import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Process-local vector index backed by {@link ConcurrentHashMap}.
 *
 * <p>State lives only in this JVM — like the SSE session map, this makes the
 * server non-horizontally-scalable while RAG is enabled. Suitable for a
 * single-instance deployment or development.</p>
 *
 * <p>A secondary nodeId → chunkIds map makes per-document deletion O(chunks
 * of that doc) instead of a full scan.</p>
 */
@Component
@ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private record VectorEntry(DocumentChunk chunk, double[] embedding) {}

    private final ConcurrentHashMap<String, VectorEntry> vectors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> nodeToChunkIds = new ConcurrentHashMap<>();

    private final int maxVectors;

    public InMemoryVectorStore(RagProperties props) {
        this.maxVectors = props.getVectorStore().getMaxVectors();
        log.info("InMemoryVectorStore initialised (maxVectors={})", maxVectors);
    }

    @Override
    public void upsert(DocumentChunk chunk, double[] embedding) {
        if (chunk == null || embedding == null) return;
        boolean isNew = !vectors.containsKey(chunk.chunkId());
        if (isNew && vectors.size() >= maxVectors) {
            throw new IllegalStateException(
                    "Vector store capacity reached (" + maxVectors + "); refusing new vector");
        }
        vectors.put(chunk.chunkId(), new VectorEntry(chunk, embedding));
        nodeToChunkIds
                .computeIfAbsent(chunk.nodeId(), k -> ConcurrentHashMap.newKeySet())
                .add(chunk.chunkId());
    }

    @Override
    public void upsertBatch(List<DocumentChunk> chunks, List<double[]> embeddings) {
        if (chunks == null || embeddings == null || chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "chunks and embeddings must be non-null and equal length");
        }
        for (int i = 0; i < chunks.size(); i++) {
            upsert(chunks.get(i), embeddings.get(i));
        }
        log.debug("Upserted batch of {} vectors (store size now {})", chunks.size(), vectors.size());
    }

    @Override
    public List<SearchHit> search(double[] query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<SearchHit> search(double[] query, int topK, Set<String> nodeIds) {
        if (query == null || vectors.isEmpty() || topK <= 0) {
            return List.of();
        }
        boolean filtered = nodeIds != null && !nodeIds.isEmpty();

        return vectors.values().parallelStream()
                .filter(e -> !filtered || nodeIds.contains(e.chunk().nodeId()))
                .map(e -> new SearchHit(
                        e.chunk().chunkId(),
                        e.chunk().nodeId(),
                        e.chunk().text(),
                        cosineSimilarity(query, e.embedding()),
                        e.chunk().metadata()))
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    @Override
    public int deleteByNodeId(String nodeId) {
        Set<String> chunkIds = nodeToChunkIds.remove(nodeId);
        if (chunkIds == null || chunkIds.isEmpty()) return 0;
        int removed = 0;
        for (String chunkId : chunkIds) {
            if (vectors.remove(chunkId) != null) removed++;
        }
        log.debug("Deleted {} chunks for node {}", removed, nodeId);
        return removed;
    }

    @Override
    public boolean isIndexed(String nodeId) {
        Set<String> ids = nodeToChunkIds.get(nodeId);
        return ids != null && !ids.isEmpty();
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Integer> perDoc = new LinkedHashMap<>();
        nodeToChunkIds.forEach((node, ids) -> perDoc.put(node, ids.size()));

        Map<String, Object> stats = new LinkedHashMap<>();
        int total = vectors.size();
        stats.put("storeType", "memory");
        stats.put("totalChunks", total);
        stats.put("totalDocuments", nodeToChunkIds.size());
        stats.put("maxVectors", maxVectors);
        stats.put("usagePercent",
                maxVectors > 0 ? Math.round((total * 10000.0) / maxVectors) / 100.0 : 0.0);
        stats.put("chunksPerDocument", perDoc);
        return stats;
    }

    @Override
    public Set<String> getIndexedNodeIds() {
        return Set.copyOf(nodeToChunkIds.keySet());
    }

    @Override
    public void clear() {
        vectors.clear();
        nodeToChunkIds.clear();
        log.info("Vector store cleared");
    }

    // ── Vector math ─────────────────────────────────────────────────

    /**
     * Cosine similarity with defensive edge handling: mismatched dimensions
     * fall back to the shared prefix; a zero-magnitude vector yields 0.
     */
    static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0;
        int n = Math.min(a.length, b.length);
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 1e-12 || nb <= 1e-12) return 0.0;
        double sim = dot / (Math.sqrt(na) * Math.sqrt(nb));
        if (Double.isNaN(sim) || Double.isInfinite(sim)) return 0.0;
        return sim;
    }
}
