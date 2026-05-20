package com.example.alfresco.mcp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for the semantic_search tool.
 */
@Schema(description = "Search indexed Alfresco documents by semantic similarity")
public class SemanticSearchRequest {

    @Schema(
            description = "Natural-language query; results ranked by meaning, not keywords",
            example = "documents discussing quarterly revenue targets",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String query;

    @Schema(
            description = "Number of results to return",
            example = "5",
            defaultValue = "5",
            minimum = "1",
            maximum = "20"
    )
    private Integer topK = 5;

    @Schema(
            description = "Optional comma-separated Alfresco node IDs to restrict the search to",
            example = "d1234567-89ab-cdef-0123-456789abcdef,a7654321-fedc-ba98-7654-3210fedcba98"
    )
    private String nodeIds;

    @Schema(
            description = "Minimum similarity score a hit must reach to be returned",
            example = "0.1",
            defaultValue = "0.1",
            minimum = "0",
            maximum = "1"
    )
    private Double minScore = 0.1;

    public SemanticSearchRequest() {}

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public String getNodeIds() { return nodeIds; }
    public void setNodeIds(String nodeIds) { this.nodeIds = nodeIds; }

    public Double getMinScore() { return minScore; }
    public void setMinScore(Double minScore) { this.minScore = minScore; }
}
