package com.example.alfresco.mcp.service.tools;

import com.example.alfresco.mcp.model.ToolDefinition;
import com.example.alfresco.mcp.model.ToolDefinition.InputSchema;
import com.example.alfresco.mcp.model.ToolDefinition.PropertyDef;
import com.example.alfresco.mcp.model.ToolResult;
import com.example.alfresco.mcp.service.alfresco.AlfrescoDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool: upload_document
 *
 * Uploads a new document to the Alfresco repository.
 * Accepts text content and creates a file in the specified folder.
 */
@Component
public class UploadDocumentTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(UploadDocumentTool.class);
    private final AlfrescoDocumentService docService;

    public UploadDocumentTool(AlfrescoDocumentService docService) {
        this.docService = docService;
    }

    @Override
    public String getName() {
        return "upload_document";
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("parentNodeId", new PropertyDef("string",
                "The node ID of the target folder. Use '-root-' for the repository root, " +
                "'-my-' for the current user's home folder, or '-shared-' for shared files."));
        props.put("fileName", new PropertyDef("string",
                "Name for the uploaded file (e.g. 'report.txt', 'data.json')"));
        props.put("content", new PropertyDef("string",
                "The text content to upload as the file body"));
        props.put("mimeType", new PropertyDef("string",
                "MIME type of the content (default: 'text/plain')",
                "text/plain"));

        return new ToolDefinition(
                getName(),
                "Upload a new document to the Alfresco repository. Creates a file with " +
                "the given name and content in the specified folder. Returns the created " +
                "document's node ID and metadata.",
                new InputSchema(props, List.of("parentNodeId", "fileName", "content"))
        );
    }

    @Override
    public CompletableFuture<ToolResult> execute(JsonNode arguments) {
        String parentNodeId = getRequiredString(arguments, "parentNodeId");
        String fileName = getRequiredString(arguments, "fileName");
        String content = getRequiredString(arguments, "content");

        if (parentNodeId == null || fileName == null || content == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.error("Missing required parameters. Need: parentNodeId, fileName, content"));
        }

        String mimeType = arguments.has("mimeType")
                ? arguments.get("mimeType").asText("text/plain")
                : "text/plain";

        log.info("Executing upload_document: file='{}', parent={}, mime={}",
                 fileName, parentNodeId, mimeType);

        return docService.uploadDocument(parentNodeId, fileName, content, mimeType)
                .thenApply(ToolResult::json)
                .exceptionally(ex -> ToolResult.error(
                        "Upload failed: " + ex.getMessage()));
    }

    private String getRequiredString(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String val = args.get(field).asText().trim();
        return val.isEmpty() ? null : val;
    }
}
