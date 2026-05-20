package com.example.alfresco.mcp.service.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction over an embedding backend (local hashing TF-IDF or a remote
 * provider such as OpenAI).
 *
 * All vectors returned by a given provider instance must share the same
 * dimensionality ({@link #getDimensions()}).
 */
public interface EmbeddingProvider {

    /** Embed a single text into a dense vector. */
    CompletableFuture<double[]> embed(String text);

    /**
     * Embed a batch of texts. The default is a sequential composition over
     * {@link #embed(String)}; providers with a native batch endpoint (or
     * stateful indexing concerns) should override this.
     */
    default CompletableFuture<List<double[]>> embedBatch(List<String> texts) {
        CompletableFuture<List<double[]>> acc =
                CompletableFuture.completedFuture(new ArrayList<>());
        for (String t : texts) {
            acc = acc.thenCompose(list ->
                    embed(t).thenApply(vec -> {
                        list.add(vec);
                        return list;
                    }));
        }
        return acc;
    }

    /** Dimensionality of every vector this provider emits. */
    int getDimensions();

    /** Short identifier used in logs and stats (e.g. "local", "openai"). */
    String getProviderName();
}