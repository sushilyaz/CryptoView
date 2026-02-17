package com.cryptoview.event;

import com.cryptoview.model.domain.Density;
import com.cryptoview.model.enums.AlertType;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

@Getter
public class DensityDetectedEvent extends ApplicationEvent {

    private final Density density;
    private final AlertType alertType;
    private final BigDecimal volume15min;

    public DensityDetectedEvent(Object source, Density density, AlertType alertType, BigDecimal volume15min) {
        super(source);
        this.density = density;
        this.alertType = alertType;
        this.volume15min = volume15min;
    }
}
