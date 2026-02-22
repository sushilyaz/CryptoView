package com.cryptoview.model.dto;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.model.enums.Side;

import java.math.BigDecimal;
import java.time.Instant;

public record DensityResponse(
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
        Instant lastSeenAt,
        long durationSeconds,
        String comment
) {
}
