package com.cryptoview.exchange.binance;

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
import java.util.stream.Collectors;


@Slf4j
@Component
public class BinanceFuturesConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://fstream.binance.com/stream";
    private static final String REST_URL = "https://fapi.binance.com/fapi/v1/exchangeInfo";

    public BinanceFuturesConnector(OkHttpClient httpClient,
                                    ObjectMapper objectMapper,
                                    OrderBookManager orderBookManager,
                                    VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public Exchange getExchange() {
        return Exchange.BINANCE;
    }

    @Override
    public MarketType getMarketType() {
        return MarketType.FUTURES;
    }

    @Override
    protected String getWebSocketUrl() {
        return WS_URL;
    }

    @Override
    protected void onConnected() {
        log.info("[BINANCE:FUTURES] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            // Binance limit: 1024 streams per connection. Each symbol = 2 streams (depth + aggTrade)
            int maxSymbols = 512;
            if (symbols.size() > maxSymbols) {
                log.warn("[BINANCE:FUTURES] Limiting from {} to {} symbols (1024 stream limit)",
                        symbols.size(), maxSymbols);
                symbols = symbols.subList(0, maxSymbols);
            }

            if (!connectAndWait(5000)) {
                log.error("[BINANCE:FUTURES] Failed to connect WebSocket, aborting subscribe");
                return;
            }

            int batchSize = 20;
            for (int i = 0; i < symbols.size(); i += batchSize) {
                int end = Math.min(i + batchSize, symbols.size());
                List<String> batch = symbols.subList(i, end);
                subscribe(batch);

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[BINANCE:FUTURES] Subscribed to {} symbols", symbols.size());
        }
    }

    private List<String> fetchAllSymbols() {
        Request request = new Request.Builder()
                .url(REST_URL)
                .build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode symbols = root.get("symbols");

                List<String> usdtSymbols = new ArrayList<>();
                for (JsonNode symbol : symbols) {
                    String name = symbol.get("symbol").asText();
                    String status = symbol.get("status").asText();
                    String quoteAsset = symbol.get("quoteAsset").asText();
                    String contractType = symbol.has("contractType") ?
                            symbol.get("contractType").asText() : "";

                    if ("TRADING".equals(status) && "USDT".equals(quoteAsset)
                            && "PERPETUAL".equals(contractType)) {
                        usdtSymbols.add(name);
                    }
                }

                log.info("[BINANCE:FUTURES] Found {} perpetual USDT pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[BINANCE:FUTURES] Failed to fetch symbols after retries", e);
        }

        return List.of();
    }

    @Override
    public void subscribe(List<String> symbols) {
        if (!connected.get()) {
            log.warn("[BINANCE:FUTURES] Cannot subscribe - not connected");
            return;
        }

        // Combined depth + trade subscription in one message
        List<String> allStreams = new ArrayList<>();
        for (String s : symbols) {
            allStreams.add(s.toLowerCase() + "@depth20@500ms");
            allStreams.add(s.toLowerCase() + "@aggTrade");
        }
        String msg = String.format("{\"method\":\"SUBSCRIBE\",\"params\":%s,\"id\":%d}",
                toJsonArray(allStreams), System.currentTimeMillis());
        webSocket.send(msg);

        subscribedSymbols.addAll(symbols);
        log.debug("[BINANCE:FUTURES] Subscribed to {} symbols ({} streams)", symbols.size(), allStreams.size());
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        List<String> allStreams = new ArrayList<>();
        for (String s : symbols) {
            allStreams.add(s.toLowerCase() + "@depth20@500ms");
            allStreams.add(s.toLowerCase() + "@aggTrade");
        }
        return String.format("{\"method\":\"SUBSCRIBE\",\"params\":%s,\"id\":%d}",
                toJsonArray(allStreams), System.currentTimeMillis());
    }

    private String toJsonArray(List<String> items) {
        return "[" + items.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(",")) + "]";
    }

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;

        // Skip subscription responses
        if (root.has("result") && root.has("id")) {
            return;
        }

        // Combined stream format: {"stream":"btcusdt@depth20@100ms","data":{...}}
        String stream = root.has("stream") ? root.get("stream").asText() : null;
        JsonNode data = root.has("data") ? root.get("data") : root;

        if (stream != null) {
            if (stream.contains("@depth")) {
                handleDepthUpdate(data, extractSymbol(stream));
            } else if (stream.contains("@aggTrade")) {
                handleTradeUpdate(data);
            }
        } else {
            // Raw stream - detect message type by content
            String eventType = data.has("e") ? data.get("e").asText() : null;
            if ("aggTrade".equals(eventType)) {
                handleTradeUpdate(data);
            } else if ("depthUpdate".equals(eventType)) {
                String symbol = data.has("s") ? data.get("s").asText() : null;
                if (symbol != null) {
                    handleDepthUpdate(data, symbol);
                }
            }
        }
    }

    private String extractSymbol(String stream) {
        return stream.split("@")[0].toUpperCase();
    }

    private void handleDepthUpdate(JsonNode data, String symbol) {
        List<OrderBookLevel> bids = parseOrderBookLevels(data.get("b"));
        List<OrderBookLevel> asks = parseOrderBookLevels(data.get("a"));

        if (bids.isEmpty() && asks.isEmpty()) {
            bids = parseOrderBookLevels(data.get("bids"));
            asks = parseOrderBookLevels(data.get("asks"));
        }

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol.toUpperCase(),
                    Exchange.BINANCE,
                    MarketType.FUTURES,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTradeUpdate(JsonNode data) {
        String symbol = data.get("s").asText();
        BigDecimal price = new BigDecimal(data.get("p").asText());
        BigDecimal quantity = new BigDecimal(data.get("q").asText());

        incrementTradeUpdates();
        orderBookManager.updateLastPrice(symbol, Exchange.BINANCE, MarketType.FUTURES, price);

        volumeTracker.addVolume(
                symbol,
                Exchange.BINANCE,
                MarketType.FUTURES,
                price.multiply(quantity)
        );
    }

    private List<OrderBookLevel> parseOrderBookLevels(JsonNode levels) {
        List<OrderBookLevel> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) {
            return result;
        }

        for (JsonNode level : levels) {
            BigDecimal price = new BigDecimal(level.get(0).asText());
            BigDecimal quantity = new BigDecimal(level.get(1).asText());
            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new OrderBookLevel(price, quantity));
            }
        }

        return result;
    }

    @Override
    protected String getPingMessage() {
        // Binance WS does not support text pings; OkHttp handles WebSocket-level ping/pong
        return null;
    }
}
