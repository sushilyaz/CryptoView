package com.cryptoview.exchange.bitget;

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

@Slf4j
@Component
public class BitgetSpotConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://ws.bitget.com/v2/ws/public";
    private static final String REST_URL = "https://api.bitget.com/api/v2/spot/public/symbols";

    public BitgetSpotConnector(OkHttpClient httpClient,
                                ObjectMapper objectMapper,
                                OrderBookManager orderBookManager,
                                VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public Exchange getExchange() {
        return Exchange.BITGET;
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
        log.info("[BITGET:SPOT] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            if (!connectAndWait(5000)) {
                log.error("[BITGET:SPOT] Failed to connect WebSocket, aborting subscribe");
                return;
            }

            int batchSize = 50;
            for (int i = 0; i < symbols.size(); i += batchSize) {
                int end = Math.min(i + batchSize, symbols.size());
                List<String> batch = symbols.subList(i, end);
                subscribe(batch);

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[BITGET:SPOT] Subscribed to {} symbols", symbols.size());
        }
    }

    private List<String> fetchAllSymbols() {
        Request request = new Request.Builder()
                .url(REST_URL)
                .build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode data = root.get("data");

                List<String> usdtSymbols = new ArrayList<>();
                if (data != null) {
                    for (JsonNode symbol : data) {
                        String symbolName = symbol.get("symbol").asText();
                        String status = symbol.get("status").asText();
                        String quoteCoin = symbol.get("quoteCoin").asText();

                        if ("online".equals(status) && "USDT".equals(quoteCoin)) {
                            usdtSymbols.add(symbolName);
                        }
                    }
                }

                log.info("[BITGET:SPOT] Found {} USDT trading pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[BITGET:SPOT] Failed to fetch symbols after retries", e);
        }

        return List.of();
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        List<String> args = new ArrayList<>();

        for (String symbol : symbols) {
            args.add(String.format("{\"instType\":\"SPOT\",\"channel\":\"books15\",\"instId\":\"%s\"}", symbol));
            args.add(String.format("{\"instType\":\"SPOT\",\"channel\":\"trade\",\"instId\":\"%s\"}", symbol));
        }

        return String.format("{\"op\":\"subscribe\",\"args\":[%s]}", String.join(",", args));
    }

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;

        // Пропускаем служебные сообщения
        if (root.has("event")) {
            return;
        }

        String action = root.has("action") ? root.get("action").asText() : null;
        JsonNode arg = root.get("arg");
        JsonNode data = root.get("data");

        if (arg == null || data == null) {
            return;
        }

        String channel = arg.get("channel").asText();
        String instId = arg.get("instId").asText();

        if ("books15".equals(channel)) {
            handleOrderBook(data, instId);
        } else if ("trade".equals(channel)) {
            handleTrades(data, instId);
        }
    }

    private void handleOrderBook(JsonNode data, String instId) {
        if (!data.isArray() || data.isEmpty()) {
            return;
        }

        JsonNode book = data.get(0);
        List<OrderBookLevel> bids = parseOrderBookLevels(book.get("bids"));
        List<OrderBookLevel> asks = parseOrderBookLevels(book.get("asks"));

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    instId,
                    Exchange.BITGET,
                    MarketType.SPOT,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTrades(JsonNode data, String instId) {
        if (!data.isArray()) {
            return;
        }

        for (JsonNode trade : data) {
            BigDecimal price = new BigDecimal(trade.get("price").asText());
            BigDecimal quantity = new BigDecimal(trade.get("size").asText());

            incrementTradeUpdates();
            orderBookManager.updateLastPrice(instId, Exchange.BITGET, MarketType.SPOT, price);

            volumeTracker.addVolume(
                    instId,
                    Exchange.BITGET,
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
        return "ping";
    }

    @Override
    protected long getPingIntervalMs() {
        return 25000;
    }
}
