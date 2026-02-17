package com.cryptoview.model.enums;

public enum AlertType {
    VOLUME_BASED("По объёму 15м"),
    STATISTICAL("Статистический");

    private final String displayName;

    AlertType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
