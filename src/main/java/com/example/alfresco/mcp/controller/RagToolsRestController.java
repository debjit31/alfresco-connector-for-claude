package com.example.alfresco.mcp.controller;

import com.example.alfresco.mcp.client.AlfrescoRestClient;
import com.example.alfresco.mcp.model.dto.IndexDocumentRequest;
import com.example.alfresco.mcp.model.dto.IndexStatusRequest;
import com.example.alfresco.mcp.model.dto.SemanticSearchRequest;
import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;
import com.example.alfresco.mcp.service.rag.RagService;
import com.example.alfresco.mcp.service.rag.TextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * REST convenience endpoints for the RAG tools, mirroring the MCP tool logic
 * so Swagger UI can offer typed, pre-filled forms. Only registered when RAG
 * is enabled (i.e. a {@link RagService} bean exists).
 */
@RestController
@RequestMapping("/api/tools")
@ConditionalOnBean(RagService.class)
public class RagToolsRestController {

    private static final Logger log = LoggerFactory.getLogger(RagToolsRestController.class);

    private final RagService ragService;
    private final AlfrescoRestClient alfrescoClient;
    private final TextExtractor textExtractor;

    public RagToolsRestController(RagService ragService,
                                  AlfrescoRestClient alfrescoClient,
                                  TextExtractor textExtractor) {
        this.ragService = ragService;
        this.alfrescoClient = alfrescoClient;
        this.textExtractor = textExtractor;
    }

