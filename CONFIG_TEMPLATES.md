# RAG Pipeline Configuration Templates

Copy the appropriate section to your `application.yml` based on your deployment phase.

---

## Phase 0: Development (Current Default)

**When to use**: Local development, quick iteration, no infrastructure requirements

```yaml
spring:
  application:
    name: alfresco-mcp-server
  jackson:
    serialization:
      write-dates-as-timestamps: false
    default-property-inclusion: non_null

server:
  port: 3000

# ── RAG / semantic search ──────────────────────────────────────────
rag:
  enabled: true
  
  chunking:
    chunk-size: 1000
    chunk-overlap: 200
    min-chunk-size: 50
    strategy: sliding_window      # sliding_window | paragraph
  
  embedding:
    provider: local               # local | openai | ollama
    ollama-base-url: http://localhost:11434
    model: nomic-embed-text
    dimensions: 768
  
  vector-store:
    type: memory                  # Single instance only
    max-vectors: 100000

logging:
  level:
    com.example.alfresco.mcp: DEBUG
```

**Pros**: 
- Zero infrastructure
- Instant feedback loop
- No API costs
- Dependencies: only Docker (optional, for Ollama)

**Cons**:
- Max 100K documents
- Data lost on restart
- Limited to single instance

---

## Phase 1: Production Ready (PostgreSQL + OpenAI)
### ⭐ RECOMMENDED IMMEDIATE NEXT STEP

**When to use**: Scaling to 10M+ docs, need durability, moderate latency requirements

```yaml
spring:
  application:
    name: alfresco-mcp-server
  
  datasource:
    url: jdbc:postgresql://localhost:5432/alfresco_rag
    username: postgres
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
      idle-timeout: 60000
      max-lifetime: 600000
  
  jpa:
    hibernate:
      ddl-auto: validate         # Change to 'update' for first run only
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc:
          batch_size: 20
          fetch_size: 100
        order_inserts: true
        order_updates: true
  
  jackson:
    serialization:
      write-dates-as-timestamps: false
    default-property-inclusion: non_null

server:
  port: 3000
  servlet:
    context-path: /

# ── MCP server metadata ────────────────────────────────────────────
mcp:
  server:
    name: alfresco-mcp-server
    version: 1.0.0

# ── Alfresco connection ────────────────────────────────────────────
alfresco:
  base-url: http://localhost:8080/alfresco
  rest-api-path: /api/-default-/public/alfresco/versions/1
  search-api-path: /api/-default-/public/search/versions/1
  auth:
    type: basic
    username: admin
    password: ${ALFRESCO_PASSWORD:admin}
  client:
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
    max-in-memory-size-mb: 16
  upload:
    max-file-size-mb: 100

# ── RAG / semantic search ──────────────────────────────────────────
rag:
  enabled: true
  
  # OPTIMIZED for semantic search
  chunking:
    chunk-size: 512               # Smaller for better context
    chunk-overlap: 128            # 25% overlap
    min-chunk-size: 100
    strategy: sliding_window
  
  embedding:
    provider: openai              # Use OpenAI's best model
    api-base-url: https://api.openai.com/v1
    api-key: ${OPENAI_API_KEY}
    model: text-embedding-3-large # State-of-the-art
    dimensions: 3072              # Higher quality embeddings
  
  vector-store:
    type: postgres                # NEW: Persistent storage
    table-name: document_chunks
    embeddings-column: embedding
    max-vectors: 10000000         # 10M capacity

# ── Logging ────────────────────────────────────────────────────────
logging:
  level:
    com.example.alfresco.mcp: INFO
    org.springframework.web.reactive.function.client: INFO
    org.hibernate.SQL: WARN
    org.hibernate.type.descriptor.sql.BasicBinder: WARN
  pattern:
    console: "%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n"

# ── Actuator ───────────────────────────────────────────────────────
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: when-authorized

# ── SpringDoc / Swagger UI ─────────────────────────────────────────
springdoc:
  api-docs:
    path: /v3/api-docs
    enabled: true
  swagger-ui:
    path: /swagger-ui.html
    enabled: true
    tags-sorter: alpha
```

