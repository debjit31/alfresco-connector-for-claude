package com.example.alfresco.mcp.client;

import com.example.alfresco.mcp.config.AlfrescoProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Low-level async client for the Alfresco REST API.
 *
 * All methods return {@link CompletableFuture} wrapping the parsed JSON response.
 * Errors are wrapped in {@link AlfrescoApiException}.
 */
@Component
public class AlfrescoRestClient {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoRestClient.class);

    private final WebClient webClient;
    private final AlfrescoProperties props;
    private final ObjectMapper objectMapper;

    public AlfrescoRestClient(WebClient alfrescoWebClient,
                              AlfrescoProperties props,
                              ObjectMapper objectMapper) {
        this.webClient = alfrescoWebClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════

    /**
     * Execute an Alfresco Search API query (AFTS or cmis).
     *
     * @param query     the search term
     * @param language  "afts" or "cmis"
     * @param maxItems  max results to return
     * @param skipCount pagination offset
     * @return raw JSON response from /search/versions/1/search
     */
    public CompletableFuture<JsonNode> search(String query, String language,
                                              int maxItems, int skipCount) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode queryNode = body.putObject("query");
        queryNode.put("query", query);
        queryNode.put("language", language);

        ObjectNode paging = body.putObject("paging");
        paging.put("maxItems", maxItems);
        paging.put("skipCount", skipCount);

        log.debug("Alfresco search: query='{}', language={}, max={}, skip={}",
                  query, language, maxItems, skipCount);

        return webClient.post()
                .uri(props.getSearchApiPath() + "/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnError(e -> log.error("Search failed: {}", e.getMessage()))
                .onErrorMap(this::wrapException)
                .toFuture();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET NODE (metadata)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetch full node metadata by nodeId.
     *
     * @param nodeId Alfresco node UUID
     * @return JSON node info from /nodes/{nodeId}
     */
    public CompletableFuture<JsonNode> getNode(String nodeId) {
        log.debug("Fetching node: {}", nodeId);
        return webClient.get()
                .uri(props.getRestApiPath() + "/nodes/{nodeId}?include=properties,aspectNames,path", nodeId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorMap(this::wrapException)
                .toFuture();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET CONTENT (download)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Download the binary content of a node as a UTF-8 string.
     * Suitable for text-based documents. For binary files, use the byte[] variant.
     *
     * @param nodeId Alfresco node UUID
     * @return document content as String
     */
    public CompletableFuture<String> getContent(String nodeId) {
        log.debug("Downloading content for node: {}", nodeId);
        return webClient.get()
                .uri(props.getRestApiPath() + "/nodes/{nodeId}/content", nodeId)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .bodyToMono(byte[].class)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .onErrorMap(this::wrapException)
                .toFuture();
    }

    /**
     * Download the binary content of a node as raw bytes.
     */
    public CompletableFuture<byte[]> getContentBytes(String nodeId) {
        log.debug("Downloading binary content for node: {}", nodeId);
        return webClient.get()
                .uri(props.getRestApiPath() + "/nodes/{nodeId}/content", nodeId)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .bodyToMono(byte[].class)
                .onErrorMap(this::wrapException)
                .toFuture();
    }

    // ═══════════════════════════════════════════════════════════════
    //  UPLOAD
    // ═══════════════════════════════════════════════════════════════

    /**
     * Upload a file to a target folder in Alfresco.
     *
     * @param parentNodeId the folder nodeId to upload into
     * @param fileName     desired file name
     * @param content      file content as bytes
     * @param mimeType     MIME type (e.g. "text/plain")
     * @return JSON response with the created node info
     */
    public CompletableFuture<JsonNode> uploadContent(String parentNodeId, String fileName,
                                                     byte[] content, String mimeType) {
        log.debug("Uploading '{}' ({}) to folder {}", fileName, mimeType, parentNodeId);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("filedata", new ByteArrayResource(content) {
            @Override
            public String getFilename() { return fileName; }
        }).header(HttpHeaders.CONTENT_TYPE, mimeType);

        builder.part("name", fileName);
        builder.part("nodeType", "cm:content");

        return webClient.post()
                .uri(props.getRestApiPath() + "/nodes/{parentNodeId}/children", parentNodeId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorMap(this::wrapException)
                .toFuture();
    }

    // ═══════════════════════════════════════════════════════════════
    //  UPDATE METADATA
    // ═══════════════════════════════════════════════════════════════

    /**
     * Update properties on an existing node.
     *
     * @param nodeId     Alfresco node UUID
     * @param properties map of property names → values
     * @return updated node JSON
     */
    public CompletableFuture<JsonNode> updateNode(String nodeId, Map<String, Object> properties) {
        log.debug("Updating node {} with properties: {}", nodeId, properties);

        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode propsNode = body.putObject("properties");
        properties.forEach((k, v) -> propsNode.putPOJO(k, v));

        return webClient.put()
                .uri(props.getRestApiPath() + "/nodes/{nodeId}", nodeId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorMap(this::wrapException)
                .toFuture();
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIST CHILDREN
    // ═══════════════════════════════════════════════════════════════

    /**
     * List child nodes of a folder.
     */
    public CompletableFuture<JsonNode> listChildren(String parentNodeId, int maxItems, int skipCount) {
        log.debug("Listing children of node: {}", parentNodeId);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(props.getRestApiPath() + "/nodes/{parentNodeId}/children")
                        .queryParam("maxItems", maxItems)
                        .queryParam("skipCount", skipCount)
                        .queryParam("include", "properties,path")
                        .build(parentNodeId))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorMap(this::wrapException)
                .toFuture();
    }

    // ═══════════════════════════════════════════════════════════════
    //  ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════

    private Throwable wrapException(Throwable t) {
        if (t instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            return new AlfrescoApiException(
                    "Alfresco API error [" + wcre.getStatusCode() + "]: " + body, wcre);
        }
        return new AlfrescoApiException("Alfresco API call failed: " + t.getMessage(), t);
    }

    // ── Custom exception ────────────────────────────────────────────

    public static class AlfrescoApiException extends RuntimeException {
        public AlfrescoApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
