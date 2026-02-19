package com.cryptoview.exchange.bybit;

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
public class BybitFuturesConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://stream.bybit.com/v5/public/linear";
    private static final String REST_URL = "https://api.bybit.com/v5/market/instruments-info?category=linear";

    public BybitFuturesConnector(OkHttpClient httpClient,
                                  ObjectMapper objectMapper,
                                  OrderBookManager orderBookManager,
                                  VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public Exchange getExchange() {
        return Exchange.BYBIT;
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
        log.info("[BYBIT:FUTURES] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            if (!connectAndWait(5000)) {
                log.error("[BYBIT:FUTURES] Failed to connect WebSocket, aborting subscribe");
                return;
            }

            int batchSize = 10;
            for (int i = 0; i < symbols.size(); i += batchSize) {
                int end = Math.min(i + batchSize, symbols.size());
                List<String> batch = symbols.subList(i, end);
                subscribe(batch);

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[BYBIT:FUTURES] Subscribed to {} symbols", symbols.size());
        }
    }

    private List<String> fetchAllSymbols() {
        List<String> usdtSymbols = new ArrayList<>();
        String cursor = "";

        do {
            String url = REST_URL + "&limit=500" + (cursor.isEmpty() ? "" : "&cursor=" + cursor);
            Request request = new Request.Builder().url(url).build();

            try (Response response = executeWithRetry(request, 3, 5000)) {
                if (response.body() != null) {
                    JsonNode root = objectMapper.readTree(response.body().string());
                    JsonNode result = root.get("result");
                    JsonNode list = result != null ? result.get("list") : null;

                    if (list != null) {
                        for (JsonNode symbol : list) {
                            String name = symbol.get("symbol").asText();
                            String status = symbol.get("status").asText();
                            String settleCoin = symbol.get("settleCoin").asText();
                            String contractType = symbol.has("contractType") ?
                                    symbol.get("contractType").asText() : "";

                            if ("Trading".equals(status) && "USDT".equals(settleCoin)
                                    && "LinearPerpetual".equals(contractType)) {
                                usdtSymbols.add(name);
                            }
                        }
                    }

                    cursor = result != null && result.has("nextPageCursor")
                            ? result.get("nextPageCursor").asText("") : "";
                } else {
                    break;
                }
            } catch (IOException e) {
                log.error("[BYBIT:FUTURES] Failed to fetch symbols after retries", e);
                break;
            }
        } while (!cursor.isEmpty());

        log.info("[BYBIT:FUTURES] Found {} USDT perpetual pairs", usdtSymbols.size());
        return usdtSymbols;
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        List<String> args = new ArrayList<>();

        for (String symbol : symbols) {
            args.add("orderbook.50." + symbol);
            args.add("publicTrade." + symbol);
        }

        return String.format("{\"op\":\"subscribe\",\"args\":%s}", toJsonArray(args));
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

        if (root.has("success") || root.has("ret_msg")) {
            return;
        }

        String topic = root.has("topic") ? root.get("topic").asText() : null;
        if (topic == null) return;

        JsonNode data = root.get("data");
        if (data == null) return;

        if (topic.startsWith("orderbook.")) {
            handleOrderBook(data, topic);
        } else if (topic.startsWith("publicTrade.")) {
            handleTrade(data);
        }
    }

    private void handleOrderBook(JsonNode data, String topic) {
        String symbol = data.get("s").asText();

        List<OrderBookLevel> bids = parseOrderBookLevels(data.get("b"));
        List<OrderBookLevel> asks = parseOrderBookLevels(data.get("a"));

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.BYBIT,
                    MarketType.FUTURES,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTrade(JsonNode data) {
        if (!data.isArray()) {
            processSingleTrade(data);
            return;
        }

        for (JsonNode trade : data) {
            processSingleTrade(trade);
        }
    }

    private void processSingleTrade(JsonNode trade) {
        String symbol = trade.get("s").asText();
        BigDecimal price = new BigDecimal(trade.get("p").asText());
        BigDecimal quantity = new BigDecimal(trade.get("v").asText());

        incrementTradeUpdates();
        orderBookManager.updateLastPrice(symbol, Exchange.BYBIT, MarketType.FUTURES, price);

        volumeTracker.addVolume(
                symbol,
                Exchange.BYBIT,
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
        return "{\"op\":\"ping\"}";
    }

    @Override
    protected long getPingIntervalMs() {
        return 20000;
    }
}
