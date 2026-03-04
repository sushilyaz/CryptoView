package com.cryptoview.service.density;

import com.cryptoview.model.config.ExchangeMarketKey;
import com.cryptoview.model.config.Workspace;
import com.cryptoview.model.domain.TrackedDensity;
import com.cryptoview.model.dto.DensityResponse;
import com.cryptoview.model.enums.DensitySortType;
import com.cryptoview.service.config.ConfigService;
import com.cryptoview.service.volume.VolumeTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DensityFilterService {

    private final DensityTracker densityTracker;
    private final ConfigService configService;
    private final VolumeTracker volumeTracker;

    public List<DensityResponse> filterDensities(Workspace ws, DensitySortType sortType, int limit) {
        return densityTracker.getAllActiveDensities().stream()
                .filter(d -> isMarketEnabled(ws, d))
                .filter(d -> !ws.getBlacklistedSymbols().contains(d.symbol().toUpperCase()))
                .filter(d -> meetsMinDensity(ws, d))
                .filter(d -> !ws.isTsMode() || meetsTsFilter(d))
                .sorted(getSorter(sortType))
                .limit(limit)
                .map(d -> toResponse(d, ws))
                .toList();
    }

    private boolean isMarketEnabled(Workspace ws, TrackedDensity d) {
        if (ws.getEnabledMarkets().isEmpty()) return true;
        String key = new ExchangeMarketKey(d.exchange(), d.marketType()).toStorageKey();
        return ws.getEnabledMarkets().contains(key);
    }

    private boolean meetsMinDensity(Workspace ws, TrackedDensity d) {
        BigDecimal minDensity = resolveMinDensity(ws, d);
        return d.volumeUsd().compareTo(minDensity) >= 0;
    }

    private boolean meetsTsFilter(TrackedDensity d) {
        BigDecimal vol15 = volumeTracker.getVolume15Min(d.symbol(), d.exchange(), d.marketType());
        return d.volumeUsd().compareTo(vol15) > 0;
    }

    private BigDecimal resolveMinDensity(Workspace ws, TrackedDensity d) {
        String symbolUpper = d.symbol().toUpperCase();
        String marketKey = new ExchangeMarketKey(d.exchange(), d.marketType()).toStorageKey();

        // 1. Most specific: symbol + market override
        String symbolMarketKey = symbolUpper + "_" + marketKey;
        BigDecimal smOverride = ws.getSymbolMarketMinDensityOverrides().get(symbolMarketKey);
        if (smOverride != null) return smOverride;

        // 2. Symbol-only override
        BigDecimal symbolOverride = ws.getSymbolMinDensityOverrides().get(symbolUpper);
        if (symbolOverride != null) return symbolOverride;

        // 3. Market-only override
        BigDecimal marketOverride = ws.getMinDensityOverrides().get(marketKey);
        if (marketOverride != null) return marketOverride;

        // 4. Global default
        return configService.getEffectiveConfig(d.exchange(), d.marketType(), d.symbol()).getMinDensityUsd();
    }

    private Comparator<TrackedDensity> getSorter(DensitySortType sortType) {
        return switch (sortType) {
            case DURATION_DESC -> Comparator.comparingLong(TrackedDensity::durationSeconds).reversed();
            case SIZE_USD_DESC -> Comparator.comparing(TrackedDensity::volumeUsd).reversed();
            case DISTANCE_ASC -> Comparator.comparing(TrackedDensity::distancePercent);
        };
    }

    private DensityResponse toResponse(TrackedDensity d, Workspace ws) {
        String comment = ws.getSymbolComments().get(d.symbol().toUpperCase());
        BigDecimal vol15 = volumeTracker.getVolume15Min(d.symbol(), d.exchange(), d.marketType());
        return new DensityResponse(
                d.symbol(), d.exchange(), d.marketType(), d.side(),
                d.price(), d.quantity(), d.volumeUsd(), d.distancePercent(),
                d.lastPrice(), d.firstSeenAt(), d.lastSeenAt(),
                d.durationSeconds(), comment, vol15
        );
    }
}
