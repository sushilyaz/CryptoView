package com.cryptoview.model.domain;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.model.enums.Side;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(
        String symbol,
        Exchange exchange,
        MarketType marketType,
        BigDecimal price,
        BigDecimal quantity,
        Side side,
        Instant timestamp
) {
    public BigDecimal getVolumeUsd() {
        return price.multiply(quantity);
    }
}
