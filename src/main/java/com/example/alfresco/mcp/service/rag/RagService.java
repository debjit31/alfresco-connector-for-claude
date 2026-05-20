package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.example.alfresco.mcp.service.alfresco.RagExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Concrete {@link RagExtension} implementation: orchestrates the full 7-stage
 * RAG pipeline matching the architecture diagram:
 *
 * <pre>
 * INGESTION:  Extract → Chunk → Embed → Index (Vector + BM25)
 * QUERY:      Expand → Hybrid Retrieve (Dense + BM25 + RRF) → Rerank → Return
 * </pre>
 *
 * <p>Each stage is a pluggable component wired via Spring. The pipeline degrades
 * gracefully: if hybrid search is disabled, only vector search runs; if reranking
 * is disabled, results pass through; if query expansion is off, the original
 * query is used as-is.</p>
 *
 * <p>Disabled entirely when {@code rag.enabled=false}; when absent, all RAG MCP
 * tools and the RAG REST controller are also withheld (they
 * {@code @ConditionalOnBean(RagService.class)}).</p>
 */
@Service
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true", matchIfMissing = true)
public class RagService implements RagExtension {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final VectorStore vectorStore;
    private final BM25Index bm25Index;
    private final HybridSearchEngine hybridSearchEngine;
    private final Reranker reranker;
    private final QueryExpander queryExpander;
    private final RagProperties props;

    public RagService(DocumentChunker chunker,
                      EmbeddingProvider embeddingProvider,
                      VectorStore vectorStore,
                      BM25Index bm25Index,
                      HybridSearchEngine hybridSearchEngine,
                      Reranker reranker,
                      QueryExpander queryExpander,
                      RagProperties props) {
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.hybridSearchEngine = hybridSearchEngine;
        this.reranker = reranker;
        this.queryExpander = queryExpander;
        this.props = props;
        log.info("RagService active: embedding={} ({}d), vectorStore={}, hybrid={}, reranker={}, queryExpansion={}",
                embeddingProvider.getProviderName(),
                embeddingProvider.getDimensions(),
                props.getVectorStore().getType(),
                props.getSearch().isHybridEnabled(),
                reranker.getProviderName(),
                props.getQueryExpansion().isEnabled());
    }

    // ══════════════════════════════════════════════════════════════════
    //  INGESTION PIPELINE: Chunk → Embed → Index (Vector + BM25)
    // ══════════════════════════════════════════════════════════════════

    @Override
    public List<DocumentChunk> chunkDocument(String nodeId, String content,
                                             Map<String, Object> metadata) {
        return chunker.chunk(nodeId, content, metadata);
    }

    @Override
    public CompletableFuture<Integer> indexChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        List<String> texts = chunks.stream()
                .map(DocumentChunk::text)
                .collect(Collectors.toList());

