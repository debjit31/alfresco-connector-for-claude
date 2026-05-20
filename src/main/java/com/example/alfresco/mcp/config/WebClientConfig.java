package com.example.alfresco.mcp.config;

import com.example.alfresco.mcp.util.AuthProvider;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Configures the reactive WebClient used for all Alfresco REST API calls.
 *
 * <p>Auth is applied via the {@link AuthProvider} abstraction — the active
 * provider (basic, ticket, or oauth2) is injected by Spring based on
 * {@code alfresco.auth.type}. Each request gets headers applied dynamically
 * through an exchange filter, so ticket refresh / token rotation works
 * transparently.</p>
 */
@Configuration
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    @Bean
    public WebClient alfrescoWebClient(AlfrescoProperties props, AuthProvider authProvider) {

        log.info("Configuring Alfresco WebClient: baseUrl={}, authType={}",
                props.getBaseUrl(), authProvider.getAuthType());

        // ── Netty HTTP client with timeouts ─────────────────────────
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getClient().getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(props.getClient().getReadTimeoutMs()));

        // ── Allow large responses (document downloads) ──────────────
        int maxBytes = props.getClient().getMaxInMemorySizeMb() * 1024 * 1024;
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(maxBytes))
                .build();

        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                // Dynamic auth: applies headers from the active AuthProvider on every request
                .filter((request, next) -> {
                    HttpHeaders headers = new HttpHeaders();
                    authProvider.applyAuth(headers);
                    ClientRequest authed = ClientRequest.from(request)
                            .headers(h -> h.addAll(headers))
                            .build();
                    return next.exchange(authed);
                })
                .build();
    }
}
