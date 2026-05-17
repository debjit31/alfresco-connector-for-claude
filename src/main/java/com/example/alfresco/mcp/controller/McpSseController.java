package com.example.alfresco.mcp.controller;

import com.example.alfresco.mcp.mcp.McpDispatcher;
import com.example.alfresco.mcp.model.McpRequest;
import com.example.alfresco.mcp.model.McpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE-based MCP transport.
 *
 * Flow:
 *   1. Client opens SSE connection: GET /mcp/sse
 *   2. Server sends an "endpoint" event with the POST URL for messages
 *   3. Client sends JSON-RPC messages via: POST /mcp/messages?sessionId={id}
 *   4. Server processes and streams responses back via SSE
 *
 * This is the transport Claude Code uses by default.
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP SSE Transport")
public class McpSseController {

    private static final Logger log = LoggerFactory.getLogger(McpSseController.class);

    private final McpDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final Map<String, SseEmitter> sessions = new ConcurrentHashMap<>();

    public McpSseController(McpDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * SSE connection endpoint. Client opens this to establish a session.
     */
    @Operation(
            summary = "Open SSE session",
            description = """
                    Opens a Server-Sent Events stream for MCP communication.
                    This is the primary transport for Claude Code.
                    
                    **Flow:**
                    1. Client sends `GET /mcp/sse` — receives an SSE stream
                    2. Server sends an `endpoint` event containing the POST URL
                    3. Client POSTs JSON-RPC messages to that URL
                    4. Server streams responses back via this SSE connection
                    
                    **Note:** This endpoint returns `text/event-stream` — it won't render
                    properly in Swagger's "Try it out". Use `curl`, `httpie`, or Claude Code
                    to test SSE connections:
                    ```
                    curl -N http://localhost:3000/mcp/sse
                    ```
                    """
    )
    @ApiResponse(responseCode = "200",
            description = "SSE stream opened. First event is `endpoint` with the message POST URL.",
            content = @Content(mediaType = "text/event-stream"))
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect() {
        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        sessions.put(sessionId, emitter);
        log.info("SSE session opened: {}", sessionId);

        emitter.onCompletion(() -> {
            sessions.remove(sessionId);
            log.info("SSE session closed: {}", sessionId);
        });
        emitter.onTimeout(() -> {
            sessions.remove(sessionId);
            log.warn("SSE session timed out: {}", sessionId);
        });
        emitter.onError(e -> {
            sessions.remove(sessionId);
            log.error("SSE session error: {}", sessionId, e);
        });

        // Send the endpoint event so the client knows where to POST messages
        try {
            String endpointUrl = "/mcp/messages?sessionId=" + sessionId;
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data(endpointUrl));
            log.debug("Sent endpoint event: {}", endpointUrl);
        } catch (IOException e) {
            log.error("Failed to send endpoint event", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * Message endpoint. Client POSTs JSON-RPC messages here.
     * Responses are streamed back via the SSE connection.
     */
    @Operation(
            summary = "Send JSON-RPC message via SSE session",
            description = """
                    Post a JSON-RPC 2.0 message to an active SSE session.
                    The response is streamed back through the SSE connection (not in the HTTP response body).
                    
                    **Prerequisites:** Open an SSE session first via `GET /mcp/sse` to get a session ID.
                    
                    Returns `202 Accepted` immediately — the actual result arrives via SSE.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Message accepted; response will arrive via SSE"),
            @ApiResponse(responseCode = "400", description = "Unknown or expired session ID")
    })
    @PostMapping(
            value = "/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> message(
            @Parameter(
                    description = "Session ID from the SSE `endpoint` event",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @RequestParam String sessionId,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "JSON-RPC 2.0 request",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = McpRequest.class),
                            examples = @ExampleObject(
                                    name = "search via SSE",
                                    value = """
                                            {
                                              "jsonrpc": "2.0",
                                              "id": 1,
                                              "method": "tools/call",
                                              "params": {
                                                "name": "search_documents",
                                                "arguments": { "query": "budget" }
                                              }
                                            }
                                            """
                            )
                    )
            )
            @RequestBody McpRequest request) {

        SseEmitter emitter = sessions.get(sessionId);
        if (emitter == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown session: " + sessionId));
        }

        log.debug("SSE message: session={}, method={}, id={}",
                  sessionId, request.getMethod(), request.getId());

        // Process async, stream response via SSE
        dispatcher.dispatch(request).thenAccept(response -> {
            try {
                String json = objectMapper.writeValueAsString(response);
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(json));
            } catch (IOException e) {
                log.error("Failed to send SSE response for session {}", sessionId, e);
            }
        }).exceptionally(ex -> {
            try {
                McpResponse errResponse = McpResponse.error(
                        request.getId(), McpResponse.INTERNAL_ERROR, ex.getMessage());
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(objectMapper.writeValueAsString(errResponse)));
            } catch (IOException e) {
                log.error("Failed to send SSE error for session {}", sessionId, e);
            }
            return null;
        });

        // Return 202 Accepted — the actual response goes via SSE
        return ResponseEntity.accepted().build();
    }

    /**
     * List active SSE sessions (debug endpoint).
     */
    @Operation(
            summary = "List active SSE sessions",
            description = "Returns the count and IDs of currently active SSE sessions. " +
                    "Useful for debugging and monitoring."
    )
    @ApiResponse(responseCode = "200", description = "Active sessions info")
    @GetMapping(value = "/sse/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listSessions() {
        return ResponseEntity.ok(Map.of(
                "activeSessions", sessions.size(),
                "sessionIds", sessions.keySet()
        ));
    }
}
