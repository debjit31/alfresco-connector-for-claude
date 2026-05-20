package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.service.alfresco.RagExtension.DocumentChunk;
import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vector index abstraction: stores chunk embeddings and answers
 * nearest-neighbour (cosine similarity) queries.
 */
public interface VectorStore {

    /** Insert or replace the embedding for a single chunk. */
    void upsert(DocumentChunk chunk, double[] embedding);

    /**
     * Insert or replace a batch. {@code chunks} and {@code embeddings} are
     * positionally aligned and must be the same length.
     */
    void upsertBatch(List<DocumentChunk> chunks, List<double[]> embeddings);

    /** Top-K chunks by cosine similarity to {@code query}. */
    List<SearchHit> search(double[] query, int topK);

    /**
     * Top-K restricted to chunks whose nodeId is in {@code nodeIds}.
     * A null/empty set means "no filter".
     */
    List<SearchHit> search(double[] query, int topK, Set<String> nodeIds);

    /** Remove every chunk belonging to {@code nodeId}; returns count removed. */
    int deleteByNodeId(String nodeId);

    /** Whether any chunk for {@code nodeId} is currently indexed. */
    boolean isIndexed(String nodeId);

    /** Index statistics (counts, capacity, per-document breakdown). */
    Map<String, Object> getStats();

    /** All distinct indexed node IDs. */
    Set<String> getIndexedNodeIds();

    /** Drop the entire index. */
    void clear();
}