package com.example.alfresco.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result returned from a tools/call invocation.
 * Wraps content in the MCP-standard array of content blocks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResult {

    @JsonProperty("content")
    private List<ContentBlock> content = new ArrayList<>();

    @JsonProperty("isError")
    private Boolean isError;

    // ── Factory helpers ─────────────────────────────────────────────

    public static ToolResult text(String text) {
        ToolResult r = new ToolResult();
        r.content.add(ContentBlock.text(text));
        return r;
    }

    public static ToolResult json(Object data) {
        ToolResult r = new ToolResult();
        r.content.add(ContentBlock.json(data));
        return r;
    }

    public static ToolResult error(String message) {
        ToolResult r = new ToolResult();
        r.isError = true;
        r.content.add(ContentBlock.text(message));
        return r;
    }

    public static ToolResult mixed(List<ContentBlock> blocks) {
        ToolResult r = new ToolResult();
        r.content = blocks;
        return r;
    }

    // ── Getters / Setters ───────────────────────────────────────────

    public List<ContentBlock> getContent() { return content; }
    public void setContent(List<ContentBlock> content) { this.content = content; }
    public Boolean getIsError() { return isError; }
    public void setIsError(Boolean isError) { this.isError = isError; }

    // ── Content block ───────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentBlock {

        @JsonProperty("type")
        private String type;

        @JsonProperty("text")
        private String text;

        @JsonProperty("data")
        private Object data;

        @JsonProperty("mimeType")
        private String mimeType;

        public static ContentBlock text(String text) {
            ContentBlock b = new ContentBlock();
            b.type = "text";
            b.text = text;
            return b;
        }

        public static ContentBlock json(Object data) {
            ContentBlock b = new ContentBlock();
            b.type = "text";
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                b.text = om.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            } catch (Exception e) {
                b.text = data.toString();
            }
            b.mimeType = "application/json";
            return b;
        }

        public String getType() { return type; }
        public String getText() { return text; }
        public Object getData() { return data; }
        public String getMimeType() { return mimeType; }
    }
}
