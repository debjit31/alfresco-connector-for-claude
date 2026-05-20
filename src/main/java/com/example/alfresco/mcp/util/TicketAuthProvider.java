package com.example.alfresco.mcp.util;

import com.example.alfresco.mcp.config.AlfrescoProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authentication provider using Alfresco's ticket-based authentication.
 *
 * <p>Acquires an {@code alf_ticket} by calling
 * {@code POST /api/-default-/public/authentication/versions/1/tickets}
 * with the configured username/password. The ticket is cached and
 * automatically refreshed when it expires or a 401 is detected.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>On startup, calls the Alfresco authentication endpoint to get a ticket</li>
 *   <li>The ticket is attached to every request as an {@code Authorization: Basic}
 *       header (base64 of the ticket ID) — this is how Alfresco validates tickets</li>
 *   <li>Tickets are refreshed proactively before the configured TTL expires, or
 *       reactively when {@link #invalidate()} is called after a 401</li>
 * </ol>
 *
 * <p>Activated with {@code alfresco.auth.type=ticket}.</p>
 */
@Component
@ConditionalOnProperty(name = "alfresco.auth.type", havingValue = "ticket")
public class TicketAuthProvider implements AuthProvider {

    private static final Logger log = LoggerFactory.getLogger(TicketAuthProvider.class);

    /** Default ticket TTL: 1 hour. Refresh 5 minutes before expiry. */
    private static final Duration TICKET_TTL = Duration.ofHours(1);
    private static final Duration REFRESH_BUFFER = Duration.ofMinutes(5);

    private final AlfrescoProperties props;
    private final ObjectMapper objectMapper;
    private final WebClient authClient;

    private volatile String currentTicket;
    private volatile Instant ticketExpiry = Instant.MIN;
    private final ReentrantLock refreshLock = new ReentrantLock();

    public TicketAuthProvider(AlfrescoProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        // Separate WebClient for auth calls — no auth header on this one
        this.authClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("TicketAuthProvider active: will acquire alf_ticket from {}", props.getBaseUrl());
    }

    @PostConstruct
    public void init() {
        try {
            refreshTicket();
            log.info("Initial alf_ticket acquired successfully");
        } catch (Exception e) {
            log.warn("Failed to acquire initial alf_ticket (Alfresco may not be running yet): {}. " +
                    "Will retry on first API call.", e.getMessage());
        }
    }

    @Override
    public void applyAuth(HttpHeaders headers) {
        String ticket = getValidTicket();
        if (ticket != null) {
            // Alfresco accepts tickets as Basic auth: base64("ticket_id")
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString(ticket.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        }
    }

    @Override
    public String getAuthType() {
        return "ticket";
    }

    @Override
    public boolean isValid() {
        return currentTicket != null && Instant.now().isBefore(ticketExpiry);
    }

    /**
     * Invalidate the current ticket. Called externally when a 401 is received,
     * forcing a fresh ticket on the next request.
     */
    public void invalidate() {
        log.debug("Ticket invalidated — will refresh on next request");
        currentTicket = null;
        ticketExpiry = Instant.MIN;
    }

    // ── Internal ─────────────────────────────────────────────────────

    private String getValidTicket() {
        if (currentTicket != null && Instant.now().isBefore(ticketExpiry.minus(REFRESH_BUFFER))) {
            return currentTicket;
        }
        // Need to refresh
        if (refreshLock.tryLock()) {
            try {
                // Double-check after acquiring lock
                if (currentTicket == null || !Instant.now().isBefore(ticketExpiry.minus(REFRESH_BUFFER))) {
                    refreshTicket();
                }
            } finally {
                refreshLock.unlock();
            }
        }
        return currentTicket;
    }

    /**
     * Call Alfresco to acquire a new ticket.
     *
     * POST /api/-default-/public/authentication/versions/1/tickets
     * Body: {"userId":"admin","password":"admin"}
     * Response: {"entry":{"id":"TICKET_xxx","userId":"admin"}}
     */
    private void refreshTicket() {
        String authPath = "/api/-default-/public/authentication/versions/1/tickets";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("userId", props.getAuth().getUsername());
        body.put("password", props.getAuth().getPassword());

        try {
            JsonNode response = authClient.post()
                    .uri(authPath)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response == null) {
                throw new IllegalStateException("Empty response from Alfresco ticket endpoint");
            }

            String ticketId = response.path("entry").path("id").asText(null);
            if (ticketId == null || ticketId.isBlank()) {
                throw new IllegalStateException("No ticket ID in response: " + response);
            }

            this.currentTicket = ticketId;
            this.ticketExpiry = Instant.now().plus(TICKET_TTL);

            log.info("alf_ticket acquired: {}... (expires ~{})",
                    ticketId.substring(0, Math.min(20, ticketId.length())),
                    ticketExpiry);

        } catch (Exception e) {
            log.error("Failed to acquire alf_ticket from {}{}: {}",
                    props.getBaseUrl(), authPath, e.getMessage());
            throw new IllegalStateException("Cannot authenticate with Alfresco", e);
        }
    }
}

