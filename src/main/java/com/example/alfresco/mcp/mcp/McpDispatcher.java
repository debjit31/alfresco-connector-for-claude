package com.example.alfresco.mcp.mcp;

import com.example.alfresco.mcp.config.McpServerProperties;
import com.example.alfresco.mcp.model.McpRequest;
import com.example.alfresco.mcp.model.McpResponse;
import com.example.alfresco.mcp.model.ToolDefinition;
import com.example.alfresco.mcp.model.ToolResult;
import com.example.alfresco.mcp.service.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central MCP protocol dispatcher.
 *
 * Handles JSON-RPC method routing:
 *   - initialize          → server info + capabilities
 *   - tools/list          → registered tool definitions
 *   - tools/call          → dispatch to specific tool
 *   - ping                → pong
 *
 * Tools are auto-discovered via Spring's component scanning:
 * any bean implementing {@link McpTool} is automatically registered.
 */
@Component
public class McpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);

    private final Map<String, McpTool> toolRegistry = new ConcurrentHashMap<>();
    private final McpServerProperties serverProps;
    private final ObjectMapper objectMapper;
    private final List<McpTool> tools;

    public McpDispatcher(McpServerProperties serverProps,
                         ObjectMapper objectMapper,
                         List<McpTool> tools) {
        this.serverProps = serverProps;
        this.objectMapper = objectMapper;
        this.tools = tools;
    }

    @PostConstruct
    public void init() {
        for (McpTool tool : tools) {
            toolRegistry.put(tool.getName(), tool);
            log.info("Registered MCP tool: {}", tool.getName());
        }
        log.info("MCP Dispatcher initialized with {} tools: {}",
                 toolRegistry.size(), toolRegistry.keySet());
    }

    // ═══════════════════════════════════════════════════════════════
    //  DISPATCH
    // ═══════════════════════════════════════════════════════════════

    /**
     * Route an incoming JSON-RPC request to the appropriate handler.
     */
    public CompletableFuture<McpResponse> dispatch(McpRequest request) {
        String method = request.getMethod();
        Object id = request.getId();

        if (method == null || method.isBlank()) {
            return CompletableFuture.completedFuture(
                    McpResponse.error(id, McpResponse.INVALID_REQUEST, "Missing 'method' field"));
        }

        log.debug("Dispatching MCP method: {} (id={})", method, id);

        return switch (method) {
            case "initialize"  -> handleInitialize(id, request.getParams());
            case "tools/list"  -> handleListTools(id);
            case "tools/call"  -> handleCallTool(id, request.getParams());
            case "ping"        -> CompletableFuture.completedFuture(McpResponse.success(id, Map.of("status", "pong")));
            default            -> CompletableFuture.completedFuture(
                    McpResponse.error(id, McpResponse.METHOD_NOT_FOUND,
                            "Unknown method: " + method));
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  INITIALIZE
    // ═══════════════════════════════════════════════════════════════

    private CompletableFuture<McpResponse> handleInitialize(Object id, JsonNode params) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Protocol version
        result.put("protocolVersion", "2024-11-05");

        // Server info
        Map<String, String> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", serverProps.getName());
        serverInfo.put("version", serverProps.getVersion());
        result.put("serverInfo", serverInfo);

        // Capabilities
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        result.put("capabilities", capabilities);

        // Instructions for Claude
        result.put("instructions",
                "This MCP server connects to an Alfresco ECM repository. " +
                "You can search for documents, retrieve document content and metadata, " +
                "upload new documents, and browse folders. Use search_documents first " +
                "to find documents, then get_document or get_metadata for details. " +
                "For semantic search: use index_document to index documents into the " +
                "vector store, then semantic_search to find documents by meaning using " +
                "the hybrid RAG pipeline (dense vectors + BM25 keywords + reranking). " +
                "Use index_status to check what is indexed.");

        log.info("MCP initialize completed for client: {}",
                 params != null ? params.path("clientInfo").path("name").asText("unknown") : "unknown");

        return CompletableFuture.completedFuture(McpResponse.success(id, result));
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIST TOOLS
    // ═══════════════════════════════════════════════════════════════

    private CompletableFuture<McpResponse> handleListTools(Object id) {
        List<ToolDefinition> definitions = toolRegistry.values().stream()
                .map(McpTool::getDefinition)
                .collect(Collectors.toList());

        Map<String, Object> result = Map.of("tools", definitions);
        return CompletableFuture.completedFuture(McpResponse.success(id, result));
    }

    // ═══════════════════════════════════════════════════════════════
    //  CALL TOOL
    // ═══════════════════════════════════════════════════════════════

    private CompletableFuture<McpResponse> handleCallTool(Object id, JsonNode params) {
        if (params == null) {
            return CompletableFuture.completedFuture(
                    McpResponse.error(id, McpResponse.INVALID_PARAMS, "Missing 'params'"));
        }

        String toolName = params.has("name") ? params.get("name").asText() : null;
        if (toolName == null || toolName.isBlank()) {
            return CompletableFuture.completedFuture(
                    McpResponse.error(id, McpResponse.INVALID_PARAMS, "Missing tool 'name' in params"));
        }

        McpTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            return CompletableFuture.completedFuture(
                    McpResponse.error(id, McpResponse.TOOL_NOT_FOUND,
                            "Unknown tool: '" + toolName + "'. Available: " + toolRegistry.keySet()));
        }

        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();

        log.info("Calling tool '{}' with arguments: {}", toolName, arguments);

        return tool.execute(arguments)
                .thenApply(result -> McpResponse.success(id, result))
                .exceptionally(ex -> {
                    log.error("Tool '{}' threw unexpected exception", toolName, ex);
                    return McpResponse.error(id, McpResponse.TOOL_EXEC_ERROR,
                            "Tool execution failed: " + ex.getMessage());
                });
    }

    // ═══════════════════════════════════════════════════════════════
    //  INTROSPECTION
    // ═══════════════════════════════════════════════════════════════

    /** Get all registered tool names (for health checks / debugging) */
    public Set<String> getRegisteredToolNames() {
        return Collections.unmodifiableSet(toolRegistry.keySet());
    }

    /** Check if a tool exists */
    public boolean hasTool(String name) {
        return toolRegistry.containsKey(name);
    }
}
