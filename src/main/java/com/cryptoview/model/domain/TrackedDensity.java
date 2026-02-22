package com.cryptoview.model.domain;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.model.enums.Side;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public record TrackedDensity(
        String symbol,
        Exchange exchange,
        MarketType marketType,
        Side side,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal volumeUsd,
        BigDecimal distancePercent,
        BigDecimal lastPrice,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
    public String trackingKey() {
        return String.format("%s_%s_%s_%s_%s",
                exchange, marketType, symbol, side, price.toPlainString());
    }

    public long durationSeconds() {
        return Duration.between(firstSeenAt, lastSeenAt).getSeconds();
    }

    public TrackedDensity withUpdated(BigDecimal quantity, BigDecimal volumeUsd,
                                       BigDecimal distancePercent, BigDecimal lastPrice,
                                       Instant lastSeenAt) {
        return new TrackedDensity(
                this.symbol, this.exchange, this.marketType, this.side,
                this.price, quantity, volumeUsd, distancePercent,
                lastPrice, this.firstSeenAt, lastSeenAt
        );
    }
}
