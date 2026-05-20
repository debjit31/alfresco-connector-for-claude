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

# Docker (multi-stage build; image exposes :3000)
docker compose up --build        # alfresco-mcp only; Alfresco itself is commented out in docker-compose.yml
docker build -t alfresco-mcp-server . && docker run -p 3000:3000 alfresco-mcp-server
```

In the container, Alfresco is reached at `host.docker.internal:8080` by default
(see Dockerfile `ENV`); the compose file instead points at an `alfresco` service
you must uncomment or supply yourself.

There is no test suite. `spring-boot-starter-test` is on the classpath but `src/test`
does not exist; `mvn test` is a no-op. Manual verification is done via curl against
`POST /mcp` (see README's "Testing with curl") or the Swagger "Tools REST API" /
"Tools — RAG" groups.

Java 17 and Maven 3.8+ are required. Most operations require a reachable Alfresco
instance (default `http://localhost:8080/alfresco`, basic auth `admin/admin`); without
it the server still starts and `tools/list`/`initialize` work, but any `tools/call`
that hits Alfresco will fail. The RAG tools also need their configured embedding
backend reachable when `rag.embedding.provider` is `openai` or `ollama` — `local` has
no external dependency.

## Architecture

This is a stateless bridge: MCP JSON-RPC in, Alfresco REST out, with an optional
in-process RAG sidecar for semantic search. Core requests flow through four layers,
each with a single responsibility:

```
Transport controller → McpDispatcher → McpTool → AlfrescoDocumentService → AlfrescoRestClient → Alfresco
                                            ↘ (RAG tools) → RagService → {Chunker, EmbeddingProvider, VectorStore}
```

- **Transport controllers** (`controller/`) only marshal HTTP ↔ `McpRequest`/`McpResponse`
  and delegate to the dispatcher. Four coexist:
  - `McpController` — `POST /mcp`, plain JSON-RPC (and `GET /mcp` info/health).
  - `McpSseController` — `GET /mcp/sse` opens a session, emits an `endpoint` event,
    then client POSTs to `/mcp/messages?sessionId=...` and the JSON-RPC reply is
    streamed back over the SSE channel (returns `202` on the POST). Sessions are an
    in-memory `ConcurrentHashMap<String, SseEmitter>`.
  - `McpToolsRestController` — typed `POST /api/tools/*` endpoints that exist purely
    so each non-RAG tool gets a clean Swagger form; they wrap the same tools.
  - `RagToolsRestController` — same pattern for the RAG tools (`index_document`,
    `semantic_search`, `index_status`). `@ConditionalOnBean(RagService.class)`, so
    it disappears entirely when RAG is off.

- **`McpDispatcher`** (`mcp/`) is the protocol core. It routes the four JSON-RPC
  methods (`initialize`, `tools/list`, `tools/call`, `ping`). On startup it injects
  `List<McpTool>` (all Spring beans implementing the interface) and builds a name→tool
  registry in `@PostConstruct`. **Tool registration is fully automatic via component
  scanning — there is no central list to edit.** The `initialize` response's
  `instructions` string is hand-written and currently only mentions the five
  Alfresco tools; update it when adding new tool families.

- **`McpTool`** (`service/tools/`) implementations are the unit of extension. Each
  declares `getName()`, `getDefinition()` (name + description + JSON Schema, used by
  Claude to decide when/how to call it), and an async `execute(JsonNode)`. They parse
  and clamp arguments (e.g. `SearchDocumentsTool` caps `maxItems` at 100), then
  delegate to a service. To add a tool: implement `McpTool`, annotate `@Component` —
  it auto-appears in `tools/list` after restart. RAG tools additionally carry
  `@ConditionalOnBean(RagService.class)`.

- **`AlfrescoDocumentService`** (`service/alfresco/`) holds the business logic and,
  importantly, **normalizes raw Alfresco JSON into clean structures** for tool output
  (e.g. `normalizeSearchResults`, `normalizeNodeInfo`). Domain rules live here:
  - A plain keyword query is auto-wrapped into an AFTS `cm:name/cm:content/cm:title`
    OR query.
  - `getDocument(..., includeContent=true)` is **MIME-aware**: it fetches metadata
    first and, if the content type is non-text, downloads raw bytes and runs them
    through `TextExtractor` (Tika); only `text/*` is read as a UTF-8 string. The
    result is then truncated to 50KB to protect Claude's context.

- **`AlfrescoRestClient`** (`client/`) is the only thing that talks HTTP to Alfresco.
  Reactive `WebClient`, every method returns `CompletableFuture<JsonNode>` (or
  `String`/`byte[]` for content). All errors are mapped to `AlfrescoApiException`
  carrying the upstream status and body. **Integration is purely the Alfresco REST
  API.** `chemistry-opencmis-client-impl` is declared in `pom.xml` but is unused
  anywhere in `src/` — do not assume a CMIS path exists; ignore or remove it.

Everything is `CompletableFuture`-based end to end; controllers return the future and
Spring MVC handles async completion. Do not block these chains.

### RAG subsystem (`service/rag/`)

Optional, in-process retrieval-augmented search implementing a **7-stage pipeline**
matching the architecture diagram (`best_rag_pipeline_architecture.svg`):

