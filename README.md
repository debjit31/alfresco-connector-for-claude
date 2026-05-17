# Alfresco MCP Server

A production-grade **Model Context Protocol (MCP)** server in Java/Spring Boot that bridges **Claude AI** to an **Alfresco ECM** repository. Claude can search, read, upload, and inspect documents through structured tool calls.

---

## Architecture

```
┌──────────────┐     JSON-RPC / SSE      ┌─────────────────────┐     REST API     ┌───────────────┐
│  Claude Code │ ◄──────────────────────► │  Alfresco MCP       │ ◄──────────────► │   Alfresco    │
│  / Claude AI │                          │  Server (:3000)     │                  │   (:8080)     │
└──────────────┘                          └─────────────────────┘                  └───────────────┘
                                           │                   │
                                           │  McpDispatcher    │
                                           │   ├─ SearchTool   │
                                           │   ├─ GetDocTool   │
                                           │   ├─ UploadTool   │
                                           │   ├─ MetadataTool │
                                           │   └─ ListFolder   │
                                           └───────────────────┘
```

## Project Structure

```
alfresco-mcp-server/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── src/main/
│   ├── java/com/example/alfresco/mcp/
│   │   ├── AlfrescoMcpServerApplication.java   # Entry point
│   │   ├── config/
│   │   │   ├── AlfrescoProperties.java          # Alfresco connection config
│   │   │   ├── McpServerProperties.java         # MCP server metadata
│   │   │   ├── OpenApiConfig.java               # Swagger / OpenAPI configuration
│   │   │   └── WebClientConfig.java             # Reactive HTTP client
│   │   ├── controller/
│   │   │   ├── McpController.java               # POST /mcp  (JSON-RPC)
│   │   │   ├── McpSseController.java            # GET /mcp/sse (SSE transport)
│   │   │   ├── McpToolsRestController.java      # REST API per tool (for Swagger)
│   │   │   └── GlobalExceptionHandler.java      # Error handling
│   │   ├── mcp/
│   │   │   └── McpDispatcher.java               # Protocol router
│   │   ├── model/
│   │   │   ├── McpRequest.java                  # JSON-RPC request
│   │   │   ├── McpResponse.java                 # JSON-RPC response
│   │   │   ├── ToolDefinition.java              # Tool schema
│   │   │   ├── ToolResult.java                  # Tool output
│   │   │   └── dto/                             # Swagger-typed request bodies
│   │   │       ├── SearchDocumentsRequest.java
│   │   │       ├── GetDocumentRequest.java
│   │   │       ├── UploadDocumentRequest.java
│   │   │       ├── GetMetadataRequest.java
│   │   │       └── ListFolderRequest.java
│   │   ├── client/
│   │   │   └── AlfrescoRestClient.java          # Low-level REST calls
│   │   ├── service/
│   │   │   ├── alfresco/
│   │   │   │   ├── AlfrescoDocumentService.java # Business logic
│   │   │   │   └── RagExtension.java            # RAG extension point
│   │   │   └── tools/
│   │   │       ├── McpTool.java                 # Tool interface
│   │   │       ├── SearchDocumentsTool.java
│   │   │       ├── GetDocumentTool.java
│   │   │       ├── UploadDocumentTool.java
│   │   │       ├── GetMetadataTool.java
│   │   │       └── ListFolderTool.java
│   │   └── util/
│   │       ├── AuthProvider.java                # Auth abstraction
│   │       ├── BasicAuthProvider.java           # Basic auth impl
│   │       └── OAuth2AuthProvider.java          # OIDC stub
│   └── resources/
│       └── application.yml
└── README.md
```

## Prerequisites

- Java 17+
- Maven 3.8+
- Alfresco running at `http://localhost:8080` (Community or Enterprise)

## Quick Start

### 1. Configure

Edit `src/main/resources/application.yml` if your Alfresco is on a different host/port:

```yaml
alfresco:
  base-url: http://localhost:8080/alfresco
  auth:
    username: admin
    password: admin
```

### 2. Build & Run

```bash
cd alfresco-mcp-server
mvn clean package -DskipTests
java -jar target/alfresco-mcp-server-1.0.0.jar
```

Or with Maven directly:

```bash
mvn spring-boot:run
```

The server starts on **port 3000**.

### 3. Verify

```bash
curl http://localhost:3000/mcp
```

### 4. Open Swagger UI

Navigate to: **http://localhost:3000/swagger-ui.html**

You'll see three API groups in the top-right dropdown:

| Group | What it contains |
|-------|------------------|
| **All Endpoints** | Everything in one view |
| **MCP Protocol** | Raw JSON-RPC endpoint (`POST /mcp`), SSE transport, server info |
| **Tools REST API** | Individual `POST /api/tools/*` endpoints — one per tool with typed schemas |

The **Tools REST API** group is the most convenient for testing: each tool has its own endpoint with pre-filled example bodies, typed parameter schemas, and sample responses. Click "Try it out" on any endpoint to fire a request at your Alfresco instance.

