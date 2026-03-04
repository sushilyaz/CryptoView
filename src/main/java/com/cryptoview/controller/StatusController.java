package com.cryptoview.controller;

import com.cryptoview.exchange.ExchangeManager;
import com.cryptoview.service.density.DensityTracker;
import com.cryptoview.service.volume.VolumeTracker;
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
    private final VolumeTracker volumeTracker;

    @GetMapping
    public Map<String, Object> getStatus() {
        return Map.ofEntries(
                Map.entry("connectedExchanges", exchangeManager.getConnectedExchangesCount()),
                Map.entry("totalSymbols", exchangeManager.getTotalSubscribedSymbols()),
                Map.entry("trackedDensities", densityTracker.getTrackedCount()),
                Map.entry("activeWorkspace", workspaceService.getActiveWorkspace().getName()),
                Map.entry("activeWorkspaceId", workspaceService.getActiveWorkspaceId()),
                Map.entry("volumeTrackingReady", volumeTracker.isVolumeDataReady()),
                Map.entry("appUptimeSeconds", volumeTracker.getUptimeSeconds())
        );
    }
}
