package com.cryptoview.model.dto;

import com.cryptoview.model.enums.DensitySortType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

public record WorkspaceRequest(
        String name,
        Set<String> enabledMarkets,
        Map<String, BigDecimal> minDensityOverrides,
        Map<String, BigDecimal> symbolMinDensityOverrides,
        Map<String, BigDecimal> symbolMarketMinDensityOverrides,
        Set<String> blacklistedSymbols,
        Set<String> favoritedSymbols,
        Map<String, String> symbolComments,
        DensitySortType sortType,
        Integer newBadgeDurationMinutes,
        Boolean tsMode
) {
}
