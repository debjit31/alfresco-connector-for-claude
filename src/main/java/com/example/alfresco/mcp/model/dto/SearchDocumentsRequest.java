package com.example.alfresco.mcp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for the search_documents tool.
 */
@Schema(description = "Search the Alfresco repository for documents matching a query")
public class SearchDocumentsRequest {

    @Schema(
            description = "Search query. Can be a simple keyword or an AFTS query " +
                    "(e.g. 'budget report' or 'cm:name:\"Q4*\" AND TYPE:\"cm:content\"')",
            example = "budget report",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String query;

    @Schema(
            description = "Maximum number of results to return",
            example = "10",
            defaultValue = "20",
            minimum = "1",
            maximum = "100"
    )
    private Integer maxItems = 20;

    @Schema(
            description = "Number of results to skip for pagination",
            example = "0",
            defaultValue = "0",
            minimum = "0"
    )
    private Integer skipCount = 0;

    // ── Constructors ────────────────────────────────────────────────

    public SearchDocumentsRequest() {}

    public SearchDocumentsRequest(String query, Integer maxItems, Integer skipCount) {
        this.query = query;
        this.maxItems = maxItems;
        this.skipCount = skipCount;
    }

    // ── Getters / Setters ───────────────────────────────────────────

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Integer getMaxItems() { return maxItems; }
    public void setMaxItems(Integer maxItems) { this.maxItems = maxItems; }

    public Integer getSkipCount() { return skipCount; }
    public void setSkipCount(Integer skipCount) { this.skipCount = skipCount; }
}
