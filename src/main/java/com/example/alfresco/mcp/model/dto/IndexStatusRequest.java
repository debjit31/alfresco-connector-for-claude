package com.example.alfresco.mcp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for the index_status tool.
 */
@Schema(description = "Inspect or manage the semantic search vector index")
public class IndexStatusRequest {

    @Schema(
            description = "status = index stats, check = is node indexed, " +
                    "remove = delete node vectors, clear = wipe entire index",
            example = "status",
            defaultValue = "status",
            allowableValues = {"status", "check", "remove", "clear"}
    )
    private String action = "status";

    @Schema(
            description = "Alfresco node ID — required for 'check' and 'remove'",
            example = "d1234567-89ab-cdef-0123-456789abcdef"
    )
    private String nodeId;

    public IndexStatusRequest() {}

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
}
