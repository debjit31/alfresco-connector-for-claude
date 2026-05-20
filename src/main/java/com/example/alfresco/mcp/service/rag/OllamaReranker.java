package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.config.RagProperties;
import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Cross-encoder reranker backed by Ollama (Stage 7 of the pipeline).
 *
 * <p>Uses Ollama's generate endpoint to score each (query, passage) pair.
 * The model (default: bge-reranker-v2-m3) is prompted to output a relevance
 * score 0-10 for each candidate. Results are then re-sorted by that score.</p>
 *
 * <p>This is a "point-wise" reranker: each candidate is scored independently.
 * For production scale, a dedicated reranking API (Cohere, Jina) would be
 * more efficient, but this approach works well for up to ~30 candidates
 * and keeps everything local.</p>
 *
 * <p>Activated with {@code rag.reranking.provider=ollama}.</p>
 */
@Component
@ConditionalOnProperty(name = "rag.reranking.provider", havingValue = "ollama")
public class OllamaReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(OllamaReranker.class);

    private final RagProperties.Reranking cfg;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public OllamaReranker(RagProperties props, ObjectMapper objectMapper) {
        this.cfg = props.getReranking();
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofMillis(cfg.getTimeoutMs());
        this.webClient = WebClient.builder()
                .baseUrl(cfg.getOllamaBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("OllamaReranker active: model={}, baseUrl={}, timeout={}ms",
                cfg.getModel(), cfg.getOllamaBaseUrl(), cfg.getTimeoutMs());
    }

    @Override
    public CompletableFuture<List<SearchHit>> rerank(String query, List<SearchHit> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return CompletableFuture.completedFuture(List.of());
        }

        // Limit candidates to avoid overwhelming the model
        List<SearchHit> toRerank = candidates.size() > cfg.getTopKBeforeRerank()
                ? candidates.subList(0, cfg.getTopKBeforeRerank())
                : candidates;

        log.info("Reranking {} candidates for query: '{}'", toRerank.size(), truncate(query, 80));

        // Score all candidates in parallel
        List<CompletableFuture<ScoredHit>> futures = new ArrayList<>();
        for (int i = 0; i < toRerank.size(); i++) {
            final int idx = i;
            SearchHit hit = toRerank.get(i);
            futures.add(scoreCandidate(query, hit.text(), idx)
                    .thenApply(score -> new ScoredHit(hit, score))
                    .exceptionally(ex -> {
                        log.warn("Reranker failed for candidate {}: {}", idx, ex.getMessage());
                        // Fall back to original score so failures don't drop results
                        return new ScoredHit(hit, hit.score() * 0.5);
                    }));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .sorted(Comparator.comparingDouble(ScoredHit::rerankScore).reversed())
                        .limit(topK)
                        .map(sh -> new SearchHit(
                                sh.hit.chunkId(),
                                sh.hit.nodeId(),
                                sh.hit.text(),
                                sh.rerankScore(),
                                sh.hit.metadata()))
                        .collect(Collectors.toList()));
    }

    /**
     * Score a single (query, passage) pair using Ollama generate.
     * The prompt asks the model to output just a number 0-10.
     */
    private CompletableFuture<Double> scoreCandidate(String query, String passage, int idx) {
        String prompt = buildScoringPrompt(query, passage);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("prompt", prompt);
        body.put("stream", false);
        // Keep response short — we only want a number
        ObjectNode options = body.putObject("options");
        options.put("num_predict", 10);
        options.put("temperature", 0.0);

        return webClient.post()
                .uri("/api/generate")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(response -> parseScore(response, idx))
                .onErrorReturn(0.0)
                .toFuture();
    }

    private String buildScoringPrompt(String query, String passage) {
        String truncatedPassage = truncate(passage, 2000);
        return String.format(
                "You are a relevance scoring system. Rate the relevance of the following passage to the query on a scale of 0 to 10, where 0 means completely irrelevant and 10 means perfectly relevant. Output ONLY a single number.\n\n" +
                "Query: %s\n\n" +
                "Passage: %s\n\n" +
                "Relevance score (0-10):",
                query, truncatedPassage);
    }

    private double parseScore(JsonNode response, int idx) {
        String text = response.path("response").asText("").trim();
        try {
            // Extract the first number from the response
            String numStr = text.replaceAll("[^0-9.]", " ").trim().split("\\s+")[0];
            double score = Double.parseDouble(numStr);
            // Normalize to 0-1 range
            score = Math.max(0.0, Math.min(10.0, score)) / 10.0;
            log.debug("Reranker scored candidate {}: {} (raw: '{}')", idx, score, text);
            return score;
        } catch (Exception e) {
            log.warn("Could not parse reranker score for candidate {} (raw: '{}'): {}",
                    idx, text, e.getMessage());
            return 0.0;
        }
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }

    private record ScoredHit(SearchHit hit, double rerankScore) {}

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

