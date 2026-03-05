package com.cryptoview.exchange;

import com.cryptoview.config.CryptoViewProperties;
import com.cryptoview.exchange.common.ExchangeConnector;
import com.cryptoview.model.config.ExchangeConfig;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.service.config.ConfigService;
import com.cryptoview.service.volume.VolumeTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeManager {

    private final List<ExchangeConnector> connectors;
    private final ConfigService configService;
    private final CryptoViewProperties properties;
    private final VolumeTracker volumeTracker;

    private final Map<String, ExchangeConnector> connectorMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PostConstruct
    public void init() {
        logStartupSummary();

        for (ExchangeConnector connector : connectors) {
            String key = buildKey(connector.getExchange(), connector.getMarketType());
            connectorMap.put(key, connector);

            if (configService.isExchangeMarketEnabled(connector.getExchange(), connector.getMarketType())) {
                log.info("Starting connector: {} {}", connector.getExchange(), connector.getMarketType());
                executor.submit(() -> {
                    try {
                        connector.subscribeAll();
                    } catch (Exception e) {
                        log.error("Failed to start connector {} {}",
                                connector.getExchange(), connector.getMarketType(), e);
                    }
                });
            } else {
                log.info("Connector disabled: {} {}", connector.getExchange(), connector.getMarketType());
            }
        }
    }

    private void logStartupSummary() {
        var global = properties.getGlobal();
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== CryptoView Startup Summary ===\n");
        sb.append(String.format("Global: minDensityUsd=%s, cooldown=%dmin, maxDistance=%.1f%%\n",
                global.getMinDensityUsd(), global.getCooldownMinutes(), global.getMaxDistancePercent().doubleValue()));

        for (ExchangeConnector connector : connectors) {
            boolean enabled = configService.isExchangeMarketEnabled(connector.getExchange(), connector.getMarketType());
            sb.append(String.format("  %s %s - %s\n",
                    connector.getExchange(), connector.getMarketType(),
                    enabled ? "ENABLED" : "DISABLED"));
        }

        sb.append("===================================");
        log.info(sb.toString());
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ExchangeManager");

        for (ExchangeConnector connector : connectors) {
            try {
                connector.disconnect();
            } catch (Exception e) {
                log.error("Error disconnecting {}", connector.getExchange(), e);
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public ExchangeConnector getConnector(Exchange exchange, MarketType marketType) {
        return connectorMap.get(buildKey(exchange, marketType));
    }

    public boolean isConnected(Exchange exchange, MarketType marketType) {
        ExchangeConnector connector = getConnector(exchange, marketType);
        return connector != null && connector.isConnected();
    }

    public int getTotalSubscribedSymbols() {
        return connectors.stream()
                .mapToInt(ExchangeConnector::getSubscribedSymbolsCount)
                .sum();
    }

    public int getConnectedExchangesCount() {
        return (int) connectors.stream()
                .filter(ExchangeConnector::isConnected)
                .count();
    }

    @Scheduled(fixedRate = 60000) // каждую минуту
    public void logStatus() {
        int connectedCount = getConnectedExchangesCount();
        int total = connectors.size();
        int symbols = getTotalSubscribedSymbols();
        int trackedVolumes = volumeTracker.getTrackedSymbolsCount();

        log.info("=== Exchange Status: {}/{} connected, {} symbols, {} volumes ===",
                connectedCount, total, symbols, trackedVolumes);

        for (ExchangeConnector connector : connectors) {
            String status = connector.isConnected() ? "OK" : "DISCONNECTED";
            String stats = connector.getStatusSummary();
            if (connector.isConnected()) {
                log.info("  [{}] {} {} | {}", status,
                        connector.getExchange(), connector.getMarketType(), stats);
            } else {
                boolean enabled = configService.isExchangeMarketEnabled(
                        connector.getExchange(), connector.getMarketType());
                if (enabled) {
                    log.warn("  [{}] {} {} | {}", status,
                            connector.getExchange(), connector.getMarketType(), stats);
                }
            }
        }
    }

    /**
     * Возвращает список "EXCHANGE_MARKETTYPE" ключей, на которых данный символ подписан.
     * Проверяет все варианты: ETHUSDT, ETHUSDC, ETH (Hyperliquid).
     */
    public List<String> getMarketsForSymbol(String baseTicker) {
        String upper = baseTicker.toUpperCase();
        List<String> suffixes = List.of("USDT", "USDC", "USD", "");
        List<String> result = new ArrayList<>();

        for (ExchangeConnector connector : connectors) {
            if (!connector.isConnected()) continue;
            var symbols = connector.getSubscribedSymbols();
            for (String suffix : suffixes) {
                String candidate = upper + suffix;
                if (candidate.equals(upper) && !suffix.isEmpty()) continue; // skip "ETH" + non-empty (already handled by "")
                if (symbols.contains(candidate) || symbols.contains(candidate.toLowerCase())) {
                    result.add(buildKey(connector.getExchange(), connector.getMarketType()));
                    break;
                }
            }
        }

        return result;
    }

    private String buildKey(Exchange exchange, MarketType marketType) {
        return exchange.name() + "_" + marketType.name();
    }
}
