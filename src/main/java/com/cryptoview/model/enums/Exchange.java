package com.cryptoview.model.enums;

public enum Exchange {
    BINANCE("Binance"),
    BYBIT("Bybit"),
    OKX("OKX"),
    BITGET("Bitget"),
    GATE("Gate"),
    MEXC("MEXC"),
    HYPERLIQUID("Hyperliquid"),
    LIGHTER("Lighter");

    private final String displayName;

    Exchange(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