**Pros**:
- Scales to 10M+ documents
- Persistent storage (survives restart)
- 70% latency reduction vs. in-memory
- 20% relevance improvement
- Standard SQL database (familiar operations)

**Cons**:
- Need to manage PostgreSQL
- Embedding API costs (~$100/1M queries)
- Moderate operational complexity

**Infrastructure Requirements**:
- PostgreSQL 13+ with pgvector extension
- OpenAI API key
- 2-4 GB RAM, 50+ GB disk (for 10M docs)

**Setup**:
```bash
# 1. PostgreSQL setup
brew install postgresql
brew services start postgresql
createdb alfresco_rag
psql alfresco_rag -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql alfresco_rag -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"

# 2. Set environment variables
export DB_PASSWORD=your_secure_password
export OPENAI_API_KEY=sk-...
export ALFRESCO_PASSWORD=admin_password
```

---

## Phase 2: Query Intelligence (Add Query Optimization)

**When to use**: Phase 1 is working well, want 15% more relevance

```yaml
# All Phase 1 config, plus:

rag:
  enabled: true
  
  # === NEW: Query Optimization ===
  query-optimization:
    enabled: true
    expansion-enabled: true       # Synonym expansion
    reformulation-enabled: true   # Query variants
    intent-detection-enabled: true
  
  chunking:
    chunk-size: 512
    chunk-overlap: 128
    min-chunk-size: 100
    strategy: sliding_window
  
  embedding:
    provider: openai
    api-base-url: https://api.openai.com/v1
    api-key: ${OPENAI_API_KEY}
    model: text-embedding-3-large
    dimensions: 3072
  
  vector-store:
    type: postgres
    table-name: document_chunks
    embeddings-column: embedding
    max-vectors: 10000000
  
  # === CACHING (optional but recommended) ===
  cache:
    enabled: true
    ttl-minutes: 60
    max-entries: 10000

# === NEW: Redis for caching (optional) ===
# Uncomment to enable distributed caching
# spring:
#   redis:
#     host: localhost
#     port: 6379
#     timeout: 2000ms
#     jedis:
#       pool:
#         max-active: 10
#         max-idle: 5
```

**New Dependencies**:
```xml
<!-- Spring Cache -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>

<!-- Optional: Redis for distributed caching -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

## Phase 3: Hybrid + Reranking (Best-in-Market)

**When to use**: Aiming for top-tier relevance, can tolerate 300-400ms latency

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/alfresco_rag
    username: postgres
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 30
  
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  
  cache:
    type: redis
  
  redis:
    host: localhost
    port: 6379
    timeout: 2000ms

server:
  port: 3000

alfresco:
  base-url: ${ALFRESCO_BASE_URL:http://localhost:8080/alfresco}
  auth:
    type: basic
    username: ${ALFRESCO_USERNAME:admin}
    password: ${ALFRESCO_PASSWORD:admin}

# ── RAG / semantic search (FULL FEATURED) ────────────────────────
rag:
  enabled: true
  
  chunking:
    chunk-size: 512
    chunk-overlap: 128
    min-chunk-size: 100
    strategy: sliding_window
  
  embedding:
    provider: openai
    api-base-url: https://api.openai.com/v1
    api-key: ${OPENAI_API_KEY}
    model: text-embedding-3-large
    dimensions: 3072
  
  vector-store:
    type: postgres
    # OR uncomment for Qdrant (distributed):
    # type: qdrant
    # url: http://localhost:6333
    # api-key: ${QDRANT_API_KEY}
    table-name: document_chunks
    embeddings-column: embedding
    max-vectors: 10000000
  
  # === HYBRID SEARCH ===
  search:
    hybrid-enabled: true
    
    # Semantic search configuration
    semantic:
      enabled: true
      top-k: 20
    
    # Full-text/BM25 search configuration
    lexical:
      enabled: true
      top-k: 20
      provider: postgresql  # OR: elasticsearch
    
    # Result fusion strategy
    fusion:
      enabled: true
      strategy: rrf        # Reciprocal Rank Fusion
      rrf-k: 60            # Standard RRF parameter
    
    # === RERANKING ===
    reranking:
      enabled: true
      model: bge-reranker-large
      provider: huggingface  # OR: ollama | openai
      
      # For HuggingFace Inference API
      api-url: https://api-inference.huggingface.co/models/BAAI/bge-reranker-large
      api-key: ${HF_API_KEY}
      
      # Local Ollama alternative
      # api-url: http://localhost:11434/api/embeddings
      
      top-k-before-rerank: 30  # Rerank top 30
      batch-size: 10
      timeout-ms: 5000
    
    # Query optimization
    query-optimization:
      enabled: true
      expansion-enabled: true
      reformulation-enabled: true
      intent-detection-enabled: true
    
    # Caching
    cache:
      enabled: true
      ttl-minutes: 120
      max-entries: 50000

logging:
  level:
    com.example.alfresco.mcp: INFO
    org.springframework: WARN
    org.hibernate: WARN
  pattern:
    console: "%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n"
    file: "%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n"
  file:
    name: logs/application.log
    max-size: 100MB
    max-history: 30

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true

springdoc:
  swagger-ui:
    enabled: true
    path: /swagger-ui.html
```

