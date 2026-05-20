package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Embedding provider backed by an OpenAI-compatible {@code /embeddings}
 * endpoint. Activated with {@code rag.embedding.provider=openai}.
 *
 * Uses the native batch endpoint: a single request embeds every input text,
 * so {@link #embedBatch} is one round-trip rather than N.
 */
@Component
@ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "openai")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingProvider.class);

    /** OpenAI rejects very long inputs; truncate defensively. */
    private static final int MAX_INPUT_CHARS = 8000;

    private final RagProperties.Embedding cfg;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAiEmbeddingProvider(RagProperties props, ObjectMapper objectMapper) {
        this.cfg = props.getEmbedding();
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(cfg.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("OpenAI embedding provider active: model={}, dimensions={}, baseUrl={}",
                cfg.getModel(), cfg.getDimensions(), cfg.getApiBaseUrl());
    }

    @Override
    public CompletableFuture<double[]> embed(String text) {
        return embedBatch(List.of(text)).thenApply(list ->
                list.isEmpty() ? new double[cfg.getDimensions()] : list.get(0));
    }

    @Override
    public CompletableFuture<List<double[]>> embedBatch(List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("dimensions", cfg.getDimensions());
        ArrayNode input = body.putArray("input");
        for (String t : texts) {
            input.add(truncate(t));
        }

        log.debug("OpenAI embeddings request: {} input(s), model={}", texts.size(), cfg.getModel());

        return webClient.post()
                .uri("/embeddings")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::parseEmbeddings)
                .doOnError(e -> log.error("OpenAI embeddings call failed: {}", e.getMessage()))
                .toFuture();
    }

    private List<double[]> parseEmbeddings(JsonNode response) {
        List<double[]> out = new ArrayList<>();
        JsonNode data = response.path("data");
        if (!data.isArray()) {
            log.warn("Unexpected embeddings response (no 'data' array): {}", response);
            return out;
        }
        for (JsonNode item : data) {
            JsonNode emb = item.path("embedding");
            double[] vec = new double[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vec[i] = emb.get(i).asDouble();
            }
            out.add(vec);
        }
        return out;
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_INPUT_CHARS ? text : text.substring(0, MAX_INPUT_CHARS);
    }

    @Override
    public int getDimensions() {
        return cfg.getDimensions();
    }

    @Override
    public String getProviderName() {
        return "openai";
    }
}