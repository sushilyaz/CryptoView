package com.cryptoview.model.config;

import com.cryptoview.model.enums.DensitySortType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.*;

@Data
public class Workspace {

    private String id;
    private String name;
    private boolean active;

    /**
     * Enabled exchange+marketType pairs (storage keys like "BINANCE_SPOT").
     * Empty set = all enabled.
     */
    private Set<String> enabledMarkets = new HashSet<>();

    /**
     * Min density overrides per exchange+marketType (storage key → USD).
     */
    private Map<String, BigDecimal> minDensityOverrides = new HashMap<>();

    /**
     * Min density overrides per symbol.
     */
    private Map<String, BigDecimal> symbolMinDensityOverrides = new HashMap<>();

    /**
     * Blacklisted symbols — hidden from densities view.
     */
    private Set<String> blacklistedSymbols = new HashSet<>();

    /**
     * Favorited symbols — shown first in densities view.
     */
    private Set<String> favoritedSymbols = new HashSet<>();

    /**
     * User comments per symbol.
     */
    private Map<String, String> symbolComments = new HashMap<>();

    /**
     * Sort order for densities.
     */
    private DensitySortType sortType = DensitySortType.SIZE_USD_DESC;

    /**
     * Min density overrides per symbol per exchange+marketType.
     * Key format: "SYMBOL_EXCHANGE_MARKETTYPE" (e.g., "BTCUSDT_BINANCE_SPOT").
     */
    private Map<String, BigDecimal> symbolMarketMinDensityOverrides = new HashMap<>();

    /**
     * Trading strategy mode: when true, additionally filters densities
     * where volumeUsd > volume15min.
     */
    private boolean tsMode = false;

    /**
     * How long (in minutes) a density is marked as NEW after first seen.
     * Frontend-only hint, not used by backend filtering.
     */
    private int newBadgeDurationMinutes = 5;

    public static Workspace createDefault() {
        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID().toString());
        ws.setName("Default");
        ws.setActive(true);
        ws.setSortType(DensitySortType.SIZE_USD_DESC);
        ws.setNewBadgeDurationMinutes(5);
        return ws;
    }
}
