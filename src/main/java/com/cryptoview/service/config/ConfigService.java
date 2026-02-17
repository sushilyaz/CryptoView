package com.cryptoview.service.config;

import com.cryptoview.config.CryptoViewProperties;
import com.cryptoview.model.config.*;
import com.cryptoview.model.enums.AlertType;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final CryptoViewProperties properties;
    private final ObjectMapper objectMapper;

    private final Map<String, SymbolConfig> symbolConfigs = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadSymbolConfigs();
    }

    private void loadSymbolConfigs() {
        File configFile = new File("symbols-config.json");
        if (configFile.exists()) {
            try {
                Map<String, Map<String, SymbolConfig>> config = objectMapper.readValue(
                        configFile,
                        new TypeReference<>() {}
                );
                Map<String, SymbolConfig> symbols = config.get("symbols");
                if (symbols != null) {
                    symbols.forEach((symbol, cfg) -> {
                        cfg.setSymbol(symbol);
                        symbolConfigs.put(symbol.toUpperCase(), cfg);
                    });
                    log.info("Loaded {} symbol configs", symbolConfigs.size());
                }
            } catch (IOException e) {
                log.warn("Failed to load symbols-config.json: {}", e.getMessage());
            }
        }
    }

    public EffectiveConfig getEffectiveConfig(Exchange exchange, MarketType marketType, String symbol) {
        GlobalConfig global = properties.getGlobal();
        ExchangeConfig exchangeConfig = properties.getExchanges().get(exchange);
        MarketTypeConfig marketTypeConfig = getMarketTypeConfig(exchangeConfig, marketType);
        SymbolConfig symbolConfig = symbolConfigs.get(symbol.toUpperCase());

        return EffectiveConfig.builder()
                .minDensityUsd(resolveMinDensityUsd(global, exchangeConfig, marketTypeConfig, symbolConfig))
                .cooldownMinutes(resolveCooldownMinutes(global, exchangeConfig, marketTypeConfig, symbolConfig))
                .maxDistancePercent(resolveMaxDistancePercent(global, exchangeConfig, marketTypeConfig, symbolConfig))
                .alertTypes(resolveAlertTypes(global, exchangeConfig, marketTypeConfig, symbolConfig))
                .enabled(resolveEnabled(global, exchangeConfig, marketTypeConfig, symbolConfig))
                .comment(symbolConfig != null ? symbolConfig.getComment() : null)
                .build();
    }

    private MarketTypeConfig getMarketTypeConfig(ExchangeConfig exchangeConfig, MarketType marketType) {
        if (exchangeConfig == null) return null;
        return marketType == MarketType.SPOT ? exchangeConfig.getSpot() : exchangeConfig.getFutures();
    }

    private BigDecimal resolveMinDensityUsd(GlobalConfig global, ExchangeConfig exchange,
                                             MarketTypeConfig marketType, SymbolConfig symbol) {
        if (symbol != null && symbol.getMinDensityUsd() != null) return symbol.getMinDensityUsd();
        if (marketType != null && marketType.getMinDensityUsd() != null) return marketType.getMinDensityUsd();
        if (exchange != null && exchange.getMinDensityUsd() != null) return exchange.getMinDensityUsd();
        return global.getMinDensityUsd();
    }

    private int resolveCooldownMinutes(GlobalConfig global, ExchangeConfig exchange,
                                        MarketTypeConfig marketType, SymbolConfig symbol) {
        if (symbol != null && symbol.getCooldownMinutes() != null) return symbol.getCooldownMinutes();
        if (marketType != null && marketType.getCooldownMinutes() != null) return marketType.getCooldownMinutes();
        if (exchange != null && exchange.getCooldownMinutes() != null) return exchange.getCooldownMinutes();
        return global.getCooldownMinutes();
    }

    private BigDecimal resolveMaxDistancePercent(GlobalConfig global, ExchangeConfig exchange,
                                                  MarketTypeConfig marketType, SymbolConfig symbol) {
        if (symbol != null && symbol.getMaxDistancePercent() != null) return symbol.getMaxDistancePercent();
        if (marketType != null && marketType.getMaxDistancePercent() != null) return marketType.getMaxDistancePercent();
        if (exchange != null && exchange.getMaxDistancePercent() != null) return exchange.getMaxDistancePercent();
        return global.getMaxDistancePercent();
    }

    private Set<AlertType> resolveAlertTypes(GlobalConfig global, ExchangeConfig exchange,
                                              MarketTypeConfig marketType, SymbolConfig symbol) {
        if (symbol != null && symbol.getAlertTypes() != null) return symbol.getAlertTypes();
        if (marketType != null && marketType.getAlertTypes() != null) return marketType.getAlertTypes();
        if (exchange != null && exchange.getAlertTypes() != null) return exchange.getAlertTypes();
        return global.getAlertTypes();
    }

    private boolean resolveEnabled(GlobalConfig global, ExchangeConfig exchange,
                                    MarketTypeConfig marketType, SymbolConfig symbol) {
        if (symbol != null && symbol.getEnabled() != null) return symbol.getEnabled();
        if (marketType != null && marketType.getEnabled() != null) return marketType.getEnabled();
        if (exchange != null && exchange.getEnabled() != null) return exchange.getEnabled();
        return global.isEnabled();
    }

    public SymbolConfig getSymbolConfig(String symbol) {
        return symbolConfigs.get(symbol.toUpperCase());
    }

    public void updateSymbolConfig(String symbol, SymbolConfig config) {
        config.setSymbol(symbol.toUpperCase());
        symbolConfigs.put(symbol.toUpperCase(), config);
        saveSymbolConfigs();
    }

    public String exportSymbolConfigs() {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("symbols", symbolConfigs));
        } catch (IOException e) {
            log.error("Failed to export symbol configs", e);
            return "{}";
        }
    }

    public void importSymbolConfigs(String json) throws IOException {
        Map<String, Map<String, SymbolConfig>> config = objectMapper.readValue(
                json,
                new TypeReference<>() {}
        );
        Map<String, SymbolConfig> symbols = config.get("symbols");
        if (symbols != null) {
            symbolConfigs.clear();
            symbols.forEach((symbol, cfg) -> {
                cfg.setSymbol(symbol);
                symbolConfigs.put(symbol.toUpperCase(), cfg);
            });
            saveSymbolConfigs();
            log.info("Imported {} symbol configs", symbolConfigs.size());
        }
    }

    private void saveSymbolConfigs() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File("symbols-config.json"), Map.of("symbols", symbolConfigs));
        } catch (IOException e) {
            log.error("Failed to save symbol configs", e);
        }
    }

    public boolean isExchangeMarketEnabled(Exchange exchange, MarketType marketType) {
        // MEXC Futures отключён
        if (exchange == Exchange.MEXC && marketType == MarketType.FUTURES) {
            return false;
        }

        ExchangeConfig exchangeConfig = properties.getExchanges().get(exchange);
        if (exchangeConfig != null && exchangeConfig.getEnabled() != null && !exchangeConfig.getEnabled()) {
            return false;
        }

        MarketTypeConfig marketTypeConfig = getMarketTypeConfig(exchangeConfig, marketType);
        if (marketTypeConfig != null && marketTypeConfig.getEnabled() != null) {
            return marketTypeConfig.getEnabled();
        }

        return properties.getGlobal().isEnabled();
    }
}
