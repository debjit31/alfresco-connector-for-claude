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
 * Tool: get_document
 *
 * Retrieves a specific document from Alfresco by node ID.
 * Can return metadata only, or metadata + text content.
 */
@Component
public class GetDocumentTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(GetDocumentTool.class);
    private final AlfrescoDocumentService docService;

    public GetDocumentTool(AlfrescoDocumentService docService) {
        this.docService = docService;
    }

    @Override
    public String getName() {
        return "get_document";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("nodeId", new PropertyDef("string",
                "The Alfresco node ID (UUID) of the document to retrieve"));
        props.put("includeContent", new PropertyDef("boolean",
                "If true, download and include the text content of the document. " +
                "Content is truncated to 50KB to fit context windows. Default: false",
                false));

        return new ToolDefinition(
                getName(),
                "Retrieve a document from the Alfresco repository by its node ID. " +
                "Returns metadata (name, type, dates, properties, aspects, path) and " +
                "optionally the document's text content (truncated to 50KB).",
                new InputSchema(props, List.of("nodeId"))
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(JsonNode arguments) {
        String nodeId = getRequiredString(arguments, "nodeId");
        if (nodeId == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.error("Missing required parameter: 'nodeId'"));
        }

        boolean includeContent = arguments.has("includeContent")
                && arguments.get("includeContent").asBoolean(false);

        log.info("Executing get_document: nodeId={}, includeContent={}", nodeId, includeContent);

        return docService.getDocument(nodeId, includeContent)
                .thenApply(ToolResult::json)
                .exceptionally(ex -> ToolResult.error(
                        "Failed to get document: " + ex.getMessage()));
    }

    private String getRequiredString(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String val = args.get(field).asText().trim();
        return val.isEmpty() ? null : val;
    }
}
