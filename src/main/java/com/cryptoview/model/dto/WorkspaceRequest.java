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
        Set<String> blacklistedSymbols,
        Map<String, String> symbolComments,
        DensitySortType sortType
) {
}
