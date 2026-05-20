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

    // ── Getters / Setters ───────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Chunking getChunking() { return chunking; }
    public void setChunking(Chunking chunking) { this.chunking = chunking; }

    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }

    public VectorStore getVectorStore() { return vectorStore; }
    public void setVectorStore(VectorStore vectorStore) { this.vectorStore = vectorStore; }

    // ── Nested config classes ───────────────────────────────────────

    /** How raw document text is split into embeddable segments. */
    public static class Chunking {
        /** Target chunk size in characters. */
        private int chunkSize = 1000;
        /** Characters of overlap carried between adjacent chunks. */
        private int chunkOverlap = 200;
        /** Chunks shorter than this (after trim) are discarded. */
        private int minChunkSize = 50;
        /** "sliding_window" or "paragraph". */
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
        /** "local" (in-process TF-IDF hashing), "openai", or "ollama". */
        private String provider = "local";
        private String apiBaseUrl = "https://api.openai.com/v1";
        /** Base URL of a local Ollama server (provider=ollama). */
        private String ollamaBaseUrl = "http://localhost:11434";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        /** Dimensionality requested from a remote provider. */
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
        /** Currently only "memory". */
        private String type = "memory";
        /** Hard cap on stored vectors to bound memory use. */
        private int maxVectors = 100_000;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getMaxVectors() { return maxVectors; }
        public void setMaxVectors(int maxVectors) { this.maxVectors = maxVectors; }
    }
}
