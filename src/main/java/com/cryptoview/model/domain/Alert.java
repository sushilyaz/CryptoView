package com.cryptoview.model.domain;

import com.cryptoview.model.enums.AlertType;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.model.enums.Side;

import java.math.BigDecimal;
import java.time.Instant;

public record Alert(
        String symbol,
        Exchange exchange,
        MarketType marketType,
        AlertType alertType,
        Side side,
        BigDecimal price,
        BigDecimal volumeUsd,
        BigDecimal distancePercent,
        BigDecimal volume15min,
        String comment,
        Instant timestamp
) {
    public String getUniqueKey() {
        return String.format("%s_%s_%s_%s_%s_%s",
                exchange, marketType, symbol, alertType, side, price.toPlainString());
    }
}
