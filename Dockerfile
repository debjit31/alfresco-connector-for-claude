# ═══════════════════════════════════════════════════════════════════
#  Stage 1: Build
# ═══════════════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies first (layer caching)
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# ═══════════════════════════════════════════════════════════════════
#  Stage 2: Runtime
# ═══════════════════════════════════════════════════════════════════
FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S mcp && adduser -S mcp -G mcp
WORKDIR /app

COPY --from=builder /build/target/alfresco-mcp-server-*.jar app.jar

# ── Alfresco connection ─────────────────────────────────────────
ENV ALFRESCO_BASE_URL=http://host.docker.internal:8080/alfresco \
    ALFRESCO_AUTH_USERNAME=admin \
    ALFRESCO_AUTH_PASSWORD=admin \
    SERVER_PORT=3000

# ── RAG pipeline (7-stage) ──────────────────────────────────────
# Stage 3: Embeddings (Ollama mxbai-embed-large, 1024d)
ENV RAG_ENABLED=true \
    RAG_EMBEDDING_PROVIDER=ollama \
    RAG_EMBEDDING_OLLAMA_BASE_URL=http://host.docker.internal:11434 \
    RAG_EMBEDDING_MODEL=mxbai-embed-large \
    RAG_EMBEDDING_DIMENSIONS=1024

# Stage 4: Dual indexing
ENV RAG_VECTOR_STORE_TYPE=qdrant \
    RAG_VECTOR_STORE_MAX_VECTORS=100000 \
    RAG_VECTOR_STORE_QDRANT_URL=http://host.docker.internal:6333 \
    RAG_VECTOR_STORE_QDRANT_API_KEY="" \
    RAG_VECTOR_STORE_QDRANT_COLLECTION=alfresco_chunks \
    RAG_VECTOR_STORE_QDRANT_TIMEOUT_MS=10000

# Stage 5: Query expansion
ENV RAG_QUERY_EXPANSION_ENABLED=true \
    RAG_QUERY_EXPANSION_VARIANTS=3

# Stage 6: Hybrid search (Dense + BM25 + RRF)
ENV RAG_SEARCH_HYBRID_ENABLED=true \
    RAG_SEARCH_SEMANTIC_TOP_K=20 \
    RAG_SEARCH_LEXICAL_TOP_K=20 \
    RAG_SEARCH_RRF_K=60

# Stage 7: Cross-encoder reranking (Ollama bge-reranker-v2-m3)
ENV RAG_RERANKING_PROVIDER=ollama \
    RAG_RERANKING_MODEL=bge-reranker-v2-m3 \
    RAG_RERANKING_OLLAMA_BASE_URL=http://host.docker.internal:11434 \
    RAG_RERANKING_TOP_K_BEFORE_RERANK=20 \
    RAG_RERANKING_TIMEOUT_MS=30000

# Chunking
ENV RAG_CHUNKING_STRATEGY=sliding_window \
    RAG_CHUNKING_CHUNK_SIZE=1000 \
    RAG_CHUNKING_CHUNK_OVERLAP=200

EXPOSE 3000

USER mcp

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:3000/mcp || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
