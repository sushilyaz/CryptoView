package com.cryptoview.service.orderbook;

import com.cryptoview.event.OrderBookUpdateEvent;
import com.cryptoview.model.domain.OrderBook;
import com.cryptoview.model.domain.OrderBookLevel;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookManager {

    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();
    private final java.util.Set<String> firstSeenLogged = ConcurrentHashMap.newKeySet();

    private static final BigDecimal MAX_DISTANCE_PERCENT = new BigDecimal("10");

    public void updateOrderBook(String symbol, Exchange exchange, MarketType marketType,
                                 List<OrderBookLevel> bids, List<OrderBookLevel> asks,
                                 BigDecimal lastPrice) {
        String key = buildKey(symbol, exchange, marketType);

        if (firstSeenLogged.add(key)) {
            log.info("[{}:{}] First orderbook received for {}", exchange, marketType, symbol);
        }

        if (lastPrice != null) {
            lastPrices.put(key, lastPrice);
        }

        BigDecimal currentPrice = lastPrices.getOrDefault(key, getMidPrice(bids, asks));

        List<OrderBookLevel> filteredBids = filterByDistance(bids, currentPrice, true);
        List<OrderBookLevel> filteredAsks = filterByDistance(asks, currentPrice, false);

        OrderBook orderBook = new OrderBook(
                symbol,
                exchange,
                marketType,
                filteredBids,
                filteredAsks,
                currentPrice,
                Instant.now()
        );

        orderBooks.put(key, orderBook);
        eventPublisher.publishEvent(new OrderBookUpdateEvent(this, orderBook));
    }

    public void updateLastPrice(String symbol, Exchange exchange, MarketType marketType, BigDecimal price) {
        String key = buildKey(symbol, exchange, marketType);
        lastPrices.put(key, price);
    }

    public Optional<OrderBook> getOrderBook(String symbol, Exchange exchange, MarketType marketType) {
        String key = buildKey(symbol, exchange, marketType);
        return Optional.ofNullable(orderBooks.get(key));
    }

    public BigDecimal getLastPrice(String symbol, Exchange exchange, MarketType marketType) {
        String key = buildKey(symbol, exchange, marketType);
        return lastPrices.get(key);
    }

    private List<OrderBookLevel> filterByDistance(List<OrderBookLevel> levels, BigDecimal currentPrice, boolean isBid) {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return levels;
        }

        List<OrderBookLevel> filtered = new ArrayList<>();
        for (OrderBookLevel level : levels) {
            BigDecimal distance = calculateDistancePercent(level.price(), currentPrice);
            if (distance.abs().compareTo(MAX_DISTANCE_PERCENT) <= 0) {
                filtered.add(level);
            }
        }
        return filtered;
    }

    private BigDecimal calculateDistancePercent(BigDecimal price, BigDecimal currentPrice) {
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return price.subtract(currentPrice)
                .divide(currentPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal getMidPrice(List<OrderBookLevel> bids, List<OrderBookLevel> asks) {
        if (bids.isEmpty() || asks.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return bids.getFirst().price().add(asks.getFirst().price())
                .divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
    }

    private String buildKey(String symbol, Exchange exchange, MarketType marketType) {
        return String.format("%s_%s_%s", exchange, marketType, symbol.toUpperCase());
    }

    public int getOrderBookCount() {
        return orderBooks.size();
    }
}
