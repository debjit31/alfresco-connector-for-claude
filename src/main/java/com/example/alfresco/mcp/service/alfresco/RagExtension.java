package com.example.alfresco.mcp.service.alfresco;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * RAG (Retrieval-Augmented Generation) extension point.
 *
 * Implement this interface to enable:
 *   - Document chunking for vector storage
 *   - Embedding generation via an LLM provider
 *   - Semantic search over Alfresco content
 *
 * When implemented, register as a Spring bean and wire it into a new
 * "semantic_search" MCP tool.
 *
 * Example flow:
 *   1. Document uploaded → chunk → embed → store in vector DB
 *   2. Claude calls semantic_search → embed query → vector similarity → return chunks
 *   3. Claude uses chunks as context for answering
 */
public interface RagExtension {

    /**
     * Chunk a document's text content into segments suitable for embedding.
     *
     * @param nodeId  the Alfresco node ID
     * @param content the full text content
     * @param metadata document metadata for context
     * @return list of text chunks with their metadata
     */
    List<DocumentChunk> chunkDocument(String nodeId, String content, Map<String, Object> metadata);

    /**
     * Generate embeddings and store chunks in a vector database.
     *
     * @param chunks the document chunks to index
     * @return number of chunks stored
     */
    CompletableFuture<Integer> indexChunks(List<DocumentChunk> chunks);

    /**
     * Perform semantic search over indexed documents.
     *
     * @param query    natural language query
     * @param topK     number of results to return
     * @return ranked list of matching chunks with scores
     */
    CompletableFuture<List<SearchHit>> semanticSearch(String query, int topK);

    // ── DTOs ────────────────────────────────────────────────────────

    record DocumentChunk(
            String chunkId,
            String nodeId,
            String text,
            int chunkIndex,
            Map<String, Object> metadata
    ) {}

    record SearchHit(
            String chunkId,
            String nodeId,
            String text,
            double score,
            Map<String, Object> metadata
    ) {}
}
