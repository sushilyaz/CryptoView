package com.cryptoview.service.density;

import com.cryptoview.event.OrderBookUpdateEvent;
import com.cryptoview.model.domain.OrderBook;
import com.cryptoview.model.domain.OrderBookLevel;
import com.cryptoview.model.domain.TrackedDensity;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.model.enums.Side;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DensityTracker {

    private static final BigDecimal TRACKING_FLOOR = new BigDecimal("50000");
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);

    private final ConcurrentHashMap<String, TrackedDensity> activeDensities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> densitiesByOrderBookKey = new ConcurrentHashMap<>();

    @EventListener
    public void onOrderBookUpdate(OrderBookUpdateEvent event) {
        OrderBook ob = event.getOrderBook();
        String obKey = buildOrderBookKey(ob.exchange(), ob.marketType(), ob.symbol());
        BigDecimal lastPrice = ob.lastPrice();

        Set<String> currentKeys = ConcurrentHashMap.newKeySet();

        processLevels(ob.bids(), Side.BID, ob.symbol(), ob.exchange(), ob.marketType(), lastPrice, currentKeys);
        processLevels(ob.asks(), Side.ASK, ob.symbol(), ob.exchange(), ob.marketType(), lastPrice, currentKeys);

        Set<String> previousKeys = densitiesByOrderBookKey.put(obKey, currentKeys);
        if (previousKeys != null) {
            for (String prevKey : previousKeys) {
                if (!currentKeys.contains(prevKey)) {
                    activeDensities.remove(prevKey);
                }
            }
        }
    }

    private void processLevels(List<OrderBookLevel> levels, Side side, String symbol,
                                Exchange exchange, MarketType marketType,
                                BigDecimal lastPrice, Set<String> currentKeys) {
        if (levels == null || lastPrice == null || lastPrice.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        Instant now = Instant.now();

        for (OrderBookLevel level : levels) {
            BigDecimal volumeUsd = level.getVolumeUsd();
            if (volumeUsd.compareTo(TRACKING_FLOOR) < 0) {
                continue;
            }

            BigDecimal distancePercent = level.price().subtract(lastPrice)
                    .divide(lastPrice, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .abs();

            String trackingKey = buildTrackingKey(exchange, marketType, symbol, side, level.price());
            currentKeys.add(trackingKey);

            activeDensities.compute(trackingKey, (key, existing) -> {
                if (existing != null) {
                    return existing.withUpdated(level.quantity(), volumeUsd, distancePercent, lastPrice, now);
                }
                return new TrackedDensity(
                        symbol, exchange, marketType, side,
                        level.price(), level.quantity(), volumeUsd,
                        distancePercent, lastPrice,
                        now, now
                );
            });
        }
    }

    @Scheduled(fixedRate = 10000)
    public void cleanupStale() {
        Instant cutoff = Instant.now().minus(STALE_THRESHOLD);
        int removed = 0;

        Iterator<Map.Entry<String, TrackedDensity>> it = activeDensities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TrackedDensity> entry = it.next();
            if (entry.getValue().lastSeenAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("Cleaned up {} stale tracked densities", removed);
        }
    }

    public Collection<TrackedDensity> getAllActiveDensities() {
        return Collections.unmodifiableCollection(activeDensities.values());
    }

    public int getTrackedCount() {
        return activeDensities.size();
    }

    private String buildTrackingKey(Exchange exchange, MarketType marketType,
                                     String symbol, Side side, BigDecimal price) {
        return String.format("%s_%s_%s_%s_%s",
                exchange, marketType, symbol, side, price.toPlainString());
    }

    private String buildOrderBookKey(Exchange exchange, MarketType marketType, String symbol) {
        return String.format("%s_%s_%s", exchange, marketType, symbol);
    }
}
