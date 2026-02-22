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
     * User comments per symbol.
     */
    private Map<String, String> symbolComments = new HashMap<>();

    /**
     * Sort order for densities.
     */
    private DensitySortType sortType = DensitySortType.SIZE_USD_DESC;

    public static Workspace createDefault() {
        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID().toString());
        ws.setName("Default");
        ws.setActive(true);
        ws.setSortType(DensitySortType.SIZE_USD_DESC);
        return ws;
    }
}
