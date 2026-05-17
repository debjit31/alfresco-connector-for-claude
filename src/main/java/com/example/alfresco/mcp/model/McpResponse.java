package com.example.alfresco.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JSON-RPC 2.0 response envelope.
 * Either "result" or "error" is set, never both.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "JSON-RPC 2.0 response. Contains either 'result' (on success) or 'error' (on failure), never both.")
public class McpResponse {

    @JsonProperty("jsonrpc")
    @Schema(description = "JSON-RPC version", example = "2.0")
    private final String jsonrpc = "2.0";

    @JsonProperty("id")
    @Schema(description = "Correlates with the request ID", example = "1")
    private Object id;

    @JsonProperty("result")
    @Schema(description = "Result payload on success (type depends on the method called)")
    private Object result;

    @JsonProperty("error")
    @Schema(description = "Error payload on failure")
    private McpError error;

    // ── Factory methods ─────────────────────────────────────────────

    public static McpResponse success(Object id, Object result) {
        McpResponse r = new McpResponse();
        r.id = id;
        r.result = result;
        return r;
    }

    public static McpResponse error(Object id, int code, String message) {
        McpResponse r = new McpResponse();
        r.id = id;
        r.error = new McpError(code, message);
        return r;
    }

    public static McpResponse error(Object id, int code, String message, Object data) {
        McpResponse r = new McpResponse();
        r.id = id;
        r.error = new McpError(code, message, data);
        return r;
    }

    // ── Getters ─────────────────────────────────────────────────────

    public String getJsonrpc() { return jsonrpc; }
    public Object getId() { return id; }
    public Object getResult() { return result; }
    public McpError getError() { return error; }

    // ── Error codes (JSON-RPC standard + MCP extensions) ────────────

    public static final int PARSE_ERROR      = -32700;
    public static final int INVALID_REQUEST  = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS   = -32602;
    public static final int INTERNAL_ERROR   = -32603;
    public static final int TOOL_NOT_FOUND   = -32001;
    public static final int TOOL_EXEC_ERROR  = -32002;

    // ── Nested error object ─────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "JSON-RPC error object")
    public static class McpError {

        @JsonProperty("code")
        @Schema(description = "Error code (standard JSON-RPC or MCP custom)", example = "-32601")
        private int code;

        @JsonProperty("message")
        @Schema(description = "Human-readable error message", example = "Unknown method: invalid_method")
        private String message;

        @JsonProperty("data")
        @Schema(description = "Additional error data (optional)")
        private Object data;

        public McpError() {}
        public McpError(int code, String message) {
            this.code = code; this.message = message;
        }
        public McpError(int code, String message, Object data) {
            this.code = code; this.message = message; this.data = data;
        }

        public int getCode() { return code; }
        public String getMessage() { return message; }
        public Object getData() { return data; }
    }
}
