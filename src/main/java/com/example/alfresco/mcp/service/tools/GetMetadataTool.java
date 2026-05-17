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
 * Tool: get_metadata
 *
 * Retrieves all metadata, properties, and aspect information for a node.
 * Unlike get_document, this never downloads content — metadata only.
 */
@Component
public class GetMetadataTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(GetMetadataTool.class);
    private final AlfrescoDocumentService docService;

    public GetMetadataTool(AlfrescoDocumentService docService) {
        this.docService = docService;
    }

    @Override
    public String getName() {
        return "get_metadata";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("nodeId", new PropertyDef("string",
                "The Alfresco node ID (UUID) of the document or folder"));

        return new ToolDefinition(
                getName(),
                "Retrieve detailed metadata for a document or folder in Alfresco. " +
                "Returns properties (title, description, author, custom properties), " +
                "aspect names, node type, path, timestamps, and content info. " +
                "Does NOT download the document content — use get_document with " +
                "includeContent=true for that.",
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

        log.info("Executing get_metadata: nodeId={}", nodeId);

        return docService.getMetadata(nodeId)
                .thenApply(ToolResult::json)
                .exceptionally(ex -> ToolResult.error(
                        "Failed to get metadata: " + ex.getMessage()));
    }

    private String getRequiredString(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String val = args.get(field).asText().trim();
        return val.isEmpty() ? null : val;
    }
}
