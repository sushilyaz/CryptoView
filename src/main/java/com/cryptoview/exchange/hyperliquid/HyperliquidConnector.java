package com.cryptoview.exchange.hyperliquid;

import com.cryptoview.exchange.common.AbstractWebSocketConnector;
import com.cryptoview.model.domain.OrderBookLevel;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.service.orderbook.OrderBookManager;
import com.cryptoview.service.volume.VolumeTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class HyperliquidConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://api.hyperliquid.xyz/ws";
    private static final String REST_URL = "https://api.hyperliquid.xyz/info";

    private final MarketType marketType;

    public HyperliquidConnector(OkHttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 OrderBookManager orderBookManager,
                                 VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
        // Hyperliquid - это perpetual DEX, работает как futures
        this.marketType = MarketType.FUTURES;
    }

    @Override
    public Exchange getExchange() {
        return Exchange.HYPERLIQUID;
    }

    @Override
    public MarketType getMarketType() {
        return marketType;
    }

    @Override
    protected String getWebSocketUrl() {
        return WS_URL;
    }

    @Override
    protected void onConnected() {
        log.info("[HYPERLIQUID] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            if (!connectAndWait(5000)) {
                log.error("[HYPERLIQUID] Failed to connect WebSocket, aborting subscribe");
                return;
            }

            // Hyperliquid может иметь ограничения, подписываемся по одному
            for (String symbol : symbols) {
                subscribe(List.of(symbol));

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[HYPERLIQUID] Subscribed to {} symbols", symbols.size());
        }
    }

    private List<String> fetchAllSymbols() {
        Request request = new Request.Builder()
                .url(REST_URL)
                .post(okhttp3.RequestBody.create(
                        "{\"type\":\"meta\"}",
                        okhttp3.MediaType.get("application/json")
                ))
                .build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode universe = root.get("universe");

                List<String> symbols = new ArrayList<>();
                if (universe != null && universe.isArray()) {
                    for (JsonNode asset : universe) {
                        String name = asset.get("name").asText();
                        symbols.add(name);
                    }
                }

                log.info("[HYPERLIQUID] Found {} trading pairs", symbols.size());
                return symbols;
            }
        } catch (IOException e) {
            log.error("[HYPERLIQUID] Failed to fetch symbols after retries", e);
        }

        return List.of();
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        // Hyperliquid использует другой формат подписки
        StringBuilder sb = new StringBuilder();
        sb.append("{\"method\":\"subscribe\",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"");
        sb.append(symbols.getFirst());
        sb.append("\"}}");
        return sb.toString();
    }

    public void subscribeToTrades(String symbol) {
        String msg = String.format(
                "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"trades\",\"coin\":\"%s\"}}",
                symbol
        );
        send(msg);
    }

    @Override
    public void subscribe(List<String> symbols) {
        for (String symbol : symbols) {
            // L2 Book subscription
            String bookMsg = String.format(
                    "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"%s\"}}",
                    symbol
            );
            send(bookMsg);
            subscribedSymbols.add(symbol);

            // Trades subscription
            subscribeToTrades(symbol);
        }
    }

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;

        String channel = root.has("channel") ? root.get("channel").asText() : null;
        JsonNode data = root.get("data");

        if (data == null) {
            return;
        }

        if ("l2Book".equals(channel)) {
            handleOrderBook(data);
        } else if ("trades".equals(channel)) {
            handleTrades(data);
        }
    }

    private void handleOrderBook(JsonNode data) {
        String coin = data.has("coin") ? data.get("coin").asText() : null;
        if (coin == null) return;

        JsonNode levels = data.get("levels");
        if (levels == null || !levels.isArray() || levels.size() < 2) {
            return;
        }

        // levels[0] = bids, levels[1] = asks
        List<OrderBookLevel> bids = parseOrderBookLevels(levels.get(0));
        List<OrderBookLevel> asks = parseOrderBookLevels(levels.get(1));

        // Convert coin name to symbol format (e.g., BTC -> BTCUSDC)
        String symbol = coin + "USDC";

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.HYPERLIQUID,
                    marketType,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTrades(JsonNode data) {
        if (!data.isArray()) {
            return;
        }

        for (JsonNode trade : data) {
            String coin = trade.has("coin") ? trade.get("coin").asText() : null;
            if (coin == null) continue;

            String symbol = coin + "USDC";
            BigDecimal price = new BigDecimal(trade.get("px").asText());
            BigDecimal quantity = new BigDecimal(trade.get("sz").asText());

            incrementTradeUpdates();
            orderBookManager.updateLastPrice(symbol, Exchange.HYPERLIQUID, marketType, price);

            volumeTracker.addVolume(
                    symbol,
                    Exchange.HYPERLIQUID,
                    marketType,
                    price.multiply(quantity)
            );
        }
    }

    private List<OrderBookLevel> parseOrderBookLevels(JsonNode levels) {
        List<OrderBookLevel> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) {
            return result;
        }

        for (JsonNode level : levels) {
            BigDecimal price;
            BigDecimal quantity;

            if (level.has("px")) {
                price = new BigDecimal(level.get("px").asText());
                quantity = new BigDecimal(level.get("sz").asText());
            } else if (level.isArray()) {
                price = new BigDecimal(level.get(0).asText());
                quantity = new BigDecimal(level.get(1).asText());
            } else {
                continue;
            }

            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new OrderBookLevel(price, quantity));
            }
        }

        return result;
    }

    @Override
    protected String getPingMessage() {
        return "{\"method\":\"ping\"}";
    }

    @Override
    protected long getPingIntervalMs() {
        return 50000;
    }
}
