package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval engine: fuses dense vector (semantic) results with BM25
 * keyword results using Reciprocal Rank Fusion (Stage 6 of the pipeline).
 *
 * <h3>Algorithm: Reciprocal Rank Fusion (RRF)</h3>
 * <p>For each result list, each document at rank {@code r} receives a score of
 * {@code 1 / (k + r)} where {@code k} is a constant (default 60). The RRF
 * score for a document is the sum of its per-list scores. This elegantly
 * handles the problem of different score scales between vector cosine
 * similarity and BM25 without explicit normalization.</p>
 *
 * <p>When hybrid search is disabled ({@code rag.search.hybrid-enabled=false}),
 * falls back to pure dense vector search.</p>
 *
 * <p>Reference: Cormack, Clarke & Buettcher (2009)
 * "Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods"</p>
 */
@Component
public class HybridSearchEngine {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchEngine.class);

    private final VectorStore vectorStore;
    private final BM25Index bm25Index;
    private final EmbeddingProvider embeddingProvider;
    private final RagProperties.Search searchCfg;

    public HybridSearchEngine(VectorStore vectorStore,
                              BM25Index bm25Index,
                              EmbeddingProvider embeddingProvider,
                              RagProperties props) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.embeddingProvider = embeddingProvider;
        this.searchCfg = props.getSearch();
        log.info("HybridSearchEngine active: hybridEnabled={}, rrfK={}, semanticTopK={}, lexicalTopK={}",
                searchCfg.isHybridEnabled(), searchCfg.getRrfK(), searchCfg.getSemanticTopK(), searchCfg.getLexicalTopK());
    }

    /**
     * Execute hybrid search: embed the query, run dense vector search + BM25,
     * fuse results with RRF, and return the merged ranking.
     *
     * @param query   natural language query
     * @param topK    how many fused results to return
     * @param nodeIds optional filter (null = all)
     * @return fused search results ordered by RRF score descending
     */
    public CompletableFuture<List<SearchHit>> search(String query, int topK, Set<String> nodeIds) {
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        if (!searchCfg.isHybridEnabled()) {
            // Pure vector search fallback
            return embeddingProvider.embed(query)
                    .thenApply(qvec -> vectorStore.search(qvec, topK, nodeIds));
        }

        // Run both searches in parallel
        CompletableFuture<List<SearchHit>> semanticFuture = embeddingProvider.embed(query)
                .thenApply(qvec -> vectorStore.search(qvec, searchCfg.getSemanticTopK(), nodeIds));

        CompletableFuture<List<SearchHit>> lexicalFuture = CompletableFuture.supplyAsync(() ->
                bm25Index.search(query, searchCfg.getLexicalTopK(), nodeIds));

        return semanticFuture.thenCombine(lexicalFuture, (semanticHits, lexicalHits) -> {
            log.debug("Hybrid search: {} semantic hits, {} lexical hits for query '{}'",
                    semanticHits.size(), lexicalHits.size(), truncate(query, 60));

            List<SearchHit> fused = reciprocalRankFusion(semanticHits, lexicalHits, topK);
            log.debug("RRF produced {} fused results", fused.size());
            return fused;
        });
    }

    /**
     * Reciprocal Rank Fusion of two ranked lists.
     *
     * <p>Each document at rank r (1-based) in a list gets score 1/(k+r).
     * Documents appearing in both lists have their scores summed.
     * The final list is sorted by combined RRF score descending.</p>
     */
    List<SearchHit> reciprocalRankFusion(List<SearchHit> semanticHits,
                                          List<SearchHit> lexicalHits,
                                          int topK) {
        int k = searchCfg.getRrfK();
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, SearchHit> hitMap = new LinkedHashMap<>();

        // Score semantic results
        for (int rank = 0; rank < semanticHits.size(); rank++) {
            SearchHit hit = semanticHits.get(rank);
            double rrfScore = 1.0 / (k + rank + 1); // 1-based rank
            rrfScores.merge(hit.chunkId(), rrfScore, Double::sum);
            hitMap.putIfAbsent(hit.chunkId(), hit);
        }

        // Score lexical results
        for (int rank = 0; rank < lexicalHits.size(); rank++) {
            SearchHit hit = lexicalHits.get(rank);
            double rrfScore = 1.0 / (k + rank + 1);
            rrfScores.merge(hit.chunkId(), rrfScore, Double::sum);
            hitMap.putIfAbsent(hit.chunkId(), hit);
        }

        // Sort by fused RRF score and take topK
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    SearchHit original = hitMap.get(e.getKey());
                    return new SearchHit(
                            original.chunkId(),
                            original.nodeId(),
                            original.text(),
                            e.getValue(), // RRF score replaces individual score
                            original.metadata());
                })
                .collect(Collectors.toList());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

