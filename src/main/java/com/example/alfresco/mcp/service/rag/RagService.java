package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.example.alfresco.mcp.service.alfresco.RagExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Concrete {@link RagExtension} implementation: orchestrates chunking →
 * embedding → vector storage and semantic retrieval.
 *
 * Disabled entirely when {@code rag.enabled=false}; when absent, all RAG MCP
 * tools and the RAG REST controller are also withheld (they
 * {@code @ConditionalOnBean(RagService.class)}).
 */
@Service
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true", matchIfMissing = true)
public class RagService implements RagExtension {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final VectorStore vectorStore;
    private final RagProperties props;

    public RagService(DocumentChunker chunker,
                      EmbeddingProvider embeddingProvider,
                      VectorStore vectorStore,
                      RagProperties props) {
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.props = props;
        log.info("RagService active: embedding={} ({}d), vectorStore={}",
                embeddingProvider.getProviderName(),
                embeddingProvider.getDimensions(),
                props.getVectorStore().getType());
    }

    // ── RagExtension contract ───────────────────────────────────────

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
                    vectorStore.upsertBatch(chunks, embeddings);
                    log.info("Indexed {} chunks", chunks.size());
                    return chunks.size();
                });
    }

    @Override
    public CompletableFuture<List<SearchHit>> semanticSearch(String query, int topK) {
        return semanticSearch(query, topK, null);
    }

    // ── Extended API ────────────────────────────────────────────────

    /** Filtered semantic search restricted to the given node IDs. */
    public CompletableFuture<List<SearchHit>> semanticSearch(String query, int topK,
                                                             Set<String> nodeIds) {
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return embeddingProvider.embed(query)
                .thenApply(qvec -> vectorStore.search(qvec, topK, nodeIds));
    }

    /**
     * Convenience full-document indexing: drop any prior vectors for the
     * node, chunk the fresh content, embed and store. Returns chunk count.
     */
    public CompletableFuture<Integer> indexDocument(String nodeId, String content,
                                                    Map<String, Object> metadata) {
        int removed = vectorStore.deleteByNodeId(nodeId);
        if (removed > 0) {
            log.debug("Re-indexing node {} (removed {} stale chunks)", nodeId, removed);
        }
        List<DocumentChunk> chunks = chunkDocument(nodeId, content, metadata);
        return indexChunks(chunks);
    }

    public boolean isDocumentIndexed(String nodeId) {
        return vectorStore.isIndexed(nodeId);
    }

    public int removeDocument(String nodeId) {
        return vectorStore.deleteByNodeId(nodeId);
    }

    public Map<String, Object> getIndexStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>(vectorStore.getStats());
        stats.put("embeddingProvider", embeddingProvider.getProviderName());
        stats.put("embeddingDimensions", embeddingProvider.getDimensions());
        stats.put("chunkingStrategy", props.getChunking().getStrategy());
        return stats;
    }

    public void clearIndex() {
        vectorStore.clear();
    }
}
