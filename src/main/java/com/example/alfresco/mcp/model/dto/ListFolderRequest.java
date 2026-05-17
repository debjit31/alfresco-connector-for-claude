package com.example.alfresco.mcp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for the list_folder tool.
 */
@Schema(description = "List the contents of a folder in the Alfresco repository")
public class ListFolderRequest {

    @Schema(
            description = "The node ID of the folder to list. " +
                    "Use '-root-' for repository root, '-my-' for user home, '-shared-' for shared files.",
            example = "-root-",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String nodeId;

    @Schema(
            description = "Maximum number of children to return",
            example = "25",
            defaultValue = "25",
            minimum = "1",
            maximum = "100"
    )
    private Integer maxItems = 25;

    @Schema(
            description = "Pagination offset",
            example = "0",
            defaultValue = "0",
            minimum = "0"
    )
    private Integer skipCount = 0;

    // ── Constructors ────────────────────────────────────────────────

    public ListFolderRequest() {}

    public ListFolderRequest(String nodeId, Integer maxItems, Integer skipCount) {
        this.nodeId = nodeId;
        this.maxItems = maxItems;
        this.skipCount = skipCount;
    }

    // ── Getters / Setters ───────────────────────────────────────────

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public Integer getMaxItems() { return maxItems; }
    public void setMaxItems(Integer maxItems) { this.maxItems = maxItems; }

    public Integer getSkipCount() { return skipCount; }
    public void setSkipCount(Integer skipCount) { this.skipCount = skipCount; }
}
