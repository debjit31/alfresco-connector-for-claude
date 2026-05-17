package com.example.alfresco.mcp.service.alfresco;

import com.example.alfresco.mcp.client.AlfrescoRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * High-level Alfresco operations.
 * Normalizes raw API responses into clean structures for MCP tool output.
 */
@Service
public class AlfrescoDocumentService {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoDocumentService.class);

    private final AlfrescoRestClient client;
    private final ObjectMapper mapper;

    public AlfrescoDocumentService(AlfrescoRestClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════

    /**
     * Search Alfresco and return a simplified result list.
     */
    public CompletableFuture<Map<String, Object>> searchDocuments(String query, int maxItems, int skipCount) {
        // Use AFTS (Alfresco Full Text Search) by default
        String aftsQuery = query;
        // If the query looks like a plain keyword (no AFTS operators), wrap it
        if (!query.contains(":") && !query.contains("(") && !query.contains("AND") && !query.contains("OR")) {
            aftsQuery = "cm:name:\"*" + query + "*\" OR cm:content:\"" + query + "\" OR cm:title:\"" + query + "\"";
        }

        return client.search(aftsQuery, "afts", maxItems, skipCount)
                .thenApply(this::normalizeSearchResults);
    }

    private Map<String, Object> normalizeSearchResults(JsonNode raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> documents = new ArrayList<>();

        JsonNode entries = raw.path("list").path("entries");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                JsonNode e = entry.path("entry");
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("nodeId", e.path("id").asText());
                doc.put("name", e.path("name").asText());
                doc.put("nodeType", e.path("nodeType").asText());
                doc.put("isFile", e.path("isFile").asBoolean());
                doc.put("isFolder", e.path("isFolder").asBoolean());
                doc.put("createdAt", e.path("createdAt").asText());
                doc.put("modifiedAt", e.path("modifiedAt").asText());
                doc.put("createdByUser", e.path("createdByUser").path("displayName").asText());
                doc.put("modifiedByUser", e.path("modifiedByUser").path("displayName").asText());

                // Content info (size, mimeType)
                JsonNode content = e.path("content");
                if (!content.isMissingNode()) {
                    Map<String, Object> contentInfo = new LinkedHashMap<>();
                    contentInfo.put("mimeType", content.path("mimeType").asText());
                    contentInfo.put("sizeInBytes", content.path("sizeInBytes").asLong());
                    doc.put("content", contentInfo);
                }

                // Path
                JsonNode path = e.path("path");
                if (!path.isMissingNode()) {
                    doc.put("path", path.path("name").asText());
                }

                documents.add(doc);
            }
        }

        // Pagination
        JsonNode pagination = raw.path("list").path("pagination");
        Map<String, Object> paging = new LinkedHashMap<>();
        paging.put("totalItems", pagination.path("totalItems").asInt());
        paging.put("maxItems", pagination.path("maxItems").asInt());
        paging.put("skipCount", pagination.path("skipCount").asInt());
        paging.put("hasMoreItems", pagination.path("hasMoreItems").asBoolean());

        result.put("documents", documents);
        result.put("pagination", paging);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET DOCUMENT (metadata + optional content)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a document's metadata and optionally its text content.
     */
    public CompletableFuture<Map<String, Object>> getDocument(String nodeId, boolean includeContent) {
        CompletableFuture<JsonNode> metaFuture = client.getNode(nodeId);

        if (includeContent) {
            CompletableFuture<String> contentFuture = client.getContent(nodeId);
            return metaFuture.thenCombine(contentFuture, (meta, content) -> {
                Map<String, Object> result = normalizeNodeInfo(meta);
                // Truncate content to 50KB to avoid overwhelming Claude's context
                if (content.length() > 50_000) {
                    result.put("content", content.substring(0, 50_000));
                    result.put("contentTruncated", true);
                    result.put("fullContentLength", content.length());
                } else {
                    result.put("content", content);
                    result.put("contentTruncated", false);
                }
                return result;
            });
        }

        return metaFuture.thenApply(this::normalizeNodeInfo);
    }

    private Map<String, Object> normalizeNodeInfo(JsonNode raw) {
        JsonNode entry = raw.path("entry");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("nodeId", entry.path("id").asText());
        doc.put("name", entry.path("name").asText());
        doc.put("nodeType", entry.path("nodeType").asText());
        doc.put("isFile", entry.path("isFile").asBoolean());
        doc.put("isFolder", entry.path("isFolder").asBoolean());
        doc.put("createdAt", entry.path("createdAt").asText());
        doc.put("modifiedAt", entry.path("modifiedAt").asText());
        doc.put("createdByUser", entry.path("createdByUser").path("displayName").asText());
        doc.put("modifiedByUser", entry.path("modifiedByUser").path("displayName").asText());

        // Properties
        JsonNode properties = entry.path("properties");
        if (!properties.isMissingNode()) {
            Map<String, Object> props = new LinkedHashMap<>();
            properties.fields().forEachRemaining(f -> props.put(f.getKey(), f.getValue().asText()));
            doc.put("properties", props);
        }

        // Aspects
        JsonNode aspects = entry.path("aspectNames");
        if (aspects.isArray()) {
            List<String> aspectList = new ArrayList<>();
            aspects.forEach(a -> aspectList.add(a.asText()));
            doc.put("aspects", aspectList);
        }

        // Content info
        JsonNode content = entry.path("content");
        if (!content.isMissingNode()) {
            Map<String, Object> contentInfo = new LinkedHashMap<>();
            contentInfo.put("mimeType", content.path("mimeType").asText());
            contentInfo.put("sizeInBytes", content.path("sizeInBytes").asLong());
            contentInfo.put("encoding", content.path("encoding").asText());
            doc.put("contentInfo", contentInfo);
        }

        // Path
        JsonNode path = entry.path("path");
        if (!path.isMissingNode()) {
            doc.put("path", path.path("name").asText());
        }

        return doc;
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET METADATA ONLY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetch only metadata and properties for a node.
     */
    public CompletableFuture<Map<String, Object>> getMetadata(String nodeId) {
        return client.getNode(nodeId).thenApply(this::normalizeNodeInfo);
    }

    // ═══════════════════════════════════════════════════════════════
    //  UPLOAD
    // ═══════════════════════════════════════════════════════════════

    /**
     * Upload a document to a target folder.
     */
    public CompletableFuture<Map<String, Object>> uploadDocument(String parentNodeId,
                                                                 String fileName,
                                                                 String content,
                                                                 String mimeType) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return client.uploadContent(parentNodeId, fileName, bytes, mimeType)
                .thenApply(raw -> {
                    Map<String, Object> result = normalizeNodeInfo(raw);
                    result.put("uploadStatus", "success");
                    return result;
                });
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIST FOLDER
    // ═══════════════════════════════════════════════════════════════

    /**
     * List contents of a folder.
     */
    public CompletableFuture<Map<String, Object>> listFolder(String nodeId, int maxItems, int skipCount) {
        return client.listChildren(nodeId, maxItems, skipCount)
                .thenApply(this::normalizeSearchResults);
    }
}
