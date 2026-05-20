package com.example.alfresco.mcp.service.tools;

import com.example.alfresco.mcp.client.AlfrescoRestClient;
import com.example.alfresco.mcp.model.ToolDefinition;
import com.example.alfresco.mcp.model.ToolDefinition.InputSchema;
import com.example.alfresco.mcp.model.ToolDefinition.PropertyDef;
import com.example.alfresco.mcp.model.ToolResult;
import com.example.alfresco.mcp.service.rag.RagService;
import com.example.alfresco.mcp.service.rag.TextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool: index_document
 *
 * Fetches an Alfresco node's metadata and text content, chunks + embeds it,
 * and stores the vectors so it becomes searchable via {@code semantic_search}.
 * Only registered when RAG is enabled.
 */
@Component
@ConditionalOnBean(RagService.class)
public class IndexDocumentTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(IndexDocumentTool.class);

    private final RagService ragService;
    private final AlfrescoRestClient alfrescoClient;
    private final TextExtractor textExtractor;

    public IndexDocumentTool(RagService ragService,
                             AlfrescoRestClient alfrescoClient,
                             TextExtractor textExtractor) {
        this.ragService = ragService;
        this.alfrescoClient = alfrescoClient;
        this.textExtractor = textExtractor;
    }

    @Override
    public String getName() {
        return "index_document";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("nodeId", new PropertyDef("string",
                "Alfresco node ID (UUID) of the document to index for semantic search"));
        props.put("forceReindex", new PropertyDef("boolean",
                "Re-index even if the document is already indexed (default: false)", false));

        InputSchema schema = new InputSchema(props, List.of("nodeId"));

        return new ToolDefinition(
                getName(),
                "Index an Alfresco document into the vector store so it can be found by " +
                "meaning via semantic_search. Downloads the document's content, splits it " +
                "into chunks, generates embeddings, and stores them. Skips work if the " +
                "document is already indexed unless forceReindex is true.",
                schema);
    }

    @Override
    public CompletableFuture<ToolResult> execute(JsonNode arguments) {
        String nodeId = getRequiredString(arguments, "nodeId");
        if (nodeId == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.error("Missing required parameter: 'nodeId'"));
        }
        boolean forceReindex = arguments.has("forceReindex")
                && arguments.get("forceReindex").asBoolean(false);

        if (!forceReindex && ragService.isDocumentIndexed(nodeId)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "already_indexed");
            result.put("nodeId", nodeId);
            result.put("hint", "Pass forceReindex=true to rebuild this document's vectors");
            return CompletableFuture.completedFuture(ToolResult.json(result));
        }

        log.info("Indexing document {} (forceReindex={})", nodeId, forceReindex);

        // Fetch metadata FIRST so we know the MIME type before downloading
        // content — binary formats (DOCX/PDF/…) must go through Tika, not be
        // read as a UTF-8 string (which yields ZIP/OLE garbage).
        return alfrescoClient.getNode(nodeId).thenCompose(node -> {
                    Map<String, Object> metadata = extractMetadata(node);
                    String documentName = String.valueOf(metadata.getOrDefault("name", nodeId));
                    String mimeType = (String) metadata.get("mimeType");

                    CompletableFuture<String> textFuture;
                    if (textExtractor.isBinaryFormat(mimeType)) {
                        log.debug("Node {} mimeType={} is binary → bytes + Tika extraction",
                                nodeId, mimeType);
                        textFuture = alfrescoClient.getContentBytes(nodeId)
                                .thenApply(bytes -> textExtractor.extractText(bytes, mimeType));
                    } else {
                        log.debug("Node {} mimeType={} is text → direct string download",
                                nodeId, mimeType);
                        textFuture = alfrescoClient.getContent(nodeId);
                    }

                    return textFuture.thenCompose(content -> {
                        int contentLength = content != null ? content.length() : 0;
                        return ragService.indexDocument(nodeId, content, metadata)
                                .thenApply(chunksCreated -> {
                                    Map<String, Object> result = new LinkedHashMap<>();
                                    result.put("status", chunksCreated > 0 ? "indexed" : "no_content");
                                    result.put("nodeId", nodeId);
                                    result.put("documentName", documentName);
                                    result.put("mimeType", mimeType);
                                    result.put("contentLength", contentLength);
                                    result.put("chunksCreated", chunksCreated);
                                    return ToolResult.json(result);
                                });
                    });
                })
                .exceptionally(ex -> ToolResult.error(
                        "Failed to index document " + nodeId + ": " + rootMessage(ex)));
    }

    /** Pull name/type/mime/modified/path out of an Alfresco /nodes entry. */
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

    private String getRequiredString(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String val = args.get(field).asText().trim();
        return val.isEmpty() ? null : val;
    }

    private String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }
}
