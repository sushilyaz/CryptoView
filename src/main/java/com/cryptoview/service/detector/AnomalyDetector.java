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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.EnumSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetector {

    private final ConfigService configService;
    private final VolumeTracker volumeTracker;
    private final ApplicationEventPublisher eventPublisher;

    private static final double Z_SCORE_THRESHOLD = 3.0;
    private static final double IQR_MULTIPLIER = 3.0;
    private static final long MIN_VOLUME_TRACKING_SEC = 300; // 5 минут минимум для VOLUME_BASED

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

        // VOLUME_BASED доступен только если собрано >= 5 минут данных
        long trackingAge = volumeTracker.getTrackingAgeSec(
                orderBook.symbol(), orderBook.exchange(), orderBook.marketType());
        boolean volumeBasedReady = trackingAge >= MIN_VOLUME_TRACKING_SEC;

        Set<AlertType> activeAlertTypes = EnumSet.noneOf(AlertType.class);
        if (alertTypes.contains(AlertType.STATISTICAL)) {
            activeAlertTypes.add(AlertType.STATISTICAL);
        }
        if (alertTypes.contains(AlertType.VOLUME_BASED) && volumeBasedReady) {
            activeAlertTypes.add(AlertType.VOLUME_BASED);
        }

        if (activeAlertTypes.isEmpty()) {
            return;
        }

        // Собираем все уровни для статистического анализа
        List<BigDecimal> allVolumes = new ArrayList<>();
        for (OrderBookLevel level : orderBook.bids()) {
            allVolumes.add(level.getVolumeUsd());
        }
        for (OrderBookLevel level : orderBook.asks()) {
            allVolumes.add(level.getVolumeUsd());
        }

        StatisticalThresholds thresholds = calculateThresholds(allVolumes);

        // Собираем лучших кандидатов для каждой комбинации side+alertType
        Map<String, Density> bestCandidates = new HashMap<>();

        // Анализируем bids
        for (OrderBookLevel level : orderBook.bids()) {
            collectCandidates(orderBook, level, Side.BID, activeAlertTypes, minDensityUsd, volume15min, thresholds, bestCandidates);
        }

        // Анализируем asks
        for (OrderBookLevel level : orderBook.asks()) {
            collectCandidates(orderBook, level, Side.ASK, activeAlertTypes, minDensityUsd, volume15min, thresholds, bestCandidates);
        }

        // Публикуем только лучших кандидатов (1 алерт на side+alertType)
        int cooldownMinutes = config.getCooldownMinutes();
        for (Map.Entry<String, Density> entry : bestCandidates.entrySet()) {
            String candidateKey = entry.getKey(); // "BID_VOLUME_BASED"
            Density density = entry.getValue();
            AlertType alertType = candidateKey.endsWith("_VOLUME_BASED") ? AlertType.VOLUME_BASED : AlertType.STATISTICAL;

            if (!isDuplicate(density, alertType, cooldownMinutes)) {
                log.info("[{}:{}] {} density: {} {} @ {} | vol=${}",
                        orderBook.exchange(), orderBook.marketType(),
                        alertType, orderBook.symbol(), density.side(), density.price(), density.volumeUsd());
                eventPublisher.publishEvent(new DensityDetectedEvent(this, density, alertType, volume15min));
                markAsAlerted(density, alertType);
            }
        }
    }

    private void collectCandidates(OrderBook orderBook, OrderBookLevel level, Side side,
                                    Set<AlertType> alertTypes, BigDecimal minDensityUsd,
                                    BigDecimal volume15min, StatisticalThresholds thresholds,
                                    Map<String, Density> bestCandidates) {
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
                String key = side + "_VOLUME_BASED";
                Density current = bestCandidates.get(key);
                if (current == null || volumeUsd.compareTo(current.volumeUsd()) > 0) {
                    bestCandidates.put(key, density);
                }
            }
        }

        // Проверяем STATISTICAL
        if (alertTypes.contains(AlertType.STATISTICAL)) {
            if (isStatisticalAnomaly(volumeUsd, thresholds)) {
                String key = side + "_STATISTICAL";
                Density current = bestCandidates.get(key);
                if (current == null || volumeUsd.compareTo(current.volumeUsd()) > 0) {
                    bestCandidates.put(key, density);
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

    private String deduplicationKey(Density density, AlertType alertType) {
        // Группируем по символу+бирже+рынку+стороне (без price), чтобы не спамить на каждый уровень
        return String.format("%s_%s_%s_%s_%s",
                density.exchange(), density.marketType(), density.symbol(), density.side(), alertType);
    }

    private boolean isDuplicate(Density density, AlertType alertType, int cooldownMinutes) {
        String key = deduplicationKey(density, alertType);
        Instant lastAlert = recentAlerts.get(key);

        if (lastAlert == null) {
            return false;
        }

        return Instant.now().isBefore(lastAlert.plusSeconds(cooldownMinutes * 60L));
    }

    private void markAsAlerted(Density density, AlertType alertType) {
        String key = deduplicationKey(density, alertType);
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
