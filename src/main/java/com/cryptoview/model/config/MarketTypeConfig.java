package com.cryptoview.model.config;

import com.cryptoview.model.enums.AlertType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;

@Data
public class MarketTypeConfig {
    private BigDecimal minDensityUsd;
    private Integer cooldownMinutes;
    private BigDecimal maxDistancePercent;
    private Set<AlertType> alertTypes;
    private Boolean enabled;
}
