package com.example.alfresco.mcp.service.tools;

import com.example.alfresco.mcp.model.ToolDefinition;
import com.example.alfresco.mcp.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * Contract for every MCP tool.
 *
 * Each implementation:
 *   1. Declares its name, description, and input schema via {@link #getDefinition()}
 *   2. Executes against Alfresco via {@link #execute(JsonNode)}
 *
 * Implementations are auto-discovered by Spring and registered with the dispatcher.
 */
public interface McpTool {

    /** Unique tool name (e.g. "search_documents") */
    String getName();

    /** Full MCP tool definition including JSON Schema for inputs */
    ToolDefinition getDefinition();

    /** Execute the tool with the provided arguments (async) */
    CompletableFuture<ToolResult> execute(JsonNode arguments);
}
