package com.cryptoview.controller;

import com.cryptoview.exchange.ExchangeManager;
import com.cryptoview.service.density.DensityTracker;
import com.cryptoview.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/status")
@RequiredArgsConstructor
public class StatusController {

    private final ExchangeManager exchangeManager;
    private final DensityTracker densityTracker;
    private final WorkspaceService workspaceService;

    @GetMapping
    public Map<String, Object> getStatus() {
        return Map.of(
                "connectedExchanges", exchangeManager.getConnectedExchangesCount(),
                "totalSymbols", exchangeManager.getTotalSubscribedSymbols(),
                "trackedDensities", densityTracker.getTrackedCount(),
                "activeWorkspace", workspaceService.getActiveWorkspace().getName(),
                "activeWorkspaceId", workspaceService.getActiveWorkspaceId()
        );
    }
}
