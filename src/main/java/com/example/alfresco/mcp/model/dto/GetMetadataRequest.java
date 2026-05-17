package com.example.alfresco.mcp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for the get_metadata tool.
 */
@Schema(description = "Retrieve detailed metadata, properties, and aspects for a node")
public class GetMetadataRequest {

    @Schema(
            description = "The Alfresco node ID (UUID) of the document or folder",
            example = "d1234567-89ab-cdef-0123-456789abcdef",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String nodeId;

    // ── Constructors ────────────────────────────────────────────────

    public GetMetadataRequest() {}

    public GetMetadataRequest(String nodeId) {
        this.nodeId = nodeId;
    }

    // ── Getters / Setters ───────────────────────────────────────────

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
}
