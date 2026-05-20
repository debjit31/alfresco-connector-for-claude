package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Embedding provider backed by Ollama's <b>native</b> embed endpoint
 * ({@code POST /api/embed}), activated with {@code rag.embedding.provider=ollama}.
 *
 * <p>Uses the {@code nomic-embed-text} model, which was trained with task-type
 * prefixes. To get the documented +5-10% retrieval accuracy this provider
 * applies them transparently:
 * <ul>
 *   <li>{@link #embedBatch}/{@link #embedForIndexing} → {@code search_document: }
 *       (only ever called while indexing)</li>
 *   <li>{@link #embed} → {@code search_query: } (only ever called for a search
 *       query)</li>
 * </ul>
 * Because the prefix is chosen by which method is invoked, callers
 * (RagService) need no changes.
 *
 * <p>nomic-embed-text always emits 768-dimensional vectors regardless of the
 * configured {@code rag.embedding.dimensions}.
 */
@Component
@ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);

    /** nomic-embed-text is fixed at 768 dimensions. */
    private static final int DIMENSIONS = 768;

    /** Prefixes nomic-embed-text was trained with. */
    private static final String DOC_PREFIX = "search_document: ";
    private static final String QUERY_PREFIX = "search_query: ";

    /** nomic-embed-text context is ~2048 tokens; truncate defensively. */
    private static final int MAX_INPUT_CHARS = 8000;

    /** First-call model load in Ollama can take several seconds. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final RagProperties.Embedding cfg;
    private final String baseUrl;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OllamaEmbeddingProvider(RagProperties props, ObjectMapper objectMapper) {
        this.cfg = props.getEmbedding();
        this.objectMapper = objectMapper;
        this.baseUrl = cfg.getOllamaBaseUrl();
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("Ollama embedding provider active: model={}, dimensions={}, baseUrl={}",
                cfg.getModel(), DIMENSIONS, baseUrl);
    }

    /** Search-time embedding — query prefix. */
    @Override
    public CompletableFuture<double[]> embed(String text) {
        return embedRaw(List.of(QUERY_PREFIX + safe(text)))
                .thenApply(list -> list.isEmpty() ? new double[DIMENSIONS] : list.get(0));
    }

    /** Index-time batch embedding — document prefix on every input. */
    @Override
    public CompletableFuture<List<double[]>> embedBatch(List<String> texts) {
        List<String> prefixed = texts.stream()
                .map(t -> DOC_PREFIX + safe(t))
                .collect(Collectors.toList());
        return embedRaw(prefixed);
    }

    /** Index-time single embedding — document prefix. */
    public CompletableFuture<double[]> embedForIndexing(String text) {
        return embedRaw(List.of(DOC_PREFIX + safe(text)))
                .thenApply(list -> list.isEmpty() ? new double[DIMENSIONS] : list.get(0));
    }

    // ── Ollama native call ──────────────────────────────────────────

    private CompletableFuture<List<double[]>> embedRaw(List<String> inputs) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", cfg.getModel());
        ArrayNode input = body.putArray("input");
        for (String t : inputs) {
            input.add(t);
        }

        log.debug("Ollama embed request: {} input(s), model={}", inputs.size(), cfg.getModel());

        return webClient.post()
                .uri("/api/embed")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(REQUEST_TIMEOUT)
                .map(this::parseEmbeddings)
                .onErrorMap(this::translateError)
                .toFuture();
    }

    /** Ollama native response: {@code { "embeddings": [[...], [...]] }}. */
    private List<double[]> parseEmbeddings(JsonNode response) {
        List<double[]> out = new ArrayList<>();
        JsonNode embeddings = response.path("embeddings");
        if (!embeddings.isArray()) {
            log.warn("Unexpected Ollama response (no 'embeddings' array): {}", response);
            return out;
        }
        for (JsonNode vecNode : embeddings) {
            double[] vec = new double[vecNode.size()];
            for (int i = 0; i < vecNode.size(); i++) {
                vec[i] = vecNode.get(i).asDouble();
            }
            out.add(vec);
        }
        return out;
    }

    private Throwable translateError(Throwable e) {
        if (isConnectionError(e)) {
            String msg = "Ollama is not running at " + baseUrl
                    + ". Start it with: ollama serve  (and: ollama pull " + cfg.getModel() + ")";
            log.error(msg);
            return new IllegalStateException(msg, e);
        }
        log.error("Ollama embed call failed: {}", e.getMessage());
        return e;
    }

    private static boolean isConnectionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WebClientRequestException
                    || t instanceof java.net.ConnectException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("Connection refused")
                    || m.contains("Connection reset"))) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static String safe(String text) {
        if (text == null) return "";
        return text.length() <= MAX_INPUT_CHARS ? text : text.substring(0, MAX_INPUT_CHARS);
    }

    @Override
    public int getDimensions() {
        return DIMENSIONS;
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }
}
