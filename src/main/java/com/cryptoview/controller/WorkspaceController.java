package com.cryptoview.controller;

import com.cryptoview.model.config.Workspace;
import com.cryptoview.model.dto.WorkspaceRequest;
import com.cryptoview.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public List<Workspace> getAllWorkspaces() {
        return workspaceService.getAllWorkspaces();
    }

    @GetMapping("/active")
    public Workspace getActiveWorkspace() {
        return workspaceService.getActiveWorkspace();
    }

    @PostMapping
    public Workspace createWorkspace(@RequestBody WorkspaceRequest request) {
        Workspace ws = workspaceService.createWorkspace(request.name());
        applyRequest(ws, request);
        workspaceService.updateWorkspace(ws.getId(), ws);
        return ws;
    }

    @PutMapping("/{id}")
    public ResponseEntity<Workspace> updateWorkspace(@PathVariable String id,
                                                      @RequestBody WorkspaceRequest request) {
        return workspaceService.getWorkspace(id)
                .map(ws -> {
                    applyRequest(ws, request);
                    return ResponseEntity.ok(workspaceService.updateWorkspace(id, ws).orElse(ws));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkspace(@PathVariable String id) {
        if (workspaceService.deleteWorkspace(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.badRequest().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Workspace> activateWorkspace(@PathVariable String id) {
        return workspaceService.activateWorkspace(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private void applyRequest(Workspace ws, WorkspaceRequest request) {
        if (request.name() != null) ws.setName(request.name());
        if (request.enabledMarkets() != null) ws.setEnabledMarkets(request.enabledMarkets());
        if (request.minDensityOverrides() != null) ws.setMinDensityOverrides(request.minDensityOverrides());
        if (request.symbolMinDensityOverrides() != null) ws.setSymbolMinDensityOverrides(request.symbolMinDensityOverrides());
        if (request.blacklistedSymbols() != null) ws.setBlacklistedSymbols(request.blacklistedSymbols());
        if (request.symbolComments() != null) ws.setSymbolComments(request.symbolComments());
        if (request.sortType() != null) ws.setSortType(request.sortType());
    }
}
