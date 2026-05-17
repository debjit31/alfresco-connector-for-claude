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
 * Tool: list_folder
 *
 * Lists the contents (files and sub-folders) of an Alfresco folder.
 */
@Component
public class ListFolderTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(ListFolderTool.class);
    private final AlfrescoDocumentService docService;

    public ListFolderTool(AlfrescoDocumentService docService) {
        this.docService = docService;
    }

    @Override
    public String getName() {
        return "list_folder";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("nodeId", new PropertyDef("string",
                "The node ID of the folder to list. Use '-root-' for repository root, " +
                "'-my-' for user home, '-shared-' for shared files."));
        props.put("maxItems", new PropertyDef("integer",
                "Maximum children to return (default: 25)", 25));
        props.put("skipCount", new PropertyDef("integer",
                "Pagination offset (default: 0)", 0));

        return new ToolDefinition(
                getName(),
                "List the contents of a folder in the Alfresco repository. Returns child " +
                "documents and sub-folders with their names, types, sizes, and paths. " +
                "Use '-root-' as nodeId for the repository root.",
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

        int maxItems = arguments.has("maxItems") ? arguments.get("maxItems").asInt(25) : 25;
        int skipCount = arguments.has("skipCount") ? arguments.get("skipCount").asInt(0) : 0;

        log.info("Executing list_folder: nodeId={}, max={}", nodeId, maxItems);

        return docService.listFolder(nodeId, Math.min(maxItems, 100), Math.max(skipCount, 0))
                .thenApply(ToolResult::json)
                .exceptionally(ex -> ToolResult.error(
                        "Failed to list folder: " + ex.getMessage()));
    }

    private String getRequiredString(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String val = args.get(field).asText().trim();
        return val.isEmpty() ? null : val;
    }
}
