package com.cryptoview.model.domain;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.model.enums.Side;

import java.math.BigDecimal;
import java.time.Instant;

public record Density(
        String symbol,
        Exchange exchange,
        MarketType marketType,
        Side side,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal volumeUsd,
        BigDecimal distancePercent,
        Instant detectedAt
) {
    public String getUniqueKey() {
        return String.format("%s_%s_%s_%s_%s",
                exchange, marketType, symbol, side, price.toPlainString());
    }
}
