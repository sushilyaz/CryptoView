package com.cryptoview.exchange.lighter;

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

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public abstract class AbstractLighterConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://mainnet.zklighter.elliot.ai/stream?readonly=true";
    private static final String REST_BASE = "https://mainnet.zklighter.elliot.ai/api/v1";

    // market_id -> symbol (e.g., 0 -> "ETHUSDC", 1 -> "BTCUSDC")
    protected final Map<Integer, String> marketIdToSymbol = new ConcurrentHashMap<>();
    protected final Map<String, Integer> symbolToMarketId = new ConcurrentHashMap<>();

    protected AbstractLighterConnector(OkHttpClient httpClient,
                                        ObjectMapper objectMapper,
                                        OrderBookManager orderBookManager,
                                        VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public Exchange getExchange() {
        return Exchange.LIGHTER;
    }

    @Override
    protected String getWebSocketUrl() {
        return WS_URL;
    }

    @Override
    protected void onConnected() {
        log.info("[LIGHTER:{}] Connected, ready to subscribe", getMarketType());
    }

    protected abstract String getMarketFilter(); // "spot" or "perp"
    protected abstract String buildSymbolName(String baseSymbol);

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (symbols.isEmpty()) {
            log.warn("[LIGHTER:{}] No symbols found", getMarketType());
            return;
        }

        if (!connectAndWait(5000)) {
            log.error("[LIGHTER:{}] Failed to connect WebSocket", getMarketType());
            return;
        }

        for (String symbol : symbols) {
            subscribe(List.of(symbol));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("[LIGHTER:{}] Subscribed to {} symbols", getMarketType(), symbols.size());
    }

    private List<String> fetchAllSymbols() {
        Request request = new Request.Builder()
                .url(REST_BASE + "/orderBookDetails?filter=" + getMarketFilter())
                .build();

        try (Response response = executeWithRetry(request, 3, 10000)) {
            if (response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body().string());

                // perp markets are in "order_book_details", spot in "spot_order_book_details"
                String arrayField = "perp".equals(getMarketFilter())
                        ? "order_book_details" : "spot_order_book_details";
                JsonNode details = root.get(arrayField);
                if (details == null || !details.isArray()) {
                    // Fallback: try the other field
                    details = root.get("order_book_details");
                    if (details == null) details = root.get("spot_order_book_details");
                }

                List<String> symbols = new ArrayList<>();
                if (details != null && details.isArray()) {
                    for (JsonNode market : details) {
                        String baseSymbol = market.has("symbol") ? market.get("symbol").asText() : null;
                        int marketId = market.has("market_id") ? market.get("market_id").asInt() : -1;
                        String marketType = market.has("market_type") ? market.get("market_type").asText() : "";
                        String status = market.has("status") ? market.get("status").asText() : "active";

                        if (baseSymbol == null || marketId < 0 || !"active".equals(status)) continue;
                        if (!getMarketFilter().equals(marketType)) continue;

                        String symbol = buildSymbolName(baseSymbol);
                        marketIdToSymbol.put(marketId, symbol);
                        symbolToMarketId.put(symbol, marketId);
                        symbols.add(symbol);
                    }
                }

                log.info("[LIGHTER:{}] Found {} trading pairs", getMarketType(), symbols.size());
                return symbols;
            }
        } catch (IOException e) {
            log.error("[LIGHTER:{}] Failed to fetch symbols: {}", getMarketType(), e.getMessage());
        }

        return List.of();
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        // Not used directly — subscribe() is overridden
        return null;
    }

    @Override
    public void subscribe(List<String> symbols) {
        for (String symbol : symbols) {
            Integer marketId = symbolToMarketId.get(symbol);
            if (marketId == null) continue;

            // Order book subscription
            send(String.format("{\"type\":\"subscribe\",\"channel\":\"order_book/%d\"}", marketId));
            // Trade subscription
            send(String.format("{\"type\":\"subscribe\",\"channel\":\"trade/%d\"}", marketId));

            subscribedSymbols.add(symbol);
        }
    }

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;

        // Lighter sends channel info in the message
        String channel = root.has("channel") ? root.get("channel").asText() : null;
        if (channel == null) return;

        if (channel.startsWith("order_book/")) {
            handleOrderBookUpdate(root, channel);
        } else if (channel.startsWith("trade/")) {
            handleTradeUpdate(root, channel);
        }
    }

    private void handleOrderBookUpdate(JsonNode root, String channel) {
        int marketId = parseMarketIdFromChannel(channel);
        String symbol = marketIdToSymbol.get(marketId);
        if (symbol == null) return;

        JsonNode asks = root.get("asks");
        JsonNode bids = root.get("bids");

        List<OrderBookLevel> bidLevels = parseOrderBookLevels(bids);
        List<OrderBookLevel> askLevels = parseOrderBookLevels(asks);

        if (!bidLevels.isEmpty() || !askLevels.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.LIGHTER,
                    getMarketType(),
                    bidLevels,
                    askLevels,
                    null
            );
        }
    }

    private void handleTradeUpdate(JsonNode root, String channel) {
        int marketId = parseMarketIdFromChannel(channel);
        String symbol = marketIdToSymbol.get(marketId);
        if (symbol == null) return;

        // Lighter trade can be a single object or part of initial data
        JsonNode trades = root.has("trades") ? root.get("trades") : null;
        if (trades != null && trades.isArray()) {
            for (JsonNode trade : trades) {
                processTrade(trade, symbol);
            }
        } else {
            // Single trade fields directly on root
            if (root.has("price") && root.has("size")) {
                processTrade(root, symbol);
            }
        }
    }

    private void processTrade(JsonNode trade, String symbol) {
        try {
            BigDecimal price = new BigDecimal(trade.get("price").asText());
            BigDecimal size = new BigDecimal(
                    trade.has("size") ? trade.get("size").asText() :
                            (trade.has("usd_amount") ? trade.get("usd_amount").asText() : "0")
            );

            if (price.compareTo(BigDecimal.ZERO) > 0) {
                incrementTradeUpdates();
                orderBookManager.updateLastPrice(symbol, Exchange.LIGHTER, getMarketType(), price);
                volumeTracker.addVolume(symbol, Exchange.LIGHTER, getMarketType(), price.multiply(size));
            }
        } catch (Exception e) {
            // Ignore malformed trades
        }
    }

    private List<OrderBookLevel> parseOrderBookLevels(JsonNode levels) {
        List<OrderBookLevel> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) return result;

        for (JsonNode level : levels) {
            try {
                BigDecimal price = new BigDecimal(level.get("price").asText());
                // size or remaining_base_amount
                String sizeStr = level.has("size") ? level.get("size").asText() :
                        (level.has("remaining_base_amount") ? level.get("remaining_base_amount").asText() : "0");
                BigDecimal quantity = new BigDecimal(sizeStr);

                if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                    result.add(new OrderBookLevel(price, quantity));
                }
            } catch (Exception e) {
                // Skip malformed levels
            }
        }
        return result;
    }

    private int parseMarketIdFromChannel(String channel) {
        try {
            String[] parts = channel.split("/");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    protected String getPingMessage() {
        return null; // OkHttp handles WebSocket ping/pong automatically
    }

    @Override
    protected long getPingIntervalMs() {
        return 30000;
    }
}
