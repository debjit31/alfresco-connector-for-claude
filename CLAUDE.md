# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build (skip tests — there are none yet)
mvn clean package -DskipTests

# Run from jar
java -jar target/alfresco-mcp-server-1.0.0.jar

# Run via Maven (hot for dev)
mvn spring-boot:run

# Health check (server on :3000)
curl http://localhost:3000/mcp

# Swagger UI
open http://localhost:3000/swagger-ui.html
```

There is no test suite. `spring-boot-starter-test` is on the classpath but `src/test`
does not exist; `mvn test` is a no-op. Manual verification is done via curl against
`POST /mcp` (see README's "Testing with curl") or the Swagger "Tools REST API" group.

Java 17 and Maven 3.8+ are required. Most operations require a reachable Alfresco
instance (default `http://localhost:8080/alfresco`, basic auth `admin/admin`); without
it the server still starts and `tools/list`/`initialize` work, but any `tools/call`
that hits Alfresco will fail.

## Architecture

This is a stateless bridge: MCP JSON-RPC in, Alfresco REST out. Requests flow through
four layers, each with a single responsibility:

```
Transport controller → McpDispatcher → McpTool → AlfrescoDocumentService → AlfrescoRestClient → Alfresco
```

- **Transport controllers** (`controller/`) only marshal HTTP ↔ `McpRequest`/`McpResponse`
  and delegate to the dispatcher. Three coexist over the same dispatcher:
  - `McpController` — `POST /mcp`, plain JSON-RPC (and `GET /mcp` info/health).
  - `McpSseController` — `GET /mcp/sse` opens a session, emits an `endpoint` event,
    then client POSTs to `/mcp/messages?sessionId=...` and the JSON-RPC reply is
    streamed back over the SSE channel (returns `202` on the POST). Sessions are an
    in-memory `ConcurrentHashMap<String, SseEmitter>` — **state lives only in this
    one instance; the server is not horizontally scalable as written.**
  - `McpToolsRestController` — typed `POST /api/tools/*` endpoints that exist purely
    so each tool gets a clean Swagger form; they wrap the same tools.

- **`McpDispatcher`** (`mcp/`) is the protocol core. It routes the four JSON-RPC
  methods (`initialize`, `tools/list`, `tools/call`, `ping`). On startup it injects
  `List<McpTool>` (all Spring beans implementing the interface) and builds a name→tool
  registry in `@PostConstruct`. **Tool registration is fully automatic via component
  scanning — there is no central list to edit.**

- **`McpTool`** (`service/tools/`) implementations are the unit of extension. Each
  declares `getName()`, `getDefinition()` (name + description + JSON Schema, used by
  Claude to decide when/how to call it), and an async `execute(JsonNode)`. They parse
  and clamp arguments (e.g. `SearchDocumentsTool` caps `maxItems` at 100), then
  delegate to `AlfrescoDocumentService`. To add a tool: implement `McpTool`, annotate
  `@Component` — it auto-appears in `tools/list` after restart.

- **`AlfrescoDocumentService`** (`service/alfresco/`) holds the business logic and,
  importantly, **normalizes raw Alfresco JSON into clean structures** for tool output
  (e.g. `normalizeSearchResults`, `normalizeNodeInfo`). It also contains domain rules
  like: a plain keyword query is auto-wrapped into an AFTS `cm:name/cm:content/cm:title`
  OR query, and document content is truncated to 50KB to protect Claude's context.

- **`AlfrescoRestClient`** (`client/`) is the only thing that talks HTTP to Alfresco.
  Reactive `WebClient`, every method returns `CompletableFuture<JsonNode>` (or
  `String`/`byte[]` for content). All errors are mapped to `AlfrescoApiException`
  carrying the upstream status and body.

Everything is `CompletableFuture`-based end to end; controllers return the future and
Spring MVC handles async completion. Do not block these chains.

### Cross-cutting pieces

- **Async + error model**: tool failures should return `ToolResult.error(...)` (a
  normal result the model can read), not thrown exceptions. Thrown exceptions are
  caught by the dispatcher and turned into JSON-RPC errors. `GlobalExceptionHandler`
  is the last-resort net for the REST layer.
- **Config**: `application.yml` → `@ConfigurationProperties` beans `AlfrescoProperties`
  (base URL, the two API path prefixes `rest-api-path`/`search-api-path`, auth, client
  timeouts, upload limit) and `McpServerProperties` (name/version reported in
  `initialize`). All overridable via env vars (`ALFRESCO_BASE_URL`,
  `ALFRESCO_AUTH_USERNAME`, `ALFRESCO_AUTH_PASSWORD`, etc.).
- **Auth**: `WebClientConfig` currently hardcodes a Basic-auth header from
  `alfresco.auth`. The `AuthProvider` / `BasicAuthProvider` / `OAuth2AuthProvider`
  abstraction exists for an OIDC swap but `WebClientConfig` does not yet route through
  it — wiring `AuthProvider` into the WebClient is the intended path for OAuth2.
- **RAG**: `RagExtension` is an unimplemented interface (extension point only). No
  bean implements it and no semantic-search tool exists yet.

### MCP protocol notes

- Protocol version returned by `initialize` is hardcoded to `2024-11-05`.
- The `initialize` response includes an `instructions` string that steers Claude's
  tool ordering ("use search_documents first, then get_document/get_metadata") — keep
  that and the per-tool descriptions in sync when changing tool behavior, since the
  model relies on them for tool selection.
- Five tools: `search_documents`, `get_document`, `upload_document`, `get_metadata`,
  `list_folder`.