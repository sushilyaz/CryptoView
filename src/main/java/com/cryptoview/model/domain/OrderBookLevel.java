package com.cryptoview.model.domain;

import java.math.BigDecimal;

public record OrderBookLevel(
        BigDecimal price,
        BigDecimal quantity
) {
    public BigDecimal getVolumeUsd() {
        return price.multiply(quantity);
    }
}
