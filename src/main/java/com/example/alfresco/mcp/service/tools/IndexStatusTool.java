package com.example.alfresco.mcp.service.tools;

import com.example.alfresco.mcp.model.ToolDefinition;
import com.example.alfresco.mcp.model.ToolDefinition.InputSchema;
import com.example.alfresco.mcp.model.ToolDefinition.PropertyDef;
import com.example.alfresco.mcp.model.ToolResult;
import com.example.alfresco.mcp.service.rag.RagService;
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
 * Tool: index_status
 *
 * Inspect and manage the vector index: overall stats, per-document indexed
 * check, single-document removal, or a full clear.
 * Only registered when RAG is enabled.
 */
@Component
@ConditionalOnBean(RagService.class)
public class IndexStatusTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(IndexStatusTool.class);

    private final RagService ragService;

    public IndexStatusTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return "index_status";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        PropertyDef action = new PropertyDef("string",
                "What to do: 'status' (index stats), 'check' (is a node indexed), " +
                "'remove' (delete a node's vectors), 'clear' (wipe the whole index)",
                "status");
        action.setEnumValues(List.of("status", "check", "remove", "clear"));
        props.put("action", action);
        props.put("nodeId", new PropertyDef("string",
                "Alfresco node ID — required for 'check' and 'remove'"));

        InputSchema schema = new InputSchema(props, List.of());

        return new ToolDefinition(
                getName(),
                "Inspect or manage the semantic search index. Use 'status' to see how many " +
                "documents/chunks are indexed, 'check' to test a specific node, 'remove' to " +
                "drop one document's vectors, or 'clear' to reset the entire index.",
                schema);
    }

    @Override
    public CompletableFuture<ToolResult> execute(JsonNode arguments) {
        String action = arguments != null && arguments.has("action") && !arguments.get("action").isNull()
                ? arguments.get("action").asText("status").trim()
                : "status";
        String nodeId = getString(arguments, "nodeId");

        log.info("index_status: action={}, nodeId={}", action, nodeId);

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            switch (action) {
                case "status" -> result.putAll(ragService.getIndexStats());

                case "check" -> {
                    if (nodeId == null) {
                        return error("'nodeId' is required for action 'check'");
                    }
                    result.put("nodeId", nodeId);
                    result.put("indexed", ragService.isDocumentIndexed(nodeId));
                }

                case "remove" -> {
                    if (nodeId == null) {
                        return error("'nodeId' is required for action 'remove'");
                    }
                    int removed = ragService.removeDocument(nodeId);
                    result.put("nodeId", nodeId);
                    result.put("chunksRemoved", removed);
                    result.put("status", removed > 0 ? "removed" : "not_indexed");
                }

                case "clear" -> {
                    ragService.clearIndex();
                    result.put("status", "cleared");
                }

                default -> {
                    return error("Unknown action '" + action +
                            "'. Expected one of: status, check, remove, clear");
                }
            }
            return CompletableFuture.completedFuture(ToolResult.json(result));
        } catch (Exception e) {
            return error("index_status failed: " + e.getMessage());
        }
    }

    private CompletableFuture<ToolResult> error(String message) {
        return CompletableFuture.completedFuture(ToolResult.error(message));
    }

    private String getString(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String val = args.get(field).asText().trim();
        return val.isEmpty() ? null : val;
    }
}
