package com.example.alfresco.mcp.service.rag;

import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Pass-through reranker: returns candidates as-is (limited to topK).
 *
 * Activated when {@code rag.reranking.provider=none} or when no other
 * reranker bean matches. This is the default fallback to keep the pipeline
 * working even without a reranking model.
 */
@Component
@ConditionalOnProperty(name = "rag.reranking.provider", havingValue = "none", matchIfMissing = true)
public class NoOpReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(NoOpReranker.class);

    public NoOpReranker() {
        log.info("NoOpReranker active: reranking disabled (pass-through)");
    }

    @Override
    public CompletableFuture<List<SearchHit>> rerank(String query, List<SearchHit> candidates, int topK) {
        List<SearchHit> result = candidates.stream()
                .limit(topK)
                .collect(Collectors.toList());
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public String getProviderName() {
        return "none";
    }
}

