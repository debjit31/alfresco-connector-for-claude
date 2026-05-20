package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-encoder reranker abstraction (Stage 7 of the pipeline).
 *
 * Takes a query and candidate SearchHits from hybrid retrieval, re-scores
 * them using a cross-encoder model, and returns the top results.
 */
public interface Reranker {

    /**
     * Re-rank the candidates for the given query.
     *
     * @param query      the original user query
     * @param candidates hits from hybrid retrieval (already fused)
     * @param topK       how many to return after reranking
     * @return reranked list, ordered by cross-encoder score descending
     */
    CompletableFuture<List<SearchHit>> rerank(String query, List<SearchHit> candidates, int topK);

    /** Short provider name for logs/stats. */
    String getProviderName();
}

