package com.cryptoview.model.config;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;

public record ExchangeMarketKey(
        Exchange exchange,
        MarketType marketType
) {
    public String toStorageKey() {
        return exchange.name() + "_" + marketType.name();
    }

    public static ExchangeMarketKey fromStorageKey(String key) {
        String[] parts = key.split("_", 2);
        return new ExchangeMarketKey(
                Exchange.valueOf(parts[0]),
                MarketType.valueOf(parts[1])
        );
    }
}
