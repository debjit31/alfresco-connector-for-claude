package com.example.alfresco.mcp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for the index_document tool.
 */
@Schema(description = "Index an Alfresco document into the semantic search vector store")
public class IndexDocumentRequest {

    @Schema(
            description = "Alfresco node ID (UUID) of the document to index",
            example = "d1234567-89ab-cdef-0123-456789abcdef",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String nodeId;

    @Schema(
            description = "Re-index even if the document is already indexed",
            example = "false",
            defaultValue = "false"
    )
    private Boolean forceReindex = false;

    public IndexDocumentRequest() {}

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public Boolean getForceReindex() { return forceReindex; }
    public void setForceReindex(Boolean forceReindex) { this.forceReindex = forceReindex; }
}
