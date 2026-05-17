package com.example.alfresco.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MCP server identity metadata (name, version, description).
 * Returned in the initialize / server_info responses.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server")
public class McpServerProperties {

    private String name = "alfresco-mcp-server";
    private String version = "1.0.0";
    private String description = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
