package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.example.alfresco.mcp.service.alfresco.RagExtension.DocumentChunk;
import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Vector store backed by Qdrant (via REST API), activated with
 * {@code rag.vector-store.type=qdrant}.
 *
 * <p>Qdrant provides persistent, HNSW-indexed vector storage that survives
 * restarts and scales to millions of documents. This implementation uses
 * Qdrant's REST API (not gRPC) to avoid extra dependencies — the project
 * already has Spring WebClient.</p>
 *
 * <h3>Collection management</h3>
 * <p>On startup, the store checks whether the configured collection exists
 * and creates it with the correct dimensionality if not. This means a fresh
 * Qdrant instance is fully self-configuring.</p>
 *
 * <h3>Payload</h3>
 * <p>Each point stores: {@code nodeId}, {@code chunkId}, {@code text},
 * {@code chunkIndex}, and the full metadata map. This allows server-side
 * filtering by {@code nodeId} and carries all data needed to reconstruct
 * a {@link SearchHit} without a secondary lookup.</p>
 */
@Component
@ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "qdrant")
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String collection;
    private final int dimensions;
    private final Duration timeout;

    /**
     * Local nodeId → chunkIds tracking for fast per-node operations.
     * Qdrant doesn't natively support "delete all points matching a payload
     * field" in a single atomic call, so we track this locally.
     */
    private final ConcurrentHashMap<String, Set<String>> nodeToChunkIds = new ConcurrentHashMap<>();

    public QdrantVectorStore(RagProperties props, EmbeddingProvider embeddingProvider, ObjectMapper objectMapper) {
        RagProperties.VectorStore cfg = props.getVectorStore();
        this.objectMapper = objectMapper;
        this.collection = cfg.getQdrantCollection();
        this.dimensions = embeddingProvider.getDimensions();
        this.timeout = Duration.ofMillis(cfg.getQdrantTimeoutMs());

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(cfg.getQdrantUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        String apiKey = cfg.getQdrantApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("api-key", apiKey);
        }

        this.webClient = builder.build();
        log.info("QdrantVectorStore initialised: url={}, collection={}, dimensions={}",
                cfg.getQdrantUrl(), collection, dimensions);
    }

    @PostConstruct
    public void ensureCollection() {
        try {
            // Check if collection exists
            Boolean exists = webClient.get()
                    .uri("/collections/{name}", collection)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .map(r -> r.path("result").path("status").asText("").length() > 0)
                    .onErrorReturn(false)
                    .block();

            if (Boolean.TRUE.equals(exists)) {
                log.info("Qdrant collection '{}' already exists", collection);
                rebuildNodeIndex();
                return;
            }

            // Create collection
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode vectors = body.putObject("vectors");
            vectors.put("size", dimensions);
            vectors.put("distance", "Cosine");

            webClient.put()
                    .uri("/collections/{name}", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            // Create payload index on nodeId for efficient filtering
            ObjectNode indexBody = objectMapper.createObjectNode();
            indexBody.put("field_name", "nodeId");
            indexBody.put("field_schema", "keyword");

            webClient.put()
                    .uri("/collections/{name}/index", collection)
                    .bodyValue(indexBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            log.info("Created Qdrant collection '{}' ({}d, cosine, nodeId indexed)", collection, dimensions);

        } catch (Exception e) {
            log.error("Failed to initialise Qdrant collection '{}': {}", collection, e.getMessage());
            throw new IllegalStateException("Qdrant not reachable or collection creation failed", e);
        }
    }

    // ── VectorStore implementation ───────────────────────────────────

    @Override
    public void upsert(DocumentChunk chunk, double[] embedding) {
        upsertBatch(List.of(chunk), List.of(embedding));
    }

    @Override
    public void upsertBatch(List<DocumentChunk> chunks, List<double[]> embeddings) {
        if (chunks == null || embeddings == null || chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks and embeddings must be non-null and equal length");
        }
        if (chunks.isEmpty()) return;

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode points = body.putArray("points");

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            double[] vec = embeddings.get(i);

            ObjectNode point = points.addObject();
            // Use a deterministic UUID from chunkId for idempotent upserts
            point.put("id", toUuid(chunk.chunkId()));

            ArrayNode vectorArr = point.putArray("vector");
            for (double v : vec) {
                vectorArr.add(v);
            }

            ObjectNode payload = point.putObject("payload");
            payload.put("chunkId", chunk.chunkId());
            payload.put("nodeId", chunk.nodeId());
            payload.put("text", chunk.text());
            payload.put("chunkIndex", chunk.chunkIndex());

            if (chunk.metadata() != null) {
                ObjectNode meta = payload.putObject("metadata");
                chunk.metadata().forEach((k, v) -> {
                    if (v != null) meta.put(k, String.valueOf(v));
                });
            }

            // Track locally
            nodeToChunkIds
                    .computeIfAbsent(chunk.nodeId(), k -> ConcurrentHashMap.newKeySet())
                    .add(chunk.chunkId());
        }

        try {
            webClient.put()
                    .uri("/collections/{name}/points", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            log.debug("Upserted {} points to Qdrant collection '{}'", chunks.size(), collection);
        } catch (Exception e) {
            log.error("Qdrant upsert failed: {}", e.getMessage());
            throw new RuntimeException("Qdrant upsert failed", e);
        }
    }

    @Override
    public List<SearchHit> search(double[] query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<SearchHit> search(double[] query, int topK, Set<String> nodeIds) {
        if (query == null || topK <= 0) return List.of();

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode vectorArr = body.putArray("vector");
        for (double v : query) {
            vectorArr.add(v);
        }
        body.put("limit", topK);
        body.put("with_payload", true);

        // Add nodeId filter if specified
        if (nodeIds != null && !nodeIds.isEmpty()) {
            ObjectNode filter = body.putObject("filter");
            ObjectNode must = filter.putArray("must").addObject();
            ObjectNode match = must.putObject("key").removeAll();
            // Build: { "must": [{ "key": "nodeId", "match": { "any": [...] } }] }
            must.put("key", "nodeId");
            ObjectNode matchObj = must.putObject("match");
            ArrayNode anyArr = matchObj.putArray("any");
            nodeIds.forEach(anyArr::add);
        }

        try {
            JsonNode response = webClient.post()
                    .uri("/collections/{name}/points/search", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            return parseSearchResults(response);
        } catch (Exception e) {
            log.error("Qdrant search failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public int deleteByNodeId(String nodeId) {
        Set<String> chunkIds = nodeToChunkIds.remove(nodeId);
        if (chunkIds == null || chunkIds.isEmpty()) {
            // Try deleting by filter anyway (in case local index is stale)
            deleteByNodeIdFilter(nodeId);
            return 0;
        }

        // Delete points by their deterministic UUIDs
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode pointIds = body.putArray("points");
        for (String chunkId : chunkIds) {
            pointIds.add(toUuid(chunkId));
        }

        try {
            webClient.post()
                    .uri("/collections/{name}/points/delete", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            log.debug("Deleted {} points for node {} from Qdrant", chunkIds.size(), nodeId);
            return chunkIds.size();
        } catch (Exception e) {
            log.error("Qdrant delete failed for node {}: {}", nodeId, e.getMessage());
            return 0;
        }
    }

    /** Fallback: delete by payload filter when local index is empty. */
    private void deleteByNodeIdFilter(String nodeId) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode filter = body.putObject("filter");
            ObjectNode must = filter.putArray("must").addObject();
            must.put("key", "nodeId");
            ObjectNode match = must.putObject("match");
            match.put("value", nodeId);

            webClient.post()
                    .uri("/collections/{name}/points/delete", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();
        } catch (Exception e) {
            log.warn("Qdrant filter-delete failed for node {}: {}", nodeId, e.getMessage());
        }
    }

    @Override
    public boolean isIndexed(String nodeId) {
        // Check local index first (fast path)
        Set<String> ids = nodeToChunkIds.get(nodeId);
        return ids != null && !ids.isEmpty();
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("storeType", "qdrant");
        stats.put("collection", collection);

        try {
            JsonNode response = webClient.get()
                    .uri("/collections/{name}", collection)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            JsonNode result = response != null ? response.path("result") : null;
            if (result != null) {
                stats.put("totalChunks", result.path("points_count").asInt(0));
                stats.put("status", result.path("status").asText("unknown"));
                stats.put("vectorsCount", result.path("vectors_count").asInt(0));
                stats.put("indexedVectorsCount", result.path("indexed_vectors_count").asInt(0));
            }
        } catch (Exception e) {
            stats.put("error", "Could not fetch Qdrant stats: " + e.getMessage());
        }

        stats.put("totalDocuments", nodeToChunkIds.size());
        Map<String, Integer> perDoc = new LinkedHashMap<>();
        nodeToChunkIds.forEach((node, ids) -> perDoc.put(node, ids.size()));
        stats.put("chunksPerDocument", perDoc);
        return stats;
    }

    @Override
    public Set<String> getIndexedNodeIds() {
        return Set.copyOf(nodeToChunkIds.keySet());
    }

    @Override
    public void clear() {
        try {
            // Delete and recreate collection
            webClient.delete()
                    .uri("/collections/{name}", collection)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();

            nodeToChunkIds.clear();
            ensureCollection();
            log.info("Qdrant collection '{}' cleared and recreated", collection);
        } catch (Exception e) {
            log.error("Failed to clear Qdrant collection: {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private List<SearchHit> parseSearchResults(JsonNode response) {
        List<SearchHit> hits = new ArrayList<>();
        if (response == null) return hits;

        JsonNode results = response.path("result");
        if (!results.isArray()) return hits;

        for (JsonNode point : results) {
            JsonNode payload = point.path("payload");
            double score = point.path("score").asDouble(0.0);

            String chunkId = payload.path("chunkId").asText("");
            String nodeId = payload.path("nodeId").asText("");
            String text = payload.path("text").asText("");

            Map<String, Object> metadata = new LinkedHashMap<>();
            JsonNode meta = payload.path("metadata");
            if (meta.isObject()) {
                meta.fields().forEachRemaining(f -> metadata.put(f.getKey(), f.getValue().asText()));
            }

            hits.add(new SearchHit(chunkId, nodeId, text, score, metadata));
        }
        return hits;
    }

    /**
     * Rebuild the local nodeId → chunkIds index by scrolling all points.
     * Called once on startup so deleteByNodeId works correctly.
     */
    private void rebuildNodeIndex() {
        try {
            String nextOffset = null;
            int total = 0;

            do {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("limit", 1000);
                body.put("with_payload", true);
                body.put("with_vector", false);
                if (nextOffset != null) {
                    body.put("offset", nextOffset);
                }

                JsonNode response = webClient.post()
                        .uri("/collections/{name}/points/scroll", collection)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(timeout)
                        .block();

                if (response == null) break;
                JsonNode result = response.path("result");
                JsonNode points = result.path("points");
                nextOffset = result.path("next_page_offset").isNull()
                        ? null : result.path("next_page_offset").asText(null);

                if (points.isArray()) {
                    for (JsonNode point : points) {
                        JsonNode payload = point.path("payload");
                        String nodeId = payload.path("nodeId").asText("");
                        String chunkId = payload.path("chunkId").asText("");
                        if (!nodeId.isEmpty() && !chunkId.isEmpty()) {
                            nodeToChunkIds
                                    .computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet())
                                    .add(chunkId);
                            total++;
                        }
                    }
                }
            } while (nextOffset != null);

            log.info("Rebuilt Qdrant node index: {} chunks across {} nodes",
                    total, nodeToChunkIds.size());
        } catch (Exception e) {
            log.warn("Failed to rebuild Qdrant node index (will rebuild progressively): {}", e.getMessage());
        }
    }

    /**
     * Deterministic UUID from a string (UUID v5 / SHA-1 based).
     * Ensures the same chunkId always maps to the same Qdrant point ID,
     * making upserts idempotent.
     */
    static String toUuid(String input) {
        return UUID.nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}

