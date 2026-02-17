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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Lighter DEX connector.
 * Note: Lighter is a newer DEX, API may need adjustments based on actual documentation.
 * This implementation is based on typical DEX WebSocket patterns.
 */
@Slf4j
@Component
public class LighterConnector extends AbstractWebSocketConnector {

    // Lighter WebSocket URL - needs to be verified with actual documentation
    private static final String WS_URL = "wss://api.lighter.xyz/ws";
    private static final String REST_URL = "https://api.lighter.xyz/markets";

    private final MarketType marketType;

    public LighterConnector(OkHttpClient httpClient,
                             ObjectMapper objectMapper,
                             OrderBookManager orderBookManager,
                             VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
        this.marketType = MarketType.SPOT;
    }

    @Override
    public Exchange getExchange() {
        return Exchange.LIGHTER;
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
        log.info("[LIGHTER] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            for (String symbol : symbols) {
                subscribe(List.of(symbol));

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[LIGHTER] Subscribed to {} symbols", symbols.size());
        }
    }

    private List<String> fetchAllSymbols() {
        Request request = new Request.Builder()
                .url(REST_URL)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode markets = root.has("markets") ? root.get("markets") : root;

                List<String> symbols = new ArrayList<>();
                if (markets != null && markets.isArray()) {
                    for (JsonNode market : markets) {
                        String symbol = market.has("symbol") ? market.get("symbol").asText() :
                                (market.has("market_id") ? market.get("market_id").asText() : null);
                        if (symbol != null) {
                            symbols.add(symbol);
                        }
                    }
                }

                log.info("[LIGHTER] Found {} trading pairs", symbols.size());
                return symbols;
            }
        } catch (IOException e) {
            log.warn("[LIGHTER] Failed to fetch symbols (API may not be available): {}", e.getMessage());
        }

        // Return default symbols if API is unavailable
        return List.of("BTCUSDT", "ETHUSDT");
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        // Generic subscription format - may need adjustment
        return String.format(
                "{\"op\":\"subscribe\",\"channel\":\"orderbook\",\"market\":\"%s\"}",
                symbols.getFirst()
        );
    }

    @Override
    public void subscribe(List<String> symbols) {
        for (String symbol : symbols) {
            // Orderbook subscription
            String bookMsg = String.format(
                    "{\"op\":\"subscribe\",\"channel\":\"orderbook\",\"market\":\"%s\"}",
                    symbol
            );
            send(bookMsg);

            // Trades subscription
            String tradesMsg = String.format(
                    "{\"op\":\"subscribe\",\"channel\":\"trades\",\"market\":\"%s\"}",
                    symbol
            );
            send(tradesMsg);

            subscribedSymbols.add(symbol);
        }
    }

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;

        String channel = root.has("channel") ? root.get("channel").asText() :
                (root.has("type") ? root.get("type").asText() : null);
        JsonNode data = root.has("data") ? root.get("data") : root;

        if (channel == null) {
            return;
        }

        if (channel.contains("orderbook") || channel.contains("book")) {
            handleOrderBook(data);
        } else if (channel.contains("trade")) {
            handleTrades(data);
        }
    }

    private void handleOrderBook(JsonNode data) {
        String market = data.has("market") ? data.get("market").asText() :
                (data.has("symbol") ? data.get("symbol").asText() : null);

        if (market == null) return;

        List<OrderBookLevel> bids = parseOrderBookLevels(data.get("bids"));
        List<OrderBookLevel> asks = parseOrderBookLevels(data.get("asks"));

        String symbol = market.replace("-", "").replace("/", "");

        if (!bids.isEmpty() || !asks.isEmpty()) {
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.LIGHTER,
                    marketType,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTrades(JsonNode data) {
        if (data.isArray()) {
            for (JsonNode trade : data) {
                processSingleTrade(trade);
            }
        } else {
            processSingleTrade(data);
        }
    }

    private void processSingleTrade(JsonNode trade) {
        String market = trade.has("market") ? trade.get("market").asText() :
                (trade.has("symbol") ? trade.get("symbol").asText() : null);

        if (market == null) return;

        String symbol = market.replace("-", "").replace("/", "");

        BigDecimal price = new BigDecimal(
                trade.has("price") ? trade.get("price").asText() :
                        (trade.has("px") ? trade.get("px").asText() : "0")
        );
        BigDecimal quantity = new BigDecimal(
                trade.has("size") ? trade.get("size").asText() :
                        (trade.has("qty") ? trade.get("qty").asText() :
                                (trade.has("amount") ? trade.get("amount").asText() : "0"))
        );

        if (price.compareTo(BigDecimal.ZERO) > 0) {
            orderBookManager.updateLastPrice(symbol, Exchange.LIGHTER, marketType, price);

            volumeTracker.addVolume(
                    symbol,
                    Exchange.LIGHTER,
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

            if (level.isArray()) {
                price = new BigDecimal(level.get(0).asText());
                quantity = new BigDecimal(level.get(1).asText());
            } else {
                price = new BigDecimal(
                        level.has("price") ? level.get("price").asText() :
                                (level.has("px") ? level.get("px").asText() : "0")
                );
                quantity = new BigDecimal(
                        level.has("size") ? level.get("size").asText() :
                                (level.has("qty") ? level.get("qty").asText() :
                                        (level.has("amount") ? level.get("amount").asText() : "0"))
                );
            }

            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new OrderBookLevel(price, quantity));
            }
        }

        return result;
    }

    @Override
    protected String getPingMessage() {
        return "{\"op\":\"ping\"}";
    }

    @Override
    protected long getPingIntervalMs() {
        return 30000;
    }
}
