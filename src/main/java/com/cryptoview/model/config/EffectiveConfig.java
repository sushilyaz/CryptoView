package com.cryptoview.model.config;

import com.cryptoview.model.enums.AlertType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;

@Data
@Builder
public class EffectiveConfig {
    private BigDecimal minDensityUsd;
    private int cooldownMinutes;
    private BigDecimal maxDistancePercent;
    private Set<AlertType> alertTypes;
    private boolean enabled;
    private String comment;
}
