package com.example.alfresco.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Represents an incoming JSON-RPC 2.0 request as defined by the MCP spec.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "JSON-RPC 2.0 request envelope for the MCP protocol")
public class McpRequest {

    @JsonProperty("jsonrpc")
    @Schema(description = "JSON-RPC version (always \"2.0\")", example = "2.0", defaultValue = "2.0")
    private String jsonrpc = "2.0";

    @JsonProperty("id")
    @Schema(description = "Request ID for correlating responses", example = "1")
    private Object id;

    @JsonProperty("method")
    @Schema(
            description = "MCP method to invoke",
            example = "tools/call",
            allowableValues = {"initialize", "tools/list", "tools/call", "ping"}
    )
    private String method;

    @JsonProperty("params")
    @Schema(description = "Method parameters. For tools/call: { \"name\": \"<tool>\", \"arguments\": { ... } }")
    private JsonNode params;

    // ── Constructors ────────────────────────────────────────────────

    public McpRequest() {}

    public McpRequest(String method, JsonNode params) {
        this.method = method;
        this.params = params;
    }

    // ── Getters / Setters ───────────────────────────────────────────

    public String getJsonrpc() { return jsonrpc; }
    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }

    public Object getId() { return id; }
    public void setId(Object id) { this.id = id; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public JsonNode getParams() { return params; }
    public void setParams(JsonNode params) { this.params = params; }
}
