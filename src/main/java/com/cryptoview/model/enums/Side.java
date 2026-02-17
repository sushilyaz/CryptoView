package com.cryptoview.model.enums;

public enum Side {
    BID("Bid"),
    ASK("Ask");

    private final String displayName;

    Side(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
