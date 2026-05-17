package com.example.alfresco.mcp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for the upload_document tool.
 */
@Schema(description = "Upload a new document to the Alfresco repository")
public class UploadDocumentRequest {

    @Schema(
            description = "The node ID of the target folder. " +
                    "Use '-root-' for repository root, '-my-' for user home, '-shared-' for shared files.",
            example = "-my-",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String parentNodeId;

    @Schema(
            description = "File name for the uploaded document (e.g. 'report.txt', 'data.json')",
            example = "meeting-notes.txt",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String fileName;

    @Schema(
            description = "The text content to upload as the file body",
            example = "Q4 Planning Meeting Notes:\n1. Revenue targets revised\n2. Hiring plan approved\n3. Product roadmap updated",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String content;

    @Schema(
            description = "MIME type of the content",
            example = "text/plain",
            defaultValue = "text/plain"
    )
    private String mimeType = "text/plain";

    // ── Constructors ────────────────────────────────────────────────

    public UploadDocumentRequest() {}

    public UploadDocumentRequest(String parentNodeId, String fileName, String content, String mimeType) {
        this.parentNodeId = parentNodeId;
        this.fileName = fileName;
        this.content = content;
        this.mimeType = mimeType;
    }

    // ── Getters / Setters ───────────────────────────────────────────

    public String getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(String parentNodeId) { this.parentNodeId = parentNodeId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}
