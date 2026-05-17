package com.example.alfresco.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Describes a single MCP tool that Claude can call.
 * Mirrors the MCP "Tool" schema:
 *   { name, description, inputSchema: { type: "object", properties, required } }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("inputSchema")
    private InputSchema inputSchema;

    // ── Constructors ────────────────────────────────────────────────

    public ToolDefinition() {}

    public ToolDefinition(String name, String description, InputSchema inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    // ── Getters / Setters ───────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public InputSchema getInputSchema() { return inputSchema; }
    public void setInputSchema(InputSchema inputSchema) { this.inputSchema = inputSchema; }

    // ── Nested InputSchema ──────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InputSchema {

        @JsonProperty("type")
        private String type = "object";

        @JsonProperty("properties")
        private Map<String, PropertyDef> properties;

        @JsonProperty("required")
        private java.util.List<String> required;

        public InputSchema() {}

        public InputSchema(Map<String, PropertyDef> properties, java.util.List<String> required) {
            this.properties = properties;
            this.required = required;
        }

        public String getType() { return type; }
        public Map<String, PropertyDef> getProperties() { return properties; }
        public void setProperties(Map<String, PropertyDef> properties) { this.properties = properties; }
        public java.util.List<String> getRequired() { return required; }
        public void setRequired(java.util.List<String> required) { this.required = required; }
    }

    // ── Nested PropertyDef ──────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PropertyDef {

        @JsonProperty("type")
        private String type;

        @JsonProperty("description")
        private String description;

        @JsonProperty("default")
        private Object defaultValue;

        @JsonProperty("enum")
        private java.util.List<String> enumValues;

        public PropertyDef() {}

        public PropertyDef(String type, String description) {
            this.type = type;
            this.description = description;
        }

        public PropertyDef(String type, String description, Object defaultValue) {
            this.type = type;
            this.description = description;
            this.defaultValue = defaultValue;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Object getDefaultValue() { return defaultValue; }
        public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }
        public java.util.List<String> getEnumValues() { return enumValues; }
        public void setEnumValues(java.util.List<String> enumValues) { this.enumValues = enumValues; }
    }
}