        return embeddingProvider.embedBatch(texts)
                .thenApply(embeddings -> {
                    // Stage 4a: Dense vector index
                    vectorStore.upsertBatch(chunks, embeddings);
                    // Stage 4b: BM25 keyword index (dual indexing)
                    bm25Index.indexBatch(chunks);
                    log.info("Indexed {} chunks (vector + BM25 dual index)", chunks.size());
                    return chunks.size();
                });
    }

    /**
     * Convenience full-document indexing: drop any prior vectors for the
     * node, chunk the fresh content, embed and store. Returns chunk count.
     */
    public CompletableFuture<Integer> indexDocument(String nodeId, String content,
                                                    Map<String, Object> metadata) {
        // Remove stale data from both indexes
        int removedVector = vectorStore.deleteByNodeId(nodeId);
        int removedBm25 = bm25Index.deleteByNodeId(nodeId);
        if (removedVector > 0 || removedBm25 > 0) {
            log.debug("Re-indexing node {} (removed {} vector, {} BM25 chunks)",
                    nodeId, removedVector, removedBm25);
        }
        List<DocumentChunk> chunks = chunkDocument(nodeId, content, metadata);
        return indexChunks(chunks);
    }

    // ══════════════════════════════════════════════════════════════════
    //  QUERY PIPELINE: Expand → Hybrid Retrieve → Rerank → Return
    // ══════════════════════════════════════════════════════════════════

    @Override
    public CompletableFuture<List<SearchHit>> semanticSearch(String query, int topK) {
        return semanticSearch(query, topK, null);
    }

    /**
     * Full pipeline search with optional node ID filter.
     *
     * <ol>
     *   <li><b>Stage 5 — Query expansion:</b> generate multiple query variants
     *       (synonym expansion, sub-query decomposition, HyDE-lite).</li>
     *   <li><b>Stage 6 — Hybrid retrieval:</b> for each variant, run dense
     *       vector search + BM25, fuse with RRF.</li>
     *   <li><b>Stage 7 — Cross-encoder rerank:</b> re-score the fused candidates
     *       with a more accurate model.</li>
     * </ol>
     */
    public CompletableFuture<List<SearchHit>> semanticSearch(String query, int topK,
                                                             Set<String> nodeIds) {
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        // ─── Stage 5: Query expansion ──────────────────────────────
        List<String> queryVariants = queryExpander.expand(query);
        log.debug("Query expansion produced {} variants for: '{}'",
                queryVariants.size(), truncate(query, 60));

        // ─── Stage 6: Hybrid retrieval (per variant, then merge) ───
        int retrievalTopK = Math.max(topK, props.getReranking().getTopKBeforeRerank());

        if (queryVariants.size() <= 1) {
            // Single query — direct hybrid search + rerank
            return hybridSearchEngine.search(query, retrievalTopK, nodeIds)
                    .thenCompose(hits -> reranker.rerank(query, hits, topK));
        }

        // Multi-query: run each variant and merge results
        List<CompletableFuture<List<SearchHit>>> variantFutures = queryVariants.stream()
                .map(variant -> hybridSearchEngine.search(variant, retrievalTopK, nodeIds))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(variantFutures.toArray(CompletableFuture[]::new))
                .thenApply(v -> {
                    // Merge results from all variants using RRF
                    List<List<SearchHit>> allResults = variantFutures.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList());
                    return mergeMultiQueryResults(allResults, retrievalTopK);
                })
                .thenCompose(merged -> {
                    // ─── Stage 7: Cross-encoder rerank ─────────────
                    log.debug("Reranking {} merged candidates", merged.size());
                    return reranker.rerank(query, merged, topK);
                });
    }

    /**
     * Merge results from multiple query variants using RRF.
     * Documents that appear in results from multiple variants get boosted.
     */
    private List<SearchHit> mergeMultiQueryResults(List<List<SearchHit>> allResults, int topK) {
        int k = props.getSearch().getRrfK();
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, SearchHit> hitMap = new LinkedHashMap<>();

        for (List<SearchHit> variantResults : allResults) {
            for (int rank = 0; rank < variantResults.size(); rank++) {
                SearchHit hit = variantResults.get(rank);
                double rrfScore = 1.0 / (k + rank + 1);
                rrfScores.merge(hit.chunkId(), rrfScore, Double::sum);
                hitMap.putIfAbsent(hit.chunkId(), hit);
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    SearchHit original = hitMap.get(e.getKey());
                    return new SearchHit(
                            original.chunkId(), original.nodeId(), original.text(),
                            e.getValue(), original.metadata());
                })
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════
    //  MANAGEMENT
    // ══════════════════════════════════════════════════════════════════

    public boolean isDocumentIndexed(String nodeId) {
        return vectorStore.isIndexed(nodeId) || bm25Index.isIndexed(nodeId);
    }

    public int removeDocument(String nodeId) {
        int removedVector = vectorStore.deleteByNodeId(nodeId);
        int removedBm25 = bm25Index.deleteByNodeId(nodeId);
        return Math.max(removedVector, removedBm25);
    }

    public Map<String, Object> getIndexStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>(vectorStore.getStats());
        stats.put("embeddingProvider", embeddingProvider.getProviderName());
        stats.put("embeddingDimensions", embeddingProvider.getDimensions());
        stats.put("chunkingStrategy", props.getChunking().getStrategy());
        // Pipeline capabilities
        stats.put("hybridSearchEnabled", props.getSearch().isHybridEnabled());
        stats.put("rerankerProvider", reranker.getProviderName());
        stats.put("queryExpansionEnabled", props.getQueryExpansion().isEnabled());
        // BM25 stats
        stats.put("bm25Index", bm25Index.getStats());
        return stats;
    }

    public void clearIndex() {
        vectorStore.clear();
        bm25Index.clear();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
