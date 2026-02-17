package com.cryptoview.model.config;

import com.cryptoview.model.enums.AlertType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SymbolConfig {
    private String symbol;
    private String comment;
    private BigDecimal minDensityUsd;
    private Integer cooldownMinutes;
    private BigDecimal maxDistancePercent;
    private Set<AlertType> alertTypes;
    private Boolean enabled;

    public boolean hasComment() {
        return comment != null && !comment.isBlank();
    }
}
