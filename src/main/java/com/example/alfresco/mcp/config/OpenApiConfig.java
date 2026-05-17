package com.example.alfresco.mcp.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Central OpenAPI / Swagger configuration.
 *
 * Swagger UI available at:  http://localhost:3000/swagger-ui.html
 * OpenAPI spec at:          http://localhost:3000/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI alfrescoMcpOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Alfresco MCP Server API")
                        .version("1.0.0")
                        .description("""
                                Model Context Protocol (MCP) server that bridges Claude AI to an Alfresco ECM repository.

                                ## Endpoints

                                **MCP Protocol (JSON-RPC)**
                                - `POST /mcp` — Raw JSON-RPC 2.0 endpoint (initialize, tools/list, tools/call)
                                - `GET /mcp` — Server info and registered tools
                                - `GET /mcp/sse` — SSE transport for Claude Code

                                **REST Convenience API**
                                - `POST /api/tools/*` — Individual REST endpoints per tool with typed schemas.
                                  Use these from Swagger UI to test each tool with pre-filled examples.

                                ## How It Works
                                1. Claude connects via SSE or HTTP
                                2. Sends `initialize` → gets server capabilities
                                3. Sends `tools/list` → discovers available tools
                                4. Sends `tools/call` with tool name + arguments → gets structured results
                                """)
                        .contact(new Contact()
                                .name("MCP Server Admin")
                                .email("admin@example.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .externalDocs(new ExternalDocumentation()
                        .description("MCP Protocol Specification")
                        .url("https://modelcontextprotocol.io/specification"))
                .servers(List.of(
                        new Server().url("http://localhost:3000").description("Local development")))
                .tags(List.of(
                        new Tag().name("MCP Protocol")
                                .description("Raw JSON-RPC 2.0 MCP endpoints — used by Claude Code / Claude AI"),
                        new Tag().name("MCP SSE Transport")
                                .description("Server-Sent Events transport — the primary transport for Claude Code"),
                        new Tag().name("Tools — Search")
                                .description("Search the Alfresco document repository"),
                        new Tag().name("Tools — Documents")
                                .description("Retrieve, upload, and inspect documents"),
                        new Tag().name("Tools — Folders")
                                .description("Browse folder contents in the repository")
                ));
    }

    /** Group: MCP protocol endpoints */
    @Bean
    public GroupedOpenApi mcpProtocolApi() {
        return GroupedOpenApi.builder()
                .group("1-mcp-protocol")
                .displayName("MCP Protocol")
                .pathsToMatch("/mcp/**")
                .build();
    }

    /** Group: REST convenience endpoints for tools */
    @Bean
    public GroupedOpenApi toolsRestApi() {
        return GroupedOpenApi.builder()
                .group("2-tools-rest")
                .displayName("Tools REST API")
                .pathsToMatch("/api/tools/**")
                .build();
    }

    /** Group: all endpoints */
    @Bean
    public GroupedOpenApi allApi() {
        return GroupedOpenApi.builder()
                .group("0-all")
                .displayName("All Endpoints")
                .pathsToMatch("/**")
                .pathsToExclude("/actuator/**")
                .build();
    }
}
