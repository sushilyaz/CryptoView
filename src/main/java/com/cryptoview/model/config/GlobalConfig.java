package com.cryptoview.model.config;

import com.cryptoview.model.enums.AlertType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;

@Data
public class GlobalConfig {
    private BigDecimal minDensityUsd = new BigDecimal("100000");
    private int cooldownMinutes = 5;
    private BigDecimal maxDistancePercent = new BigDecimal("10.0");
    private Set<AlertType> alertTypes = Set.of(AlertType.VOLUME_BASED, AlertType.STATISTICAL);
    private boolean enabled = true;
}
