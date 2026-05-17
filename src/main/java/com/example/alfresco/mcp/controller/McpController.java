package com.example.alfresco.mcp.controller;

import com.example.alfresco.mcp.mcp.McpDispatcher;
import com.example.alfresco.mcp.model.McpRequest;
import com.example.alfresco.mcp.model.McpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Single HTTP endpoint for the MCP JSON-RPC protocol.
 *
 * All MCP communication flows through POST /mcp.
 * The controller delegates entirely to {@link McpDispatcher}.
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP Protocol")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public McpController(McpDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * Main MCP endpoint. Accepts JSON-RPC 2.0 requests.
     */
    @Operation(
            summary = "MCP JSON-RPC endpoint",
            description = """
                    The primary MCP endpoint. Send JSON-RPC 2.0 requests to interact with the server.
                    
                    **Supported methods:**
                    - `initialize` — Handshake; returns server info and capabilities
                    - `tools/list` — Returns all available tool definitions with input schemas
                    - `tools/call` — Execute a tool by name with arguments
                    - `ping` — Health check (returns pong)
                    
                    Select an example from the dropdown in the request body to pre-fill payloads
                    for each method/tool. Use the **Tools REST API** group for per-tool testing
                    with typed schemas and individual forms.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "JSON-RPC response",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = McpResponse.class)))
    })
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public CompletableFuture<ResponseEntity<McpResponse>> handle(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "JSON-RPC 2.0 request. Select an example from the dropdown.",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = McpRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "1 — initialize",
                                            summary = "Handshake with the MCP server",
                                            value = """
                                                    {
                                                      "jsonrpc": "2.0",
                                                      "id": 1,
                                                      "method": "initialize",
                                                      "params": {
                                                        "protocolVersion": "2024-11-05",
                                                        "clientInfo": { "name": "swagger-ui", "version": "1.0" }
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "2 — tools/list",
                                            summary = "List all available tools with schemas",
                                            value = """
                                                    {
                                                      "jsonrpc": "2.0",
                                                      "id": 2,
                                                      "method": "tools/list",
                                                      "params": {}
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "3 — search_documents",
                                            summary = "Search repository for documents",
                                            value = """
                                                    {
                                                      "jsonrpc": "2.0",
                                                      "id": 3,
                                                      "method": "tools/call",
                                                      "params": {
                                                        "name": "search_documents",
                                                        "arguments": {
                                                          "query": "budget report",
                                                          "maxItems": 10
                                                        }
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "4 — get_document",
                                            summary = "Fetch document metadata + content",
                                            value = """
                                                    {
                                                      "jsonrpc": "2.0",
                                                      "id": 4,
                                                      "method": "tools/call",
                                                      "params": {
                                                        "name": "get_document",
                                                        "arguments": {
                                                          "nodeId": "YOUR-NODE-ID-HERE",
                                                          "includeContent": true
                                                        }
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "5 — upload_document",
                                            summary = "Upload a new file",
                                            value = """
                                                    {
                                                      "jsonrpc": "2.0",
                                                      "id": 5,
                                                      "method": "tools/call",
                                                      "params": {
                                                        "name": "upload_document",
                                                        "arguments": {
                                                          "parentNodeId": "-my-",
                                                          "fileName": "test-upload.txt",
                                                          "content": "Hello from Swagger UI!",
                                                          "mimeType": "text/plain"
                                                        }
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "6 — get_metadata",
                                            summary = "Get node properties and aspects",
                                            value = """
                                                    {
                                                      "jsonrpc": "2.0",
                                                      "id": 6,
                                                      "method": "tools/call",
                                                      "params": {
                                                        "name": "get_metadata",
                                                        "arguments": {
                                                          "nodeId": "YOUR-NODE-ID-HERE"
                                                        }
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "7 — list_folder",
                                            summary = "Browse folder contents",
                                            value = """
                                                    {
                                                      "jsonrpc": "2.0",
                                                      "id": 7,
                                                      "method": "tools/call",
                                                      "params": {
                                                        "name": "list_folder",
                                                        "arguments": {
                                                          "nodeId": "-root-",
                                                          "maxItems": 10
                                                        }
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "8 — ping",
                                            summary = "Health check",
                                            value = """
                                                    {
                                                      "jsonrpc": "2.0",
                                                      "id": 99,
                                                      "method": "ping",
                                                      "params": {}
                                                    }
                                                    """
                                    )
                            }
                    )
            )
            @RequestBody McpRequest request) {

        log.debug("MCP request: method={}, id={}", request.getMethod(), request.getId());

        return dispatcher.dispatch(request)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("Unhandled MCP error", ex);
                    return ResponseEntity.internalServerError()
                            .body(McpResponse.error(
                                    request.getId(),
                                    McpResponse.INTERNAL_ERROR,
                                    "Internal server error: " + ex.getMessage()));
                });
    }

    /**
     * Health / info endpoint for quick connectivity checks.
     */
    @Operation(
            summary = "Server info & health check",
            description = "Returns server status and the list of registered MCP tools. " +
                    "Useful for health checks and verifying the server is running."
    )
    @ApiResponse(responseCode = "200", description = "Server info",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {
                              "server": "alfresco-mcp-server",
                              "status": "running",
                              "tools": ["search_documents", "get_document", "upload_document", "get_metadata", "list_folder"],
                              "mcp_endpoint": "POST /mcp"
                            }
                            """)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "server", "alfresco-mcp-server",
                "status", "running",
                "tools", dispatcher.getRegisteredToolNames(),
                "mcp_endpoint", "POST /mcp"
        ));
    }
}
