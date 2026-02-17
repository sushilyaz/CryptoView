package com.cryptoview.service.alert;

import com.cryptoview.event.AlertEvent;
import com.cryptoview.event.DensityDetectedEvent;
import com.cryptoview.model.config.EffectiveConfig;
import com.cryptoview.model.domain.Alert;
import com.cryptoview.model.domain.Density;
import com.cryptoview.service.config.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onDensityDetected(DensityDetectedEvent event) {
        Density density = event.getDensity();

        EffectiveConfig config = configService.getEffectiveConfig(
                density.exchange(),
                density.marketType(),
                density.symbol()
        );

        Alert alert = new Alert(
                density.symbol(),
                density.exchange(),
                density.marketType(),
                event.getAlertType(),
                density.side(),
                density.price(),
                density.volumeUsd(),
                density.distancePercent(),
                event.getVolume15min(),
                config.getComment(),
                Instant.now()
        );

        log.info("New alert: {} {} {} {} @ {} - ${} ({})",
                alert.exchange(),
                alert.marketType(),
                alert.symbol(),
                alert.side(),
                alert.price(),
                alert.volumeUsd().toPlainString(),
                alert.alertType()
        );

        eventPublisher.publishEvent(new AlertEvent(this, alert));
    }
}
