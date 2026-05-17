package com.example.alfresco.mcp.util;

import com.example.alfresco.mcp.config.AlfrescoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Basic authentication provider.
 * Active when alfresco.auth.type=basic (default).
 */
@Component
@ConditionalOnProperty(name = "alfresco.auth.type", havingValue = "basic", matchIfMissing = true)
public class BasicAuthProvider implements AuthProvider {

    private final String encodedCredentials;

    public BasicAuthProvider(AlfrescoProperties props) {
        String raw = props.getAuth().getUsername() + ":" + props.getAuth().getPassword();
        this.encodedCredentials = Base64.getEncoder().encodeToString(raw.getBytes());
    }

    @Override
    public void applyAuth(HttpHeaders headers) {
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encodedCredentials);
    }

    @Override
    public String getAuthType() {
        return "basic";
    }

    @Override
    public boolean isValid() {
        return encodedCredentials != null && !encodedCredentials.isBlank();
    }
}
