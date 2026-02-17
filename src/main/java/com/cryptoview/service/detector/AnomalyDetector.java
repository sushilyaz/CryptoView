package com.cryptoview.service.detector;

import com.cryptoview.event.DensityDetectedEvent;
import com.cryptoview.event.OrderBookUpdateEvent;
import com.cryptoview.model.config.EffectiveConfig;
import com.cryptoview.model.domain.Density;
import com.cryptoview.model.domain.OrderBook;
import com.cryptoview.model.domain.OrderBookLevel;
import com.cryptoview.model.enums.AlertType;
import com.cryptoview.model.enums.Side;
import com.cryptoview.service.config.ConfigService;
import com.cryptoview.service.volume.VolumeTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetector {

    private final ConfigService configService;
    private final VolumeTracker volumeTracker;
    private final ApplicationEventPublisher eventPublisher;

    private static final double Z_SCORE_THRESHOLD = 3.0;
    private static final double IQR_MULTIPLIER = 3.0;

    // Дедупликация: ключ -> время последнего алерта
    private final Map<String, Instant> recentAlerts = new ConcurrentHashMap<>();

    @EventListener
    public void onOrderBookUpdate(OrderBookUpdateEvent event) {
        OrderBook orderBook = event.getOrderBook();
        analyzeOrderBook(orderBook);
    }

    public void analyzeOrderBook(OrderBook orderBook) {
        EffectiveConfig config = configService.getEffectiveConfig(
                orderBook.exchange(),
                orderBook.marketType(),
                orderBook.symbol()
        );

        if (!config.isEnabled()) {
            return;
        }

        log.debug("[{}:{}] Analyzing orderbook for {}: {} bids, {} asks",
                orderBook.exchange(), orderBook.marketType(), orderBook.symbol(),
                orderBook.bids().size(), orderBook.asks().size());

        Set<AlertType> alertTypes = config.getAlertTypes();
        BigDecimal minDensityUsd = config.getMinDensityUsd();
        BigDecimal volume15min = volumeTracker.getVolume15Min(
                orderBook.symbol(),
                orderBook.exchange(),
                orderBook.marketType()
        );

        // Собираем все уровни для статистического анализа
        List<BigDecimal> allVolumes = new ArrayList<>();
        for (OrderBookLevel level : orderBook.bids()) {
            allVolumes.add(level.getVolumeUsd());
        }
        for (OrderBookLevel level : orderBook.asks()) {
            allVolumes.add(level.getVolumeUsd());
        }

        StatisticalThresholds thresholds = calculateThresholds(allVolumes);

        // Анализируем bids
        for (OrderBookLevel level : orderBook.bids()) {
            analyzeLevel(orderBook, level, Side.BID, config, alertTypes, minDensityUsd, volume15min, thresholds);
        }

        // Анализируем asks
        for (OrderBookLevel level : orderBook.asks()) {
            analyzeLevel(orderBook, level, Side.ASK, config, alertTypes, minDensityUsd, volume15min, thresholds);
        }
    }

    private void analyzeLevel(OrderBook orderBook, OrderBookLevel level, Side side,
                               EffectiveConfig config, Set<AlertType> alertTypes,
                               BigDecimal minDensityUsd, BigDecimal volume15min,
                               StatisticalThresholds thresholds) {
        BigDecimal volumeUsd = level.getVolumeUsd();

        // Проверяем минимальный порог
        if (volumeUsd.compareTo(minDensityUsd) < 0) {
            return;
        }

        BigDecimal distancePercent = calculateDistancePercent(level.price(), orderBook.lastPrice());

        Density density = new Density(
                orderBook.symbol(),
                orderBook.exchange(),
                orderBook.marketType(),
                side,
                level.price(),
                level.quantity(),
                volumeUsd,
                distancePercent,
                Instant.now()
        );

        // Проверяем VOLUME_BASED
        if (alertTypes.contains(AlertType.VOLUME_BASED)) {
            if (volume15min.compareTo(BigDecimal.ZERO) > 0 && volumeUsd.compareTo(volume15min) > 0) {
                if (!isDuplicate(density, AlertType.VOLUME_BASED, config.getCooldownMinutes())) {
                    log.info("[{}:{}] VOLUME_BASED density: {} {} @ {} | vol={} > vol15m={}",
                            orderBook.exchange(), orderBook.marketType(),
                            orderBook.symbol(), side, level.price(), volumeUsd, volume15min);
                    eventPublisher.publishEvent(new DensityDetectedEvent(this, density, AlertType.VOLUME_BASED, volume15min));
                    markAsAlerted(density, AlertType.VOLUME_BASED);
                }
            }
        }

        // Проверяем STATISTICAL
        if (alertTypes.contains(AlertType.STATISTICAL)) {
            if (isStatisticalAnomaly(volumeUsd, thresholds)) {
                if (!isDuplicate(density, AlertType.STATISTICAL, config.getCooldownMinutes())) {
                    log.info("[{}:{}] STATISTICAL density: {} {} @ {} | vol={} > threshold={}",
                            orderBook.exchange(), orderBook.marketType(),
                            orderBook.symbol(), side, level.price(), volumeUsd, thresholds.threshold());
                    eventPublisher.publishEvent(new DensityDetectedEvent(this, density, AlertType.STATISTICAL, volume15min));
                    markAsAlerted(density, AlertType.STATISTICAL);
                }
            }
        }
    }

    private StatisticalThresholds calculateThresholds(List<BigDecimal> volumes) {
        if (volumes.size() < 10) {
            return new StatisticalThresholds(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(Double.MAX_VALUE));
        }

        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (BigDecimal vol : volumes) {
            stats.addValue(vol.doubleValue());
        }

        double mean = stats.getMean();
        double stdDev = stats.getStandardDeviation();
        double q1 = stats.getPercentile(25);
        double q3 = stats.getPercentile(75);
        double iqr = q3 - q1;

        // Z-score threshold
        double zScoreThreshold = mean + (Z_SCORE_THRESHOLD * stdDev);

        // IQR threshold
        double iqrThreshold = q3 + (IQR_MULTIPLIER * iqr);

        // Берём минимум из двух методов для более строгой фильтрации
        double threshold = Math.min(zScoreThreshold, iqrThreshold);

        return new StatisticalThresholds(
                BigDecimal.valueOf(mean),
                BigDecimal.valueOf(stdDev),
                BigDecimal.valueOf(threshold)
        );
    }

    private boolean isStatisticalAnomaly(BigDecimal volumeUsd, StatisticalThresholds thresholds) {
        return volumeUsd.compareTo(thresholds.threshold()) > 0;
    }

    private BigDecimal calculateDistancePercent(BigDecimal price, BigDecimal currentPrice) {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return price.subtract(currentPrice)
                .divide(currentPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private boolean isDuplicate(Density density, AlertType alertType, int cooldownMinutes) {
        String key = density.getUniqueKey() + "_" + alertType;
        Instant lastAlert = recentAlerts.get(key);

        if (lastAlert == null) {
            return false;
        }

        return Instant.now().isBefore(lastAlert.plusSeconds(cooldownMinutes * 60L));
    }

    private void markAsAlerted(Density density, AlertType alertType) {
        String key = density.getUniqueKey() + "_" + alertType;
        recentAlerts.put(key, Instant.now());
    }

    // Периодическая очистка старых записей
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 300000) // каждые 5 минут
    public void cleanupOldAlerts() {
        Instant cutoff = Instant.now().minusSeconds(30 * 60); // 30 минут
        recentAlerts.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    private record StatisticalThresholds(BigDecimal mean, BigDecimal stdDev, BigDecimal threshold) {}
}
