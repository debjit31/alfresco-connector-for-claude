package com.example.alfresco.mcp.controller;

import com.example.alfresco.mcp.model.ToolResult;
import com.example.alfresco.mcp.model.dto.*;
import com.example.alfresco.mcp.service.alfresco.AlfrescoDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST convenience controller that exposes every MCP tool as its own
 * individually-documented endpoint.
 *
 * This exists purely so Swagger UI can render typed schemas, per-field
 * descriptions, and pre-filled examples for each tool — unlike the raw
 * JSON-RPC {@code POST /mcp} endpoint which is a single opaque payload.
 *
 * All endpoints delegate to the same {@link AlfrescoDocumentService}
 * that the MCP tool implementations use.
 */
@RestController
@RequestMapping("/api/tools")
public class McpToolsRestController {

    private static final Logger log = LoggerFactory.getLogger(McpToolsRestController.class);

    private final AlfrescoDocumentService docService;
    private final ObjectMapper objectMapper;

    public McpToolsRestController(AlfrescoDocumentService docService, ObjectMapper objectMapper) {
        this.docService = docService;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEARCH DOCUMENTS
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Search documents",
            description = """
                    Search the Alfresco repository using keywords or AFTS (Alfresco Full Text Search) syntax.
                    
                    **Simple keyword search:** `budget report`
                    
                    **AFTS query:** `cm:name:"Q4*" AND TYPE:"cm:content"`
                    
                    Returns a paginated list of matching documents with node IDs, names,
                    types, sizes, paths, and timestamps.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results returned",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "documents": [
                                        {
                                          "nodeId": "abc123-def456",
                                          "name": "Q4-Budget-Report.pdf",
                                          "nodeType": "cm:content",
                                          "isFile": true,
                                          "isFolder": false,
                                          "createdAt": "2024-10-15T09:30:00Z",
                                          "modifiedAt": "2024-11-01T14:22:00Z",
                                          "createdByUser": "admin",
                                          "content": { "mimeType": "application/pdf", "sizeInBytes": 245760 },
                                          "path": "/Company Home/Shared"
                                        }
                                      ],
                                      "pagination": { "totalItems": 1, "maxItems": 20, "skipCount": 0, "hasMoreItems": false }
                                    }
                                    """))),
            @ApiResponse(responseCode = "502", description = "Alfresco API error")
    })
    @Tag(name = "Tools — Search")
    @PostMapping(value = "/search_documents",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<Map<String, Object>>> searchDocuments(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Search parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = SearchDocumentsRequest.class))
            )
            @RequestBody SearchDocumentsRequest request) {

        log.info("REST search_documents: query='{}', max={}, skip={}",
                 request.getQuery(), request.getMaxItems(), request.getSkipCount());

        int maxItems = request.getMaxItems() != null ? Math.min(request.getMaxItems(), 100) : 20;
        int skipCount = request.getSkipCount() != null ? Math.max(request.getSkipCount(), 0) : 0;

        return docService.searchDocuments(request.getQuery(), maxItems, skipCount)
                .thenApply(ResponseEntity::ok);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET DOCUMENT
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Get document by node ID",
            description = """
                    Retrieve a specific document from the Alfresco repository by its node ID (UUID).
                    
                    - **Metadata only** (default): Returns name, type, properties, aspects, path, timestamps.
                    - **With content** (`includeContent: true`): Also downloads and includes the text content,
                      truncated to 50KB to fit LLM context windows.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document info returned",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "nodeId": "abc123-def456",
                                      "name": "meeting-notes.txt",
                                      "nodeType": "cm:content",
                                      "isFile": true,
                                      "properties": {
                                        "cm:title": "Q4 Meeting Notes",
                                        "cm:description": "Quarterly planning"
                                      },
                                      "aspects": ["cm:titled", "cm:auditable"],
                                      "contentInfo": { "mimeType": "text/plain", "sizeInBytes": 1024 },
                                      "content": "Q4 planning meeting notes...",
                                      "contentTruncated": false
                                    }
                                    """))),
            @ApiResponse(responseCode = "502", description = "Alfresco API error")
    })
    @Tag(name = "Tools — Documents")
    @PostMapping(value = "/get_document",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getDocument(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Document retrieval parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = GetDocumentRequest.class))
            )
            @RequestBody GetDocumentRequest request) {

        log.info("REST get_document: nodeId={}, includeContent={}",
                 request.getNodeId(), request.getIncludeContent());

        boolean includeContent = request.getIncludeContent() != null && request.getIncludeContent();

        return docService.getDocument(request.getNodeId(), includeContent)
                .thenApply(ResponseEntity::ok);
    }

    // ═══════════════════════════════════════════════════════════════
    //  UPLOAD DOCUMENT
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Upload a new document",
            description = """
                    Upload a new text document to the Alfresco repository.
                    
                    **Target folder shortcuts:**
                    - `-root-` — Repository root
                    - `-my-` — Current user's home folder
                    - `-shared-` — Shared files folder
                    - Or use a specific folder node ID (UUID)
                    
                    Returns the created document's metadata including its new node ID.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document uploaded successfully",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "nodeId": "new-uuid-here",
                                      "name": "meeting-notes.txt",
                                      "nodeType": "cm:content",
                                      "isFile": true,
                                      "uploadStatus": "success"
                                    }
                                    """))),
            @ApiResponse(responseCode = "502", description = "Alfresco API error")
    })
    @Tag(name = "Tools — Documents")
    @PostMapping(value = "/upload_document",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<Map<String, Object>>> uploadDocument(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Upload parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = UploadDocumentRequest.class))
            )
            @RequestBody UploadDocumentRequest request) {

        log.info("REST upload_document: file='{}', parent={}, mime={}",
                 request.getFileName(), request.getParentNodeId(), request.getMimeType());

        String mimeType = request.getMimeType() != null ? request.getMimeType() : "text/plain";

        return docService.uploadDocument(
                        request.getParentNodeId(),
                        request.getFileName(),
                        request.getContent(),
                        mimeType)
                .thenApply(ResponseEntity::ok);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET METADATA
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Get node metadata",
            description = """
                    Retrieve all metadata for a document or folder: properties (title, description,
                    author, custom fields), aspect names, node type, file path, timestamps, and
                    content info (MIME type, size).
                    
                    Does **not** download the document content — use `get_document` with
                    `includeContent: true` for that.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Metadata returned",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "nodeId": "abc123-def456",
                                      "name": "report.docx",
                                      "nodeType": "cm:content",
                                      "properties": {
                                        "cm:title": "Annual Report 2024",
                                        "cm:author": "Jane Smith"
                                      },
                                      "aspects": ["cm:titled", "cm:auditable", "cm:versionable"],
                                      "path": "/Company Home/Shared/Reports"
                                    }
                                    """))),
            @ApiResponse(responseCode = "502", description = "Alfresco API error")
    })
    @Tag(name = "Tools — Documents")
    @PostMapping(value = "/get_metadata",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getMetadata(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Metadata retrieval parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = GetMetadataRequest.class))
            )
            @RequestBody GetMetadataRequest request) {

        log.info("REST get_metadata: nodeId={}", request.getNodeId());

        return docService.getMetadata(request.getNodeId())
                .thenApply(ResponseEntity::ok);
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIST FOLDER
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "List folder contents",
            description = """
                    List child documents and sub-folders of an Alfresco folder.
                    
                    **Folder shortcuts:**
                    - `-root-` — Repository root
                    - `-my-` — Current user's home folder
                    - `-shared-` — Shared files folder
                    - Or use a specific folder node ID (UUID)
                    
                    Returns a paginated list of children with names, types, sizes, and paths.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Folder contents returned",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "documents": [
                                        { "nodeId": "child-uuid-1", "name": "Reports", "isFolder": true },
                                        { "nodeId": "child-uuid-2", "name": "budget.xlsx", "isFile": true, "content": { "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "sizeInBytes": 35840 } }
                                      ],
                                      "pagination": { "totalItems": 2, "maxItems": 25, "skipCount": 0, "hasMoreItems": false }
                                    }
                                    """))),
            @ApiResponse(responseCode = "502", description = "Alfresco API error")
    })
    @Tag(name = "Tools — Folders")
    @PostMapping(value = "/list_folder",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<Map<String, Object>>> listFolder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Folder listing parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = ListFolderRequest.class))
            )
            @RequestBody ListFolderRequest request) {

        log.info("REST list_folder: nodeId={}, max={}", request.getNodeId(), request.getMaxItems());

        int maxItems = request.getMaxItems() != null ? Math.min(request.getMaxItems(), 100) : 25;
        int skipCount = request.getSkipCount() != null ? Math.max(request.getSkipCount(), 0) : 0;

        return docService.listFolder(request.getNodeId(), maxItems, skipCount)
                .thenApply(ResponseEntity::ok);
    }
}
