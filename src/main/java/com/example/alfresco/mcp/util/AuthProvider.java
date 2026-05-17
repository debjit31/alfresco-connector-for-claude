package com.example.alfresco.mcp.util;

import org.springframework.http.HttpHeaders;

/**
 * Authentication provider abstraction.
 *
 * Current implementation: Basic auth.
 * To switch to OIDC/OAuth2, implement this interface with token-based auth
 * and swap the bean via a Spring profile or configuration property.
 */
public interface AuthProvider {

    /**
     * Apply authentication to the given HTTP headers.
     * Called before every Alfresco API request.
     */
    void applyAuth(HttpHeaders headers);

    /**
     * Return the auth type identifier.
     */
    String getAuthType();

    /**
     * Check if the current credentials are valid / not expired.
     */
    boolean isValid();
}
