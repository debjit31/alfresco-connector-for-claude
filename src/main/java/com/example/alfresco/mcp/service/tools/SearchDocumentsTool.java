package com.example.alfresco.mcp.service.tools;

import com.example.alfresco.mcp.model.ToolDefinition;
import com.example.alfresco.mcp.model.ToolDefinition.InputSchema;
import com.example.alfresco.mcp.model.ToolDefinition.PropertyDef;
import com.example.alfresco.mcp.model.ToolResult;
import com.example.alfresco.mcp.service.alfresco.AlfrescoDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool: search_documents
 *
 * Searches the Alfresco repository using keywords or AFTS queries.
 * Returns a paginated list of matching documents with metadata.
 */
@Component
public class SearchDocumentsTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(SearchDocumentsTool.class);
    private final AlfrescoDocumentService docService;

    public SearchDocumentsTool(AlfrescoDocumentService docService) {
        this.docService = docService;
    }

    @Override
    public String getName() {
        return "search_documents";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("query", new PropertyDef("string",
                "Search query. Can be a simple keyword or an AFTS query " +
                "(e.g. 'budget report' or 'cm:name:\"Q4*\" AND TYPE:\"cm:content\"')"));
        props.put("maxItems", new PropertyDef("integer",
                "Maximum number of results to return (default: 20)", 20));
        props.put("skipCount", new PropertyDef("integer",
                "Number of results to skip for pagination (default: 0)", 0));

        InputSchema schema = new InputSchema(props, List.of("query"));

        return new ToolDefinition(
                getName(),
                "Search the Alfresco document repository. Supports keyword search and " +
                "Alfresco Full Text Search (AFTS) syntax. Returns document names, IDs, " +
                "types, sizes, and paths.",
                schema
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(JsonNode arguments) {
        // ── Validate ────────────────────────────────────────────────
        String query = getRequiredString(arguments, "query");
        if (query == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.error("Missing required parameter: 'query'"));
        }

        int maxItems = arguments.has("maxItems") ? arguments.get("maxItems").asInt(20) : 20;
        int skipCount = arguments.has("skipCount") ? arguments.get("skipCount").asInt(0) : 0;

        // Clamp
        maxItems = Math.min(maxItems, 100);
        skipCount = Math.max(skipCount, 0);

        log.info("Executing search_documents: query='{}', max={}, skip={}", query, maxItems, skipCount);

        // ── Execute ─────────────────────────────────────────────────
        return docService.searchDocuments(query, maxItems, skipCount)
                .thenApply(ToolResult::json)
                .exceptionally(ex -> ToolResult.error(
                        "Search failed: " + ex.getMessage()));
    }

    private String getRequiredString(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String val = args.get(field).asText().trim();
        return val.isEmpty() ? null : val;
    }
}
