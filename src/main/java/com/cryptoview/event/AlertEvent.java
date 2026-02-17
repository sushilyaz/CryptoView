package com.cryptoview.event;

import com.cryptoview.model.domain.Alert;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class AlertEvent extends ApplicationEvent {

    private final Alert alert;

    public AlertEvent(Object source, Alert alert) {
        super(source);
        this.alert = alert;
    }
}
