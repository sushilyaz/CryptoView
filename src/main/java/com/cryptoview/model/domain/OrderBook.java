package com.cryptoview.model.domain;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

public record OrderBook(
        String symbol,
        Exchange exchange,
        MarketType marketType,
        List<OrderBookLevel> bids,
        List<OrderBookLevel> asks,
        BigDecimal lastPrice,
        Instant timestamp
) {
    public BigDecimal getBestBid() {
        return bids.isEmpty() ? BigDecimal.ZERO : bids.getFirst().price();
    }

    public BigDecimal getBestAsk() {
        return asks.isEmpty() ? BigDecimal.ZERO : asks.getFirst().price();
    }

    public BigDecimal getMidPrice() {
        if (bids.isEmpty() || asks.isEmpty()) {
            return lastPrice != null ? lastPrice : BigDecimal.ZERO;
        }
        return getBestBid().add(getBestAsk()).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
    }
}