```
INGESTION:  Extract (Tika) → Chunk → Embed → Dual Index (Vector + BM25)
QUERY:      Expand → Hybrid Retrieve (Dense + BM25 + RRF) → Rerank → Return
```

Gated at three levels so the rest of the app keeps working when it's off:

- `rag.enabled=true` (default, `matchIfMissing=true`) → `RagService` bean exists.
- Each RAG tool / REST controller is `@ConditionalOnBean(RagService.class)`.
- Each provider/store implementation is `@ConditionalOnProperty` on its key.

`RagService` orchestrates the full pipeline. Three pluggable seams for ingestion,
plus three new pluggable seams for retrieval:

**Ingestion stages:**

- **`DocumentChunker`** — two strategies via `rag.chunking.strategy`:
  `sliding_window` (fixed size with overlap, snapped to a sentence boundary in the
  last 20%) and `paragraph` (split on blank lines, greedy merge up to chunk size).
  Every chunk gets a `[Document: NAME | Section: HEADING]\n` prefix from a
  best-effort heading detector — that contextual prefix is what actually gets
  embedded.

- **`EmbeddingProvider`** — selected by `rag.embedding.provider`:
  - `local` (default) — TF-IDF feature hashing into 768 dims.
  - `openai` — single-request batch against `/embeddings`.
  - `ollama` — native `POST /api/embed` with `search_document:` / `search_query:` prefixes.

- **`VectorStore`** — dense vector index (`InMemoryVectorStore`). Maintains a
  secondary `nodeId → chunkIds` index for O(chunks) deletion.

- **`BM25Index`** — in-process Okapi BM25 keyword index (Stage 4b). Maintains
  inverted index + per-document term frequencies. Indexed in lockstep with the
  vector store during `indexChunks()`. Parameters: k1=1.2, b=0.75.

**Query stages:**

- **`QueryExpander`** (Stage 5) — generates multiple query variants to improve
  recall. Techniques: synonym expansion, sub-query decomposition, HyDE-lite
  (hypothetical answer fragment). Gated on `rag.query-expansion.enabled`.

- **`HybridSearchEngine`** (Stage 6) — runs dense vector search + BM25 search in
  parallel, then fuses results using **Reciprocal Rank Fusion** (RRF). The RRF
  constant k (default 60) controls rank position weighting. Falls back to pure
  vector search when `rag.search.hybrid-enabled=false`.

- **`Reranker`** (Stage 7) — cross-encoder reranking of fused candidates:
  - `none` (default) — `NoOpReranker`, pass-through.
  - `ollama` — `OllamaReranker`, uses Ollama generate to score (query, passage)
    pairs with bge-reranker-v2-m3. Activated with `rag.reranking.provider=ollama`.

**Pipeline degradation:** each stage is independently toggleable. Turning off hybrid
search leaves pure vector cosine. Turning off reranking passes RRF results through.
Turning off query expansion uses the original query only. This means the pipeline
works end-to-end with zero external dependencies (local embeddings + in-memory store
+ NoOp reranker + no expansion).

- **`TextExtractor`** — Apache Tika `AutoDetectParser` wrapped to never throw.
  Used by both `IndexDocumentTool` and `AlfrescoDocumentService.getDocument`.

**Single-instance constraint:** the SSE session map *and* the in-memory vector store
both live only in this JVM. As written the server is not horizontally scalable — RAG
state would diverge between replicas, and SSE sessions are sticky to whichever node
opened them.

### Cross-cutting pieces

- **Async + error model**: tool failures should return `ToolResult.error(...)` (a
  normal result the model can read), not thrown exceptions. Thrown exceptions are
  caught by the dispatcher and turned into JSON-RPC errors. `GlobalExceptionHandler`
  is the last-resort net for the REST layer.
- **Config**: `application.yml` → `@ConfigurationProperties` beans `AlfrescoProperties`
  (base URL, the two API path prefixes `rest-api-path`/`search-api-path`, auth, client
  timeouts, upload limit), `McpServerProperties` (name/version reported in
  `initialize`), and `RagProperties` (chunking / embedding / vector-store, all under
  the `rag` prefix). All overridable via env vars (`ALFRESCO_BASE_URL`,
  `ALFRESCO_AUTH_USERNAME`, `ALFRESCO_AUTH_PASSWORD`, `RAG_EMBEDDING_PROVIDER`, etc.).
- **Auth**: `WebClientConfig` currently hardcodes a Basic-auth header from
  `alfresco.auth`. The `AuthProvider` / `BasicAuthProvider` / `OAuth2AuthProvider`
  abstraction exists for an OIDC swap but `WebClientConfig` does not yet route through
  it — wiring `AuthProvider` into the WebClient is the intended path for OAuth2.

### MCP protocol notes

- Protocol version returned by `initialize` is hardcoded to `2024-11-05`.
- The `initialize` response includes an `instructions` string that steers Claude's
  tool ordering ("use search_documents first, then get_document/get_metadata") — keep
  that and the per-tool descriptions in sync when changing tool behavior, since the
  model relies on them for tool selection. It currently does **not** mention the RAG
  tools; if you rely on RAG, update the instructions so Claude knows to call
  `index_document` before `semantic_search`.
- Tools registered (count depends on `rag.enabled`):
  - Always: `search_documents`, `get_document`, `upload_document`, `get_metadata`,
    `list_folder`.
  - With RAG on: also `index_document`, `semantic_search`, `index_status`.