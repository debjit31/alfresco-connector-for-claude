package com.example.alfresco.mcp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for the get_document tool.
 */
@Schema(description = "Retrieve a document from Alfresco by node ID, optionally including text content")
public class GetDocumentRequest {

    @Schema(
            description = "The Alfresco node ID (UUID) of the document to retrieve",
            example = "d1234567-89ab-cdef-0123-456789abcdef",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String nodeId;

    @Schema(
            description = "If true, download and include the text content (truncated to 50KB). " +
                    "If false, return metadata only.",
            example = "false",
            defaultValue = "false"
    )
    private Boolean includeContent = false;

    // ── Constructors ────────────────────────────────────────────────

    public GetDocumentRequest() {}

    public GetDocumentRequest(String nodeId, Boolean includeContent) {
        this.nodeId = nodeId;
        this.includeContent = includeContent;
    }

    // ── Getters / Setters ───────────────────────────────────────────

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public Boolean getIncludeContent() { return includeContent; }
    public void setIncludeContent(Boolean includeContent) { this.includeContent = includeContent; }
}
