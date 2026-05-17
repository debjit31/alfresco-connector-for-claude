package com.example.alfresco.mcp.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Base64;

/**
 * Configures the reactive WebClient used for all Alfresco REST API calls.
 * Supports Basic auth now; swap the header filter for OAuth2 later.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient alfrescoWebClient(AlfrescoProperties props) {

        // ── Auth header ─────────────────────────────────────────────
        String credentials = props.getAuth().getUsername() + ":" + props.getAuth().getPassword();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());

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
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
