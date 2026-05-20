package com.example.alfresco.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Type-safe configuration for the RAG (Retrieval-Augmented Generation) pipeline.
 * Bound from the "rag" prefix in application.yml.
 *
 * The whole RAG subsystem is gated on {@link #enabled}; individual beans
 * additionally switch on {@link Embedding#provider} / {@link VectorStore#type}.
 */
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private boolean enabled = true;

    private Chunking chunking = new Chunking();
    private Embedding embedding = new Embedding();
    private VectorStore vectorStore = new VectorStore();
    private Search search = new Search();
    private Reranking reranking = new Reranking();
    private QueryExpansion queryExpansion = new QueryExpansion();

    // ── Getters / Setters ───────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Chunking getChunking() { return chunking; }
    public void setChunking(Chunking chunking) { this.chunking = chunking; }

    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }

    public VectorStore getVectorStore() { return vectorStore; }
    public void setVectorStore(VectorStore vectorStore) { this.vectorStore = vectorStore; }

    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }

    public Reranking getReranking() { return reranking; }
    public void setReranking(Reranking reranking) { this.reranking = reranking; }

    public QueryExpansion getQueryExpansion() { return queryExpansion; }
    public void setQueryExpansion(QueryExpansion queryExpansion) { this.queryExpansion = queryExpansion; }

    // ── Nested config classes ───────────────────────────────────────

    /** How raw document text is split into embeddable segments. */
    public static class Chunking {
        private int chunkSize = 1000;
        private int chunkOverlap = 200;
        private int minChunkSize = 50;
        private String strategy = "sliding_window";

        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public int getMinChunkSize() { return minChunkSize; }
        public void setMinChunkSize(int minChunkSize) { this.minChunkSize = minChunkSize; }
        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
    }

    /** Which embedding backend to use and its connection details. */
    public static class Embedding {
        private String provider = "local";
        private String apiBaseUrl = "https://api.openai.com/v1";
        private String ollamaBaseUrl = "http://localhost:11434";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        private int dimensions = 1536;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
        public String getOllamaBaseUrl() { return ollamaBaseUrl; }
        public void setOllamaBaseUrl(String ollamaBaseUrl) { this.ollamaBaseUrl = ollamaBaseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    }

    /** Vector index backing store. */
    public static class VectorStore {
        /** "memory" (in-process) or "qdrant" (external Qdrant DB). */
        private String type = "memory";
        /** Hard cap on stored vectors (memory store only). */
        private int maxVectors = 100_000;
        /** Qdrant REST API base URL (type=qdrant). */
        private String qdrantUrl = "http://localhost:6333";
        /** Qdrant API key (optional, for Qdrant Cloud). */
        private String qdrantApiKey = "";
        /** Qdrant collection name. */
        private String qdrantCollection = "alfresco_chunks";
        /** Timeout in milliseconds for Qdrant REST calls. */
        private int qdrantTimeoutMs = 10000;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getMaxVectors() { return maxVectors; }
        public void setMaxVectors(int maxVectors) { this.maxVectors = maxVectors; }
        public String getQdrantUrl() { return qdrantUrl; }
        public void setQdrantUrl(String qdrantUrl) { this.qdrantUrl = qdrantUrl; }
        public String getQdrantApiKey() { return qdrantApiKey; }
        public void setQdrantApiKey(String qdrantApiKey) { this.qdrantApiKey = qdrantApiKey; }
        public String getQdrantCollection() { return qdrantCollection; }
        public void setQdrantCollection(String qdrantCollection) { this.qdrantCollection = qdrantCollection; }
        public int getQdrantTimeoutMs() { return qdrantTimeoutMs; }
        public void setQdrantTimeoutMs(int qdrantTimeoutMs) { this.qdrantTimeoutMs = qdrantTimeoutMs; }
    }

    /**
     * Hybrid search: combines dense vector (semantic) with BM25 keyword
     * retrieval using Reciprocal Rank Fusion.
     */
    public static class Search {
        /** Master switch for hybrid retrieval. When false, only dense vector search runs. */
        private boolean hybridEnabled = true;
        /** Number of candidates to retrieve from the dense vector index before fusion. */
        private int semanticTopK = 20;
        /** Number of candidates to retrieve from the BM25 keyword index before fusion. */
        private int lexicalTopK = 20;
        /** RRF constant (k). Higher values reduce the impact of high-rank positions. Standard: 60. */
        private int rrfK = 60;

        public boolean isHybridEnabled() { return hybridEnabled; }
        public void setHybridEnabled(boolean hybridEnabled) { this.hybridEnabled = hybridEnabled; }
        public int getSemanticTopK() { return semanticTopK; }
        public void setSemanticTopK(int semanticTopK) { this.semanticTopK = semanticTopK; }
        public int getLexicalTopK() { return lexicalTopK; }
        public void setLexicalTopK(int lexicalTopK) { this.lexicalTopK = lexicalTopK; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
    }

    /**
     * Cross-encoder reranking: re-scores the top-N hybrid results using a
     * more expensive but more accurate model (Cohere, Ollama, or no-op).
     */
    public static class Reranking {
        /** Master switch. When false, hybrid results pass through unmodified. */
        private boolean enabled = true;
        /** "ollama" or "none". */
        private String provider = "ollama";
        /** Model name for the reranker (Ollama model or API model). */
        private String model = "bge-reranker-v2-m3";
        /** Ollama base URL used when provider=ollama. */
        private String ollamaBaseUrl = "http://localhost:11434";
        /** Number of candidates to feed into the reranker. */
        private int topKBeforeRerank = 20;
        /** Timeout in milliseconds for remote rerank calls. */
        private int timeoutMs = 30000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getOllamaBaseUrl() { return ollamaBaseUrl; }
        public void setOllamaBaseUrl(String ollamaBaseUrl) { this.ollamaBaseUrl = ollamaBaseUrl; }
        public int getTopKBeforeRerank() { return topKBeforeRerank; }
        public void setTopKBeforeRerank(int topKBeforeRerank) { this.topKBeforeRerank = topKBeforeRerank; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    /**
     * Query expansion: generates alternative representations of the user's
     * query to improve recall (HyDE or multi-query rewrite).
     */
    public static class QueryExpansion {
        /** Master switch. When false, the original query is used as-is. */
        private boolean enabled = false;
        /** Number of expanded query variants to generate (including the original). */
        private int variants = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getVariants() { return variants; }
        public void setVariants(int variants) { this.variants = variants; }
    }
}
