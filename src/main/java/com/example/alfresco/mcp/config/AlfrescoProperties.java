package com.example.alfresco.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Type-safe configuration properties for the Alfresco connection.
 * All values are bound from the "alfresco" prefix in application.yml.
 */
@Configuration
@ConfigurationProperties(prefix = "alfresco")
@Validated
public class AlfrescoProperties {

    @NotBlank
    private String baseUrl;
    private String restApiPath;
    private String searchApiPath;
    private Auth auth = new Auth();
    private Client client = new Client();
    private Upload upload = new Upload();

    // ── Getters / Setters ───────────────────────────────────────────

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getRestApiPath() { return restApiPath; }
    public void setRestApiPath(String restApiPath) { this.restApiPath = restApiPath; }

    public String getSearchApiPath() { return searchApiPath; }
    public void setSearchApiPath(String searchApiPath) { this.searchApiPath = searchApiPath; }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    public Upload getUpload() { return upload; }
    public void setUpload(Upload upload) { this.upload = upload; }

    /** Convenience: full REST API base URL */
    public String getRestApiBaseUrl() {
        return baseUrl + restApiPath;
    }

    /** Convenience: full Search API base URL */
    public String getSearchApiBaseUrl() {
        return baseUrl + searchApiPath;
    }

    // ── Nested config classes ───────────────────────────────────────

    public static class Auth {
        private String type = "basic";
        private String username = "admin";
        private String password = "admin";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Client {
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 30000;
        private int maxInMemorySizeMb = 16;

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
        public int getMaxInMemorySizeMb() { return maxInMemorySizeMb; }
        public void setMaxInMemorySizeMb(int maxInMemorySizeMb) { this.maxInMemorySizeMb = maxInMemorySizeMb; }
    }

    public static class Upload {
        private int maxFileSizeMb = 100;

        public int getMaxFileSizeMb() { return maxFileSizeMb; }
        public void setMaxFileSizeMb(int maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }
    }
}
