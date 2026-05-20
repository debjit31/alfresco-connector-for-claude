package com.example.alfresco.mcp.service.tools;

import com.example.alfresco.mcp.model.ToolDefinition;
import com.example.alfresco.mcp.model.ToolDefinition.InputSchema;
import com.example.alfresco.mcp.model.ToolDefinition.PropertyDef;
import com.example.alfresco.mcp.model.ToolResult;
import com.example.alfresco.mcp.service.alfresco.RagExtension.SearchHit;
import com.example.alfresco.mcp.service.rag.RagService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Tool: semantic_search
 *
 * Embeds a natural-language query and returns the most similar previously
 * indexed document chunks, ranked by cosine similarity.
 * Only registered when RAG is enabled.
 */
@Component
@ConditionalOnBean(RagService.class)
public class SemanticSearchTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchTool.class);

    private final RagService ragService;

    public SemanticSearchTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return "semantic_search";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("query", new PropertyDef("string",
                "Natural-language query; results are ranked by hybrid semantic + keyword relevance"));
        props.put("topK", new PropertyDef("integer",
                "Number of results to return, 1-20 (default: 5)", 5));
        props.put("nodeIds", new PropertyDef("string",
                "Optional comma-separated Alfresco node IDs to restrict the search to"));
        props.put("minScore", new PropertyDef("number",
                "Minimum relevance score a hit must reach (default: 0.0)", 0.0));

        InputSchema schema = new InputSchema(props, List.of("query"));

        return new ToolDefinition(
                getName(),
                "Search previously indexed Alfresco documents using a 7-stage hybrid RAG " +
                "pipeline: query expansion → dense vector search + BM25 keyword search → " +
                "Reciprocal Rank Fusion → cross-encoder reranking. Documents must first be " +
                "indexed with index_document. Returns ranked chunks with relevance scores.",
                schema);
    }

    @Override
    public CompletableFuture<ToolResult> execute(JsonNode arguments) {
        String query = getRequiredString(arguments, "query");
        if (query == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.error("Missing required parameter: 'query'"));
        }

        int topK = arguments.has("topK") ? arguments.get("topK").asInt(5) : 5;
        topK = Math.max(1, Math.min(topK, 20));

        double minScore = arguments.has("minScore")
                ? arguments.get("minScore").asDouble(0.0) : 0.0;

        Set<String> nodeIds = parseNodeIds(getRequiredString(arguments, "nodeIds"));

        log.info("semantic_search: query='{}', topK={}, minScore={}, filter={}",
                query, topK, minScore, nodeIds == null ? "none" : nodeIds.size() + " nodes");

        return ragService.semanticSearch(query, topK, nodeIds)
                .thenApply(hits -> ToolResult.json(buildResult(query, hits, minScore)))
                .exceptionally(ex -> ToolResult.error(
                        "Semantic search failed: " + ex.getMessage()));
    }

    private Map<String, Object> buildResult(String query, List<SearchHit> hits, double minScore) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        int rank = 1;
        for (SearchHit hit : hits) {
            if (hit.score() < minScore) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("score", Math.round(hit.score() * 1000.0) / 1000.0);
            row.put("nodeId", hit.nodeId());
            row.put("chunkId", hit.chunkId());
            row.put("text", hit.text());

            Map<String, Object> meta = hit.metadata() != null ? hit.metadata() : Map.of();
            Map<String, Object> srcMeta = new LinkedHashMap<>();
            srcMeta.put("documentName", meta.get("name"));
            srcMeta.put("path", meta.get("path"));
            row.put("metadata", srcMeta);
            ranked.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("totalHits", ranked.size());
        result.put("minScore", minScore);
        result.put("results", ranked);
        return result;
    }

    private Set<String> parseNodeIds(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> ids = java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return ids.isEmpty() ? null : ids;
    }

    private String getRequiredString(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String val = args.get(field).asText().trim();
        return val.isEmpty() ? null : val;
    }
}
