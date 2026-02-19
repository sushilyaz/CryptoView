package com.cryptoview.exchange.mexc;

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
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MexcSpotConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://wbs.mexc.com/ws";
    private static final String REST_URL = "https://api.mexc.com/api/v3/exchangeInfo";

    public MexcSpotConnector(OkHttpClient httpClient,
                              ObjectMapper objectMapper,
                              OrderBookManager orderBookManager,
                              VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public Exchange getExchange() {
        return Exchange.MEXC;
    }

    @Override
    public MarketType getMarketType() {
        return MarketType.SPOT;
    }

    @Override
    protected String getWebSocketUrl() {
        return WS_URL;
    }

    @Override
    protected void onConnected() {
        log.info("[MEXC:SPOT] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchTopSymbols();
        if (!symbols.isEmpty()) {
            if (!connectAndWait(5000)) {
                log.error("[MEXC:SPOT] Failed to connect WebSocket, aborting subscribe");
                return;
            }

            // MEXC limit: 30 subscriptions per connection
            // Each symbol = 2 subs (depth + deals), so max 15 symbols
            subscribe(symbols);
            log.info("[MEXC:SPOT] Subscribed to {} symbols", symbols.size());
        }
    }

    // MEXC allows max 30 subscriptions per WS connection.
    // Each symbol = 2 subs (depth + deals), so we fetch top 15 symbols by 24h volume.
    private static final int MAX_SYMBOLS = 15;
    private static final String TICKER_URL = "https://api.mexc.com/api/v3/ticker/24hr";

    private List<String> fetchTopSymbols() {
        // First get valid USDT trading pairs
        List<String> validSymbols = fetchValidSymbols();
        if (validSymbols.isEmpty()) return List.of();

        // Then get 24h tickers to sort by volume
        Request tickerRequest = new Request.Builder()
                .url(TICKER_URL)
                .build();

        try (Response response = executeWithRetry(tickerRequest, 3, 5000)) {
            if (response.body() != null) {
                JsonNode tickers = objectMapper.readTree(response.body().string());
                Set<String> validSet = new java.util.HashSet<>(validSymbols);

                List<String> topSymbols = new ArrayList<>();
                // Collect symbols with volume
                List<java.util.Map.Entry<String, Double>> symbolVolumes = new ArrayList<>();
                for (JsonNode ticker : tickers) {
                    String symbol = ticker.get("symbol").asText();
                    if (validSet.contains(symbol) && ticker.has("quoteVolume")) {
                        double volume = ticker.get("quoteVolume").asDouble();
                        symbolVolumes.add(java.util.Map.entry(symbol, volume));
                    }
                }

                // Sort by volume descending, take top N
                symbolVolumes.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                for (int i = 0; i < Math.min(MAX_SYMBOLS, symbolVolumes.size()); i++) {
                    topSymbols.add(symbolVolumes.get(i).getKey());
                }

                log.info("[MEXC:SPOT] Selected top {} symbols by volume from {} total",
                        topSymbols.size(), validSymbols.size());
                return topSymbols;
            }
        } catch (IOException e) {
            log.error("[MEXC:SPOT] Failed to fetch tickers, falling back to first {} symbols", MAX_SYMBOLS, e);
        }

        // Fallback: just take first N
        return validSymbols.subList(0, Math.min(MAX_SYMBOLS, validSymbols.size()));
    }

    private List<String> fetchValidSymbols() {
        Request request = new Request.Builder()
                .url(REST_URL)
                .build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode symbols = root.get("symbols");

                List<String> usdtSymbols = new ArrayList<>();
                if (symbols != null) {
                    for (JsonNode symbol : symbols) {
                        String name = symbol.get("symbol").asText();
                        String status = symbol.get("status").asText();
                        String quoteAsset = symbol.get("quoteAsset").asText();
                        boolean isSpotTradingAllowed = symbol.has("isSpotTradingAllowed") &&
                                symbol.get("isSpotTradingAllowed").asBoolean();

                        if ("1".equals(status) && "USDT".equals(quoteAsset) && isSpotTradingAllowed) {
                            usdtSymbols.add(name);
                        }
                    }
                }

                log.info("[MEXC:SPOT] Found {} USDT trading pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[MEXC:SPOT] Failed to fetch symbols after retries", e);
        }

        return List.of();
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        List<String> params = new ArrayList<>();

        for (String symbol : symbols) {
            // MEXC uses lowercase symbols for subscriptions
            params.add(String.format("spot@public.limit.depth.v3.api@%s@20", symbol));
            params.add(String.format("spot@public.deals.v3.api@%s", symbol));
        }

        return String.format("{\"method\":\"SUBSCRIPTION\",\"params\":[%s]}",
                params.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")));
    }

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;

        // Log subscription responses
        if (root.has("msg")) {
            String msg = root.get("msg").asText();
            if (msg.contains("Not Subscribed")) {
                log.warn("[MEXC:SPOT] Subscription failed: {}", msg.length() > 200 ? msg.substring(0, 200) + "..." : msg);
            } else {
                log.debug("[MEXC:SPOT] Service message: {}", msg);
            }
            return;
        }
        if (root.has("code")) {
            return;
        }

        String channel = root.has("c") ? root.get("c").asText() : null;
        JsonNode data = root.get("d");

        if (channel == null || data == null) {
            return;
        }

        if (channel.contains("limit.depth")) {
            handleOrderBook(data, channel);
        } else if (channel.contains("deals")) {
            handleTrades(data, channel);
        }
    }

    private void handleOrderBook(JsonNode data, String channel) {
        // Extract symbol from channel: spot@public.limit.depth.v3.api@BTCUSDT@20
        String[] parts = channel.split("@");
        if (parts.length < 3) return;

        String symbol = parts[2];

        List<OrderBookLevel> bids = parseOrderBookLevels(data.get("bids"));
        List<OrderBookLevel> asks = parseOrderBookLevels(data.get("asks"));

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.MEXC,
                    MarketType.SPOT,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTrades(JsonNode data, String channel) {
        String[] parts = channel.split("@");
        if (parts.length < 3) return;

        String symbol = parts[2];

        JsonNode deals = data.get("deals");
        if (deals == null || !deals.isArray()) {
            return;
        }

        for (JsonNode trade : deals) {
            BigDecimal price = new BigDecimal(trade.get("p").asText());
            BigDecimal quantity = new BigDecimal(trade.get("v").asText());

            incrementTradeUpdates();
            orderBookManager.updateLastPrice(symbol, Exchange.MEXC, MarketType.SPOT, price);

            volumeTracker.addVolume(
                    symbol,
                    Exchange.MEXC,
                    MarketType.SPOT,
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
                price = new BigDecimal(level.get("p").asText());
                quantity = new BigDecimal(level.get("v").asText());
            }

            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new OrderBookLevel(price, quantity));
            }
        }

        return result;
    }

    @Override
    protected String getPingMessage() {
        return "{\"method\":\"PING\"}";
    }

    @Override
    protected long getPingIntervalMs() {
        return 15000;
    }
}
