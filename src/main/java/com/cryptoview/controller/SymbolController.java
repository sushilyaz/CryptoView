package com.cryptoview.controller;

import com.cryptoview.model.config.Workspace;
import com.cryptoview.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
@RequiredArgsConstructor
public class SymbolController {

    private final WorkspaceService workspaceService;

    @PostMapping("/blacklist/{symbol}")
    public ResponseEntity<Workspace> addToBlacklist(@PathVariable String workspaceId,
                                                     @PathVariable String symbol) {
        return workspaceService.addToBlacklist(workspaceId, symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/blacklist/{symbol}")
    public ResponseEntity<Workspace> removeFromBlacklist(@PathVariable String workspaceId,
                                                          @PathVariable String symbol) {
        return workspaceService.removeFromBlacklist(workspaceId, symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/symbols/{symbol}/comment")
    public ResponseEntity<Workspace> setComment(@PathVariable String workspaceId,
                                                 @PathVariable String symbol,
                                                 @RequestBody Map<String, String> body) {
        String comment = body.get("comment");
        if (comment == null) return ResponseEntity.badRequest().build();
        return workspaceService.setSymbolComment(workspaceId, symbol, comment)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/symbols/{symbol}/comment")
    public ResponseEntity<Workspace> removeComment(@PathVariable String workspaceId,
                                                    @PathVariable String symbol) {
        return workspaceService.removeSymbolComment(workspaceId, symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/symbols/{symbol}/min-density")
    public ResponseEntity<Workspace> setMinDensity(@PathVariable String workspaceId,
                                                    @PathVariable String symbol,
                                                    @RequestBody Map<String, BigDecimal> body) {
        BigDecimal minDensity = body.get("minDensity");
        if (minDensity == null) return ResponseEntity.badRequest().build();
        return workspaceService.setSymbolMinDensity(workspaceId, symbol, minDensity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/symbols/{symbol}/min-density")
    public ResponseEntity<Workspace> removeMinDensity(@PathVariable String workspaceId,
                                                       @PathVariable String symbol) {
        return workspaceService.removeSymbolMinDensity(workspaceId, symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