**New Dependencies**:
```xml
<!-- Reranking support -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-huggingface</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Elasticsearch for lexical search (optional) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
    <version>3.2.5</version>
</dependency>

<!-- Prometheus metrics -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**Infrastructure Check**:
```bash
# PostgreSQL + pgvector
psql -U postgres -d alfresco_rag -c "SELECT version();"

# Redis
redis-cli ping

# Check Ollama (if using local reranker)
curl http://localhost:11434/api/tags

# OR HuggingFace API (if using cloud reranker)
curl -H "Authorization: Bearer $HF_API_KEY" \
  https://api-inference.huggingface.co/models/BAAI/bge-reranker-large
```

---

## Phase 4: Enterprise Scale (Distributed Qdrant + High Availability)

**When to use**: 50M+ documents, multi-node deployment, 99.9% uptime requirement

```yaml
spring:
  datasource:
    # Keep PostgreSQL for metadata and sync state only
    url: jdbc:postgresql://postgres.production:5432/alfresco_rag
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 50
  
  cache:
    type: redis
  redis:
    sentinel:
      master: alfresco-redis-master
      nodes: redis-node1:26379,redis-node2:26379,redis-node3:26379
    password: ${REDIS_PASSWORD}

server:
  port: 3000
  shutdown: graceful
  servlet:
    context-path: /

alfresco:
  base-url: ${ALFRESCO_CLUSTER_URL}
  auth:
    type: oauth2  # Upgraded from basic auth

# ── RAG / semantic search (ENTERPRISE) ──────────────────────────
rag:
  enabled: true
  
  chunking:
    chunk-size: 512
    chunk-overlap: 128
    strategy: sliding_window
  
  embedding:
    provider: voyageai           # Better price/quality
    api-key: ${VOYAGE_API_KEY}
    model: voyage-3
    dimensions: 1024
    cache-embeddings: true       # Cache computed embeddings
  
  vector-store:
    type: qdrant                 # Distributed vector DB
    cluster:
      enabled: true
      urls:
        - https://qdrant-node1:6333
        - https://qdrant-node2:6333
        - https://qdrant-node3:6333
      api-key: ${QDRANT_API_KEY}
      timeout-ms: 10000
    collection: alfresco-documents
    replication-factor: 3
    shard-number: 12
    max-vectors: 100000000       # 100M capacity
  
  search:
    hybrid-enabled: true
    
    semantic:
      enabled: true
      top-k: 25
    
    lexical:
      enabled: true
      provider: elasticsearch
      elasticsearch:
        urls:
          - https://es-node1:9200
          - https://es-node2:9200
          - https://es-node3:9200
        username: ${ES_USER}
        password: ${ES_PASSWORD}
        index: alfresco-documents
        top-k: 25
    
    fusion:
      enabled: true
      strategy: rrf
      rrf-k: 60
    
    reranking:
      enabled: true
      model: bge-reranker-large
      provider: ollama
      replicas:
        - http://reranker-1:8000
        - http://reranker-2:8000
        - http://reranker-3:8000
      load-balancer: round-robin
      timeout-ms: 10000
      top-k-before-rerank: 50
      batch-size: 32
    
    query-optimization:
      enabled: true
      expansion-enabled: true
      reformulation-enabled: true
      intent-detection-enabled: true
    
    cache:
      enabled: true
      ttl-minutes: 240
      max-entries: 100000
      distributed: true  # Shared across instances

