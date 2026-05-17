package com.example.alfresco.mcp.util;

import com.example.alfresco.mcp.config.AlfrescoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * OAuth2 / OIDC authentication provider (stub).
 *
 * Activated when alfresco.auth.type=oauth2.
 *
 * TODO: Implement token acquisition from your IdP:
 *   1. Add spring-boot-starter-oauth2-client dependency
 *   2. Configure token endpoint, client-id, client-secret in application.yml
 *   3. Implement token refresh logic
 */
@Component
@ConditionalOnProperty(name = "alfresco.auth.type", havingValue = "oauth2")
public class OAuth2AuthProvider implements AuthProvider {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthProvider.class);

    public OAuth2AuthProvider(AlfrescoProperties props) {
        log.warn("OAuth2 auth provider is a stub. Implement token acquisition before use.");
    }

    @Override
    public void applyAuth(HttpHeaders headers) {
        // TODO: Acquire / refresh OAuth2 token and set Bearer header
        // String token = tokenService.getAccessToken();
        // headers.setBearerAuth(token);
        throw new UnsupportedOperationException(
                "OAuth2 auth not yet implemented. Set alfresco.auth.type=basic or implement this class.");
    }

    @Override
    public String getAuthType() {
        return "oauth2";
    }

    @Override
    public boolean isValid() {
        return false; // Not implemented yet
    }
}