    // ═══════════════════════════════════════════════════════════════
    //  INDEX DOCUMENT
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Index a document for semantic search",
            description = """
                    Download an Alfresco document's content, chunk it, generate embeddings,
                    and store the vectors so it becomes discoverable via semantic_search.

                    If the document is already indexed and `forceReindex` is false, the call
                    returns immediately with `status: already_indexed`.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document indexed (or already indexed)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "status": "indexed",
                                      "nodeId": "d1234567-89ab-cdef-0123-456789abcdef",
                                      "documentName": "Q4-Strategy.txt",
                                      "contentLength": 18452,
                                      "chunksCreated": 21
                                    }
                                    """))),
            @ApiResponse(responseCode = "502", description = "Alfresco API error")
    })
    @Tag(name = "Tools — RAG")
    @PostMapping(value = "/index_document",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<Map<String, Object>>> indexDocument(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Indexing parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = IndexDocumentRequest.class))
            )
            @RequestBody IndexDocumentRequest request) {

        String nodeId = request.getNodeId();
        boolean forceReindex = Boolean.TRUE.equals(request.getForceReindex());
        log.info("REST index_document: nodeId={}, forceReindex={}", nodeId, forceReindex);

        if (nodeId == null || nodeId.isBlank()) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.badRequest().body(Map.of("error", "nodeId is required")));
        }

        if (!forceReindex && ragService.isDocumentIndexed(nodeId)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "already_indexed");
            body.put("nodeId", nodeId);
            return CompletableFuture.completedFuture(ResponseEntity.ok(body));
        }

        // Metadata first → MIME type → binary goes through Tika, text reads
        // directly as a UTF-8 string. (Same branching as IndexDocumentTool.)
        return alfrescoClient.getNode(nodeId).thenCompose(node -> {
                    Map<String, Object> metadata = extractMetadata(node);
                    String documentName = String.valueOf(metadata.getOrDefault("name", nodeId));
                    String mimeType = (String) metadata.get("mimeType");

                    CompletableFuture<String> textFuture;
                    if (textExtractor.isBinaryFormat(mimeType)) {
                        textFuture = alfrescoClient.getContentBytes(nodeId)
                                .thenApply(bytes -> textExtractor.extractText(bytes, mimeType));
                    } else {
                        textFuture = alfrescoClient.getContent(nodeId);
                    }

                    return textFuture.thenCompose(content -> {
                        int contentLength = content != null ? content.length() : 0;
                        return ragService.indexDocument(nodeId, content, metadata)
                                .thenApply(chunks -> {
                                    Map<String, Object> body = new LinkedHashMap<>();
                                    body.put("status", chunks > 0 ? "indexed" : "no_content");
                                    body.put("nodeId", nodeId);
                                    body.put("documentName", documentName);
                                    body.put("mimeType", mimeType);
                                    body.put("contentLength", contentLength);
                                    body.put("chunksCreated", chunks);
                                    return ResponseEntity.ok(body);
                                });
                    });
                });
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEMANTIC SEARCH
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Hybrid semantic search over indexed documents",
            description = """
                    Runs the full 7-stage RAG pipeline: query expansion → dense vector
                    search + BM25 keyword search → Reciprocal Rank Fusion → cross-encoder
                    reranking. Documents must be indexed first via `/api/tools/index_document`.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ranked semantic hits",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "query": "documents discussing quarterly revenue targets",
                                      "totalHits": 2,
                                      "minScore": 0.1,
                                      "results": [
                                        {
                                          "rank": 1,
                                          "score": 0.842,
                                          "nodeId": "d1234567-89ab-cdef-0123-456789abcdef",
                                          "chunkId": "d1234567-89ab-cdef-0123-456789abcdef::chunk-3",
                                          "text": "Revenue targets for Q4 were set at ...",
                                          "metadata": { "documentName": "Q4-Strategy.txt", "path": "/Company Home/Shared" }
                                        }
                                      ]
                                    }
                                    """)))
    })
    @Tag(name = "Tools — RAG")
    @PostMapping(value = "/semantic_search",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<Map<String, Object>>> semanticSearch(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Semantic search parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = SemanticSearchRequest.class))
            )
            @RequestBody SemanticSearchRequest request) {

        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.badRequest().body(Map.of("error", "query is required")));
        }

        int topK = request.getTopK() != null ? Math.max(1, Math.min(request.getTopK(), 20)) : 5;
        double minScore = request.getMinScore() != null ? request.getMinScore() : 0.1;
        Set<String> nodeIds = parseNodeIds(request.getNodeIds());

        log.info("REST semantic_search: query='{}', topK={}, minScore={}", query, topK, minScore);

        return ragService.semanticSearch(query, topK, nodeIds)
                .thenApply(hits -> ResponseEntity.ok(buildSearchResult(query, hits, minScore)));
    }

    // ═══════════════════════════════════════════════════════════════
    //  INDEX STATUS
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Inspect or manage the vector index",
            description = """
                    `status` — index statistics. `check` — whether a node is indexed.
                    `remove` — delete one document's vectors. `clear` — wipe the index.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Index status / action result",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "storeType": "memory",
                                      "totalChunks": 42,
                                      "totalDocuments": 3,
                                      "maxVectors": 100000,
                                      "usagePercent": 0.04,
                                      "embeddingProvider": "local",
                                      "embeddingDimensions": 768
                                    }
                                    """)))
    })
    @Tag(name = "Tools — RAG")
    @PostMapping(value = "/index_status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> indexStatus(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Index management parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = IndexStatusRequest.class))
            )
            @RequestBody IndexStatusRequest request) {

        String action = request.getAction() != null ? request.getAction().trim() : "status";
        String nodeId = request.getNodeId();
        log.info("REST index_status: action={}, nodeId={}", action, nodeId);

        Map<String, Object> body = new LinkedHashMap<>();
        switch (action) {
            case "status" -> body.putAll(ragService.getIndexStats());
            case "check" -> {
                if (isBlank(nodeId)) return ResponseEntity.badRequest()
                        .body(Map.of("error", "nodeId is required for action 'check'"));
                body.put("nodeId", nodeId);
                body.put("indexed", ragService.isDocumentIndexed(nodeId));
            }
            case "remove" -> {
                if (isBlank(nodeId)) return ResponseEntity.badRequest()
                        .body(Map.of("error", "nodeId is required for action 'remove'"));
                int removed = ragService.removeDocument(nodeId);
                body.put("nodeId", nodeId);
                body.put("chunksRemoved", removed);
                body.put("status", removed > 0 ? "removed" : "not_indexed");
            }
            case "clear" -> {
                ragService.clearIndex();
                body.put("status", "cleared");
            }
            default -> {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Unknown action '" + action +
                                "'. Expected one of: status, check, remove, clear"));
            }
        }
        return ResponseEntity.ok(body);
    }

    // ── Shared helpers (same logic as the MCP tools) ────────────────

    private Map<String, Object> extractMetadata(JsonNode node) {
        Map<String, Object> meta = new LinkedHashMap<>();
        JsonNode entry = node.has("entry") ? node.get("entry") : node;
        meta.put("name", entry.path("name").asText(null));
        meta.put("nodeType", entry.path("nodeType").asText(null));
        meta.put("mimeType", entry.path("content").path("mimeType").asText(null));
        meta.put("modifiedAt", entry.path("modifiedAt").asText(null));
        JsonNode path = entry.path("path");
        if (path.has("name")) {
            meta.put("path", path.get("name").asText());
        }
        meta.values().removeIf(v -> v == null || "null".equals(v));
        return meta;
    }

    private Map<String, Object> buildSearchResult(String query, List<SearchHit> hits, double minScore) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        int rank = 1;
        for (SearchHit hit : hits) {
            if (hit.score() < minScore) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("score", Math.round(hit.score() * 1000.0) / 1000.0);
            row.put("nodeId", hit.nodeId());
            row.put("chunkId", hit.chunkId());
            row.put("text", hit.text());
            Map<String, Object> meta = hit.metadata() != null ? hit.metadata() : Map.of();
            Map<String, Object> srcMeta = new LinkedHashMap<>();
            srcMeta.put("documentName", meta.get("name"));
            srcMeta.put("path", meta.get("path"));
            row.put("metadata", srcMeta);
            ranked.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("totalHits", ranked.size());
        result.put("minScore", minScore);
        result.put("results", ranked);
        return result;
    }

    private Set<String> parseNodeIds(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> ids = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return ids.isEmpty() ? null : ids;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
