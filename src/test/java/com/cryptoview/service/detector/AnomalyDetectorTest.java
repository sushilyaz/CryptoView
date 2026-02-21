package com.cryptoview.service.detector;

import com.cryptoview.config.CryptoViewProperties;
import com.cryptoview.event.DensityDetectedEvent;
import com.cryptoview.model.config.EffectiveConfig;
import com.cryptoview.model.config.GlobalConfig;
import com.cryptoview.model.domain.OrderBook;
import com.cryptoview.model.domain.OrderBookLevel;
import com.cryptoview.model.enums.AlertType;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.service.config.ConfigService;
import com.cryptoview.service.volume.VolumeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectorTest {

    @Mock
    private ConfigService configService;

    @Mock
    private VolumeTracker volumeTracker;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AnomalyDetector anomalyDetector;

    @BeforeEach
    void setUp() {
        anomalyDetector = new AnomalyDetector(configService, volumeTracker, eventPublisher);
    }

    @Test
    void shouldDetectVolumeBasedAnomaly() {
        // Given
        EffectiveConfig config = EffectiveConfig.builder()
                .minDensityUsd(new BigDecimal("100000"))
                .cooldownMinutes(5)
                .maxDistancePercent(new BigDecimal("10"))
                .alertTypes(Set.of(AlertType.VOLUME_BASED))
                .enabled(true)
                .build();

        when(configService.getEffectiveConfig(any(), any(), any())).thenReturn(config);
        when(volumeTracker.getVolume15Min(any(), any(), any())).thenReturn(new BigDecimal("500000"));
        when(volumeTracker.getTrackingAgeSec(any(), any(), any())).thenReturn(600L);

        // Create orderbook with anomalous density
        List<OrderBookLevel> bids = new ArrayList<>();
        bids.add(new OrderBookLevel(new BigDecimal("50000"), new BigDecimal("20"))); // $1,000,000 > $500,000 volume

        List<OrderBookLevel> asks = new ArrayList<>();
        asks.add(new OrderBookLevel(new BigDecimal("50100"), new BigDecimal("1")));

        OrderBook orderBook = new OrderBook(
                "BTCUSDT",
                Exchange.BINANCE,
                MarketType.FUTURES,
                bids,
                asks,
                new BigDecimal("50050"),
                Instant.now()
        );

        // When
        anomalyDetector.analyzeOrderBook(orderBook);

        // Then
        ArgumentCaptor<DensityDetectedEvent> captor = ArgumentCaptor.forClass(DensityDetectedEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());

        DensityDetectedEvent event = captor.getValue();
        assertEquals(AlertType.VOLUME_BASED, event.getAlertType());
        assertEquals("BTCUSDT", event.getDensity().symbol());
    }

    @Test
    void shouldNotDetectWhenBelowMinDensity() {
        // Given
        EffectiveConfig config = EffectiveConfig.builder()
                .minDensityUsd(new BigDecimal("1000000")) // High threshold
                .cooldownMinutes(5)
                .maxDistancePercent(new BigDecimal("10"))
                .alertTypes(Set.of(AlertType.VOLUME_BASED))
                .enabled(true)
                .build();

        when(configService.getEffectiveConfig(any(), any(), any())).thenReturn(config);

        // Small density
        List<OrderBookLevel> bids = new ArrayList<>();
        bids.add(new OrderBookLevel(new BigDecimal("50000"), new BigDecimal("1"))); // $50,000 < $1,000,000

        OrderBook orderBook = new OrderBook(
                "BTCUSDT",
                Exchange.BINANCE,
                MarketType.FUTURES,
                bids,
                List.of(),
                new BigDecimal("50050"),
                Instant.now()
        );

        // When
        anomalyDetector.analyzeOrderBook(orderBook);

        // Then
        verify(eventPublisher, never()).publishEvent(any(DensityDetectedEvent.class));
    }

    @Test
    void shouldNotDetectWhenDisabled() {
        // Given
        EffectiveConfig config = EffectiveConfig.builder()
                .minDensityUsd(new BigDecimal("100000"))
                .cooldownMinutes(5)
                .maxDistancePercent(new BigDecimal("10"))
                .alertTypes(Set.of(AlertType.VOLUME_BASED))
                .enabled(false) // Disabled
                .build();

        when(configService.getEffectiveConfig(any(), any(), any())).thenReturn(config);

        List<OrderBookLevel> bids = new ArrayList<>();
        bids.add(new OrderBookLevel(new BigDecimal("50000"), new BigDecimal("100")));

        OrderBook orderBook = new OrderBook(
                "BTCUSDT",
                Exchange.BINANCE,
                MarketType.FUTURES,
                bids,
                List.of(),
                new BigDecimal("50050"),
                Instant.now()
        );

        // When
        anomalyDetector.analyzeOrderBook(orderBook);

        // Then
        verify(eventPublisher, never()).publishEvent(any(DensityDetectedEvent.class));
    }
}
