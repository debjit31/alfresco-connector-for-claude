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

## Connecting to Local Claude

Your MCP server exposes two transport endpoints that Claude can connect to:

| Transport | Endpoint | Notes |
|-----------|----------|-------|
| **SSE** | `http://localhost:3000/mcp/sse` | Works with Claude Code and Claude Desktop (via bridge) |
| **HTTP (Streamable)** | `http://localhost:3000/mcp` | Recommended by the current MCP spec for Claude Code |

> **Prerequisite:** Make sure the MCP server is already running (`mvn spring-boot:run`)
> before connecting Claude. Verify with `curl http://localhost:3000/mcp`.

---

### Option 1: Claude Code (Terminal CLI) — Direct Connection

Claude Code supports SSE and HTTP transports natively. No bridge or proxy needed.

**Add via the CLI** (run this in a regular terminal, not inside a Claude Code session):

```bash
# SSE transport (uses /mcp/sse)
claude mcp add --transport sse alfresco-ecm http://localhost:3000/mcp/sse

# OR HTTP transport (uses POST /mcp — newer spec-recommended option)
claude mcp add --transport http alfresco-ecm http://localhost:3000/mcp
```

Pick one of the two; both work. Then verify the registration:

```bash
claude mcp list
```

You should see `alfresco-ecm` listed. Now launch Claude Code:

```bash
claude
```

Inside the session, type `/mcp` to confirm the server shows as **connected** and all 5
tools are discovered. Then just talk naturally — Claude calls the right tools automatically.

**Scope options** — the default scope is `local` (current project only). For broader access:

```bash
# Available across ALL your projects (user-level)
claude mcp add --transport sse --scope user alfresco-ecm http://localhost:3000/mcp/sse

# Shared with your team via .mcp.json committed to git (project-level)
claude mcp add --transport sse --scope project alfresco-ecm http://localhost:3000/mcp/sse
```

**Alternative: edit the config JSON directly** instead of using the CLI wizard.

Add to `~/.claude.json`:

```json
{
  "mcpServers": {
    "alfresco-ecm": {
      "type": "sse",
      "url": "http://localhost:3000/mcp/sse"
    }
  }
}
```

Or for HTTP transport:

```json
{
  "mcpServers": {
    "alfresco-ecm": {
      "type": "http",
      "url": "http://localhost:3000/mcp"
    }
  }
}
```

**Removing the server later:**

```bash
claude mcp remove alfresco-ecm
```

---

### Option 2: Claude Desktop (GUI App) — Via `mcp-remote` Bridge

Claude Desktop only speaks **stdio** to local MCP servers — it cannot call HTTP or SSE
endpoints directly. The [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) npm
package acts as a bridge: Claude Desktop spawns it as a stdio process, and it forwards
all JSON-RPC messages to your server over SSE.

**Prerequisites:** Node.js 18+ installed and `npx` available on your system PATH.

**Step 1 — Open your Claude Desktop config file:**

| OS | Config file path |
|----|------------------|
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |
| Linux | `~/.config/Claude/claude_desktop_config.json` |

You can also open it from inside Claude Desktop: **Settings → Developer → Edit Config**.

**Step 2 — Add this entry** and save:

```json
{
  "mcpServers": {
    "alfresco-ecm": {
      "command": "npx",
      "args": [
        "mcp-remote@latest",
        "http://localhost:3000/mcp/sse"
      ]
    }
  }
}
```

**Step 3 — Restart Claude Desktop completely.**

On macOS: `Cmd+Q` (not just close the window).
On Windows: right-click the system tray icon → Quit, then reopen.

**Step 4 — Verify the connection.**

Open a new chat. You should see a **hammer icon (🔨)** at the bottom of the input box
with a number showing how many tools are available. Click it to confirm the 5 Alfresco
tools appear in the list. Then ask Claude something like "Search Alfresco for recent
documents" — it will call `search_documents` through the bridge.

---

### How Tool Discovery Works

Once connected (either method), the protocol handshake is fully automatic:

```
Claude                          MCP Server                      Alfresco
  │                                │                                │
  │─── initialize ────────────────►│                                │
  │◄── server info + capabilities ─│                                │
  │                                │                                │
  │─── tools/list ────────────────►│                                │
  │◄── 5 tool definitions ────────│                                │
  │    (with JSON schemas)         │                                │
  │                                │                                │
  │  User: "find budget reports"   │                                │
  │                                │                                │
  │─── tools/call ────────────────►│                                │
  │    search_documents            │─── POST /search ──────────────►│
  │    { query: "budget report" }  │◄── search results ────────────│
  │◄── structured result ─────────│                                │
  │                                │                                │
  │  Claude presents results       │                                │
```

You do not need to tell Claude which tools exist — it discovers them automatically
through the MCP handshake and uses their descriptions + input schemas to decide
when and how to call them.

---

### Example Prompts

Once connected, try these in Claude Code or Claude Desktop:

- `Search Alfresco for budget reports`
- `Show me what's in the shared folder`
- `Get the content of document <nodeId> and summarize it`
- `Upload these meeting notes to my home folder`
- `What metadata does document <nodeId> have?`
- `List everything in the repository root`

---

### Troubleshooting

**Claude Code shows the server as "failed":**

1. Confirm the MCP server is running: `curl http://localhost:3000/mcp` should return JSON.
2. Remove and re-add: `claude mcp remove alfresco-ecm` then re-run the `add` command.
3. Check the MCP timeout — if the server is slow to start, set a longer timeout:
   `MCP_TIMEOUT=15000 claude`

**Claude Desktop hammer icon is missing:**

1. Ensure `npx` is on your system PATH. Test manually in a terminal:
   `npx mcp-remote@latest http://localhost:3000/mcp/sse`
   — it should connect without errors.
2. Validate your JSON config syntax (missing commas, mismatched brackets silently
   disable all servers).
3. Check Claude Desktop logs:
   - macOS: `~/Library/Logs/Claude/mcp.log`
   - Windows: `%APPDATA%\Claude\logs\mcp.log`

**Tools appear but tool calls fail with Alfresco errors:**

1. Verify Alfresco is reachable at the configured URL:
   ```bash
   curl -u admin:admin \
     http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1/nodes/-root-
   ```
2. Check credentials in `application.yml` match your Alfresco instance.
3. Review MCP server logs (console output) for the full Alfresco API error message.

**Tools appear but Claude never calls them:**

Claude matches tools by their descriptions. Make sure your prompt relates to document
management. Saying "find files about budgets" triggers `search_documents`; saying
"tell me a joke" does not.

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
