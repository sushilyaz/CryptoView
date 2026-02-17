package com.cryptoview.model.config;

import com.cryptoview.model.enums.AlertType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;

@Data
public class ExchangeConfig {
    private BigDecimal minDensityUsd;
    private Integer cooldownMinutes;
    private BigDecimal maxDistancePercent;
    private Set<AlertType> alertTypes;
    private Boolean enabled;

    private MarketTypeConfig spot;
    private MarketTypeConfig futures;
}
