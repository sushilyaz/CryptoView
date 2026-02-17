package com.cryptoview.model.enums;

public enum MarketType {
    SPOT("Spot"),
    FUTURES("Futures");

    private final String displayName;

    MarketType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