logging:
  level:
    ROOT: WARN
    com.example.alfresco.mcp: INFO
  pattern:
    console: "%d{ISO8601} %X{traceId} [%thread] %-5level %logger{36} - %msg%n"
  file:
    name: /var/log/alfresco-mcp/application.log
    max-size: 500MB
    max-history: 90
    total-size-cap: 50GB

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,threaddump,heapdump
  metrics:
    export:
      prometheus:
        enabled: true
        step: 1m
  tracing:
    sampling:
      probability: 0.1  # Sample 10% of traces
    exporter:
      otlp:
        enabled: true
        endpoint: http://jaeger:4317

springdoc:
  swagger-ui:
    enabled: false  # Disable in production
```

**Monitoring Setup**:
```yaml
# Prometheus scrape config (prometheus.yml)
scrape_configs:
  - job_name: 'alfresco-mcp'
    static_configs:
      - targets: ['localhost:3000']
    metrics_path: '/actuator/prometheus'

# Key metrics to monitor:
# - alfresco_mcp_rag_search_latency_ms (histogram)
# - alfresco_mcp_rag_relevance_score (gauge)
# - alfresco_mcp_rag_index_size_chunks (counter)
# - alfresco_mcp_rag_cache_hit_ratio (counter)
```

---

## Environment Variable Reference

### Phase 1 Minimum:
```bash
export OPENAI_API_KEY="sk-..."
export DB_PASSWORD="secure_postgres_pw"
export ALFRESCO_PASSWORD="admin_pw"
```

### Phase 3 Full:
```bash
export OPENAI_API_KEY="sk-..."
export HF_API_KEY="hf_..."
export DB_PASSWORD="secure_postgres_pw"
export REDIS_PASSWORD="redis_pw"
export ALFRESCO_PASSWORD="admin_pw"
export QDRANT_API_KEY="qdrant_key"  # If using Qdrant
```

### Phase 4 Enterprise:
```bash
export VOYAGE_API_KEY="..."
export DB_USER="alfresco"
export DB_PASSWORD="..."
export REDIS_PASSWORD="..."
export ALFRESCO_CLUSTER_URL="https://alfresco-lb.production"
export QDRANT_API_KEY="..."
export ES_USER="elastic"
export ES_PASSWORD="..."
```

---

## Validation Checklist

### After updating `application.yml`:

- [ ] Syntax is valid YAML
- [ ] All `${ENV_VAR}` references have defaults or env vars set
- [ ] Database connection URL is correct
- [ ] API keys are not hardcoded (use env vars)
- [ ] Logging levels are appropriate (DEBUG for dev, INFO for prod)
- [ ] Port 3000 is available (or change `server.port`)
- [ ] Test with: `mvn spring-boot:run`

### Sample Test Commands:

```bash
# Quick health check
curl http://localhost:3000/mcp

# List available tools
curl -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'

# Semantic search
curl -X POST http://localhost:3000/api/tools/semantic_search \
  -H "Content-Type: application/json" \
  -d '{"query":"your search term","topK":10}'
```

---

**Last Updated**: May 2026