The **MCP Protocol** group's `POST /mcp` endpoint includes a dropdown of 8 example payloads (initialize, tools/list, each tool call, and ping) so you can test the raw JSON-RPC interface too.

Expected:
```json
{
  "server": "alfresco-mcp-server",
  "status": "running",
  "tools": ["search_documents", "get_document", "upload_document", "get_metadata", "list_folder"],
  "mcp_endpoint": "POST /mcp"
}
```

---

## MCP Tools

| Tool | Description |
|------|-------------|
| `search_documents` | Search the repository by keyword or AFTS query |
| `get_document` | Fetch a document by node ID (metadata + optional content) |
| `upload_document` | Upload a new file to a folder |
| `get_metadata` | Get all properties, aspects, and metadata for a node |
| `list_folder` | Browse folder contents |

---

## Testing with curl

### Initialize

```bash
curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "clientInfo": { "name": "test-client", "version": "1.0" }
    }
  }' | jq .
```

### List Tools

```bash
curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {}
  }' | jq .
```

### Search Documents

```bash
curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "search_documents",
      "arguments": {
        "query": "budget report",
        "maxItems": 5
      }
    }
  }' | jq .
```

### Get Document (with content)

```bash
curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{
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
  }' | jq .
```

### Upload Document

```bash
curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 5,
    "method": "tools/call",
    "params": {
      "name": "upload_document",
      "arguments": {
        "parentNodeId": "-my-",
        "fileName": "meeting-notes.txt",
        "content": "Q4 planning meeting notes:\n1. Revenue targets\n2. Hiring plan\n3. Product roadmap",
        "mimeType": "text/plain"
      }
    }
  }' | jq .
```

### Get Metadata

```bash
curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 6,
    "method": "tools/call",
    "params": {
      "name": "get_metadata",
      "arguments": {
        "nodeId": "YOUR-NODE-ID-HERE"
      }
    }
  }' | jq .
```

### List Folder

```bash
curl -s -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{
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
  }' | jq .
```

---

## Connecting to Claude Code

### Option A: SSE Transport (recommended)

Claude Code connects via the SSE endpoint. Add this to your Claude Code MCP config:

**File: `~/.claude/claude_desktop_config.json`**

```json
{
  "mcpServers": {
    "alfresco": {
      "url": "http://localhost:3000/mcp/sse"
    }
  }
}
```

### Option B: Streamable HTTP Transport

For the direct JSON-RPC endpoint:

```json
{
  "mcpServers": {
    "alfresco": {
      "url": "http://localhost:3000/mcp",
      "transport": "http"
    }
  }
}
```

### How Claude Discovers Tools

1. Claude Code connects to the MCP server
2. Sends `initialize` to handshake
3. Sends `tools/list` to discover available tools
4. Claude sees tools with descriptions and input schemas
5. When the user asks about documents, Claude calls the appropriate tool

### Example Prompts for Claude

Once connected, try:

- "Search Alfresco for budget reports"
- "Show me what's in the shared folder"
- "Get the content of document [nodeId]"
- "Upload these meeting notes to my home folder"
- "What metadata does document [nodeId] have?"

---

## Docker

### Build & Run

```bash
docker build -t alfresco-mcp-server .
docker run -p 3000:3000 \
  -e ALFRESCO_BASE_URL=http://host.docker.internal:8080/alfresco \
  -e ALFRESCO_AUTH_USERNAME=admin \
  -e ALFRESCO_AUTH_PASSWORD=admin \
  alfresco-mcp-server
```

### With Docker Compose

```bash
docker-compose up --build
```

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `alfresco.base-url` | `http://localhost:8080/alfresco` | Alfresco base URL |
| `alfresco.auth.type` | `basic` | Auth type: `basic` or `oauth2` |
| `alfresco.auth.username` | `admin` | Basic auth username |
| `alfresco.auth.password` | `admin` | Basic auth password |
| `alfresco.client.connect-timeout-ms` | `5000` | Connection timeout |
| `alfresco.client.read-timeout-ms` | `30000` | Read timeout |
| `alfresco.client.max-in-memory-size-mb` | `16` | Max response buffer |
| `server.port` | `3000` | MCP server port |

All properties can be overridden via environment variables:
`ALFRESCO_BASE_URL`, `ALFRESCO_AUTH_USERNAME`, `ALFRESCO_AUTH_PASSWORD`, etc.

---

## Extending

### Adding a New Tool

1. Create a class implementing `McpTool` in `service/tools/`
2. Annotate with `@Component`
3. Implement `getName()`, `getDefinition()`, `execute()`
4. It's auto-registered — restart and it appears in `tools/list`

### Switching to OIDC Auth

1. Set `alfresco.auth.type=oauth2` in config
2. Implement `OAuth2AuthProvider.java` with your IdP details
3. The `WebClientConfig` already uses the `AuthProvider` abstraction

### Adding RAG / Semantic Search

1. Implement the `RagExtension` interface
2. Wire a vector DB (Qdrant, Pinecone, Weaviate)
3. Create a `SemanticSearchTool` that uses the extension
4. Index documents on upload via an event hook

---

## License

MIT
