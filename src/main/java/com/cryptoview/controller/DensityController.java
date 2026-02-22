package com.cryptoview.controller;

import com.cryptoview.model.config.Workspace;
import com.cryptoview.model.dto.DensityResponse;
import com.cryptoview.model.enums.DensitySortType;
import com.cryptoview.service.density.DensityFilterService;
import com.cryptoview.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/densities")
@RequiredArgsConstructor
public class DensityController {

    private final DensityFilterService densityFilterService;
    private final WorkspaceService workspaceService;

    @GetMapping
    public List<DensityResponse> getDensities(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) DensitySortType sort,
            @RequestParam(defaultValue = "200") int limit) {

        Workspace ws = workspaceId != null
                ? workspaceService.getWorkspace(workspaceId).orElse(workspaceService.getActiveWorkspace())
                : workspaceService.getActiveWorkspace();

        DensitySortType sortType = sort != null ? sort : ws.getSortType();

        return densityFilterService.filterDensities(ws, sortType, limit);
    }
}
