package com.cryptoview.service.volume;

import com.cryptoview.event.TradeEvent;
import com.cryptoview.model.domain.Trade;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Service
public class VolumeTracker {

    private static final int WINDOW_MINUTES = 15;

    private final Map<String, Queue<VolumeEntry>> volumeHistory = new ConcurrentHashMap<>();

    private record VolumeEntry(BigDecimal volumeUsd, Instant timestamp) {}

    @EventListener
    public void onTrade(TradeEvent event) {
        Trade trade = event.getTrade();
        addVolume(
                trade.symbol(),
                trade.exchange(),
                trade.marketType(),
                trade.getVolumeUsd()
        );
    }

    public void addVolume(String symbol, Exchange exchange, MarketType marketType, BigDecimal volumeUsd) {
        String key = buildKey(symbol, exchange, marketType);
        volumeHistory.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>())
                .add(new VolumeEntry(volumeUsd, Instant.now()));
    }

    /**
     * Возвращает сколько секунд мы трекаем данные для символа.
     * 0 — если данных нет.
     */
    public long getTrackingAgeSec(String symbol, Exchange exchange, MarketType marketType) {
        String key = buildKey(symbol, exchange, marketType);
        Queue<VolumeEntry> history = volumeHistory.get(key);
        if (history == null || history.isEmpty()) {
            return 0;
        }
        VolumeEntry oldest = history.peek();
        if (oldest == null) {
            return 0;
        }
        return java.time.Duration.between(oldest.timestamp(), Instant.now()).toSeconds();
    }

    public BigDecimal getVolume15Min(String symbol, Exchange exchange, MarketType marketType) {
        String key = buildKey(symbol, exchange, marketType);
        Queue<VolumeEntry> history = volumeHistory.get(key);

        if (history == null || history.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Instant cutoff = Instant.now().minus(WINDOW_MINUTES, ChronoUnit.MINUTES);
        BigDecimal total = BigDecimal.ZERO;

        for (VolumeEntry entry : history) {
            if (entry.timestamp().isAfter(cutoff)) {
                total = total.add(entry.volumeUsd());
            }
        }

        return total;
    }

    @Scheduled(fixedRate = 60000) // каждую минуту
    public void cleanupOldEntries() {
        Instant cutoff = Instant.now().minus(WINDOW_MINUTES + 1, ChronoUnit.MINUTES);
        int removed = 0;

        for (Queue<VolumeEntry> history : volumeHistory.values()) {
            Iterator<VolumeEntry> iterator = history.iterator();
            while (iterator.hasNext()) {
                VolumeEntry entry = iterator.next();
                if (entry.timestamp().isBefore(cutoff)) {
                    iterator.remove();
                    removed++;
                } else {
                    break; // очередь упорядочена по времени
                }
            }
        }

        log.debug("VolumeTracker cleanup: removed {} old entries, tracking {} symbols",
                removed, volumeHistory.size());
    }

    private String buildKey(String symbol, Exchange exchange, MarketType marketType) {
        return String.format("%s_%s_%s", exchange, marketType, symbol.toUpperCase());
    }

    public int getTrackedSymbolsCount() {
        return volumeHistory.size();
    }
}
