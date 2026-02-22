package com.cryptoview.controller;

import com.cryptoview.model.config.Workspace;
import com.cryptoview.model.dto.DensityResponse;
import com.cryptoview.model.enums.DensitySortType;
import com.cryptoview.service.density.DensityFilterService;
import com.cryptoview.service.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DensityWebSocketHandler extends TextWebSocketHandler {

    private static final int DEFAULT_LIMIT = 200;

    private final DensityFilterService densityFilterService;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    private final Map<String, ClientState> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String workspaceId = extractWorkspaceId(session);
        sessions.put(session.getId(), new ClientState(session, workspaceId));
        log.info("WebSocket connected: {} (workspace: {})", session.getId(),
                workspaceId != null ? workspaceId : "active");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket disconnected: {} ({})", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String action = node.path("action").asText("");

            if ("setWorkspace".equals(action)) {
                String workspaceId = node.path("workspaceId").asText(null);
                ClientState state = sessions.get(session.getId());
                if (state != null) {
                    state.workspaceId = workspaceId;
                    log.debug("WebSocket {} switched workspace to: {}", session.getId(),
                            workspaceId != null ? workspaceId : "active");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse WebSocket message from {}: {}", session.getId(), e.getMessage());
        }
    }

    @Scheduled(fixedRate = 500)
    public void broadcastDensities() {
        if (sessions.isEmpty()) return;

        for (ClientState client : sessions.values()) {
            if (!client.session.isOpen()) {
                sessions.remove(client.session.getId());
                continue;
            }

            try {
                Workspace ws = resolveWorkspace(client.workspaceId);
                DensitySortType sortType = ws.getSortType();
                List<DensityResponse> densities = densityFilterService.filterDensities(ws, sortType, DEFAULT_LIMIT);

                Map<String, Object> payload = Map.of(
                        "type", "densities",
                        "timestamp", Instant.now().toString(),
                        "count", densities.size(),
                        "data", densities
                );

                String json = objectMapper.writeValueAsString(payload);
                client.session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.warn("Failed to send WebSocket message to {}: {}", client.session.getId(), e.getMessage());
                try {
                    client.session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ex) {
                    // ignore
                }
                sessions.remove(client.session.getId());
            }
        }
    }

    private Workspace resolveWorkspace(String workspaceId) {
        if (workspaceId != null) {
            return workspaceService.getWorkspace(workspaceId)
                    .orElse(workspaceService.getActiveWorkspace());
        }
        return workspaceService.getActiveWorkspace();
    }

    private String extractWorkspaceId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        try {
            return UriComponentsBuilder.fromUri(session.getUri())
                    .build()
                    .getQueryParams()
                    .getFirst("workspaceId");
        } catch (Exception e) {
            return null;
        }
    }

    private static class ClientState {
        final WebSocketSession session;
        volatile String workspaceId;

        ClientState(WebSocketSession session, String workspaceId) {
            this.session = session;
            this.workspaceId = workspaceId;
        }
    }
}
