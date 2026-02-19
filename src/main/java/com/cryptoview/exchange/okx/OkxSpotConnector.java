package com.cryptoview.exchange.okx;

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
public class OkxSpotConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/public";
    private static final String REST_URL = "https://www.okx.com/api/v5/public/instruments?instType=SPOT";

    public OkxSpotConnector(OkHttpClient httpClient,
                             ObjectMapper objectMapper,
                             OrderBookManager orderBookManager,
                             VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public Exchange getExchange() {
        return Exchange.OKX;
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
        log.info("[OKX:SPOT] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            if (!connectAndWait(5000)) {
                log.error("[OKX:SPOT] Failed to connect WebSocket, aborting subscribe");
                return;
            }

            int batchSize = 50;
            for (int i = 0; i < symbols.size(); i += batchSize) {
                int end = Math.min(i + batchSize, symbols.size());
                List<String> batch = symbols.subList(i, end);
                subscribe(batch);

                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[OKX:SPOT] Subscribed to {} symbols", symbols.size());
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
                    for (JsonNode inst : data) {
                        String instId = inst.get("instId").asText();
                        String state = inst.get("state").asText();
                        String quoteCcy = inst.get("quoteCcy").asText();

                        if ("live".equals(state) && "USDT".equals(quoteCcy)) {
                            usdtSymbols.add(instId);
                        }
                    }
                }

                log.info("[OKX:SPOT] Found {} USDT trading pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[OKX:SPOT] Failed to fetch symbols after retries", e);
        }

        return List.of();
    }

    @Override
    public void subscribe(List<String> symbols) {
        if (!connected.get()) {
            log.warn("[OKX:SPOT] Cannot subscribe - not connected");
            return;
        }

        String bookMsg = buildSubscribeForChannel(symbols, "books5");
        webSocket.send(bookMsg);

        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        String tradeMsg = buildSubscribeForChannel(symbols, "trades");
        webSocket.send(tradeMsg);

        subscribedSymbols.addAll(symbols);
        log.debug("[OKX:SPOT] Subscribed to {} symbols", symbols.size());
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        return buildSubscribeForChannel(symbols, "books5");
    }

    private String buildSubscribeForChannel(List<String> symbols, String channel) {
        List<String> args = new ArrayList<>();
        for (String symbol : symbols) {
            args.add(String.format("{\"channel\":\"%s\",\"instId\":\"%s\"}", channel, symbol));
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

        JsonNode arg = root.get("arg");
        JsonNode data = root.get("data");

        if (arg == null || data == null || !data.isArray() || data.isEmpty()) {
            return;
        }

        String channel = arg.get("channel").asText();
        String instId = arg.get("instId").asText();

        if ("books5".equals(channel)) {
            handleOrderBook(data.get(0), instId);
        } else if ("trades".equals(channel)) {
            handleTrades(data);
        }
    }

    private void handleOrderBook(JsonNode data, String instId) {
        List<OrderBookLevel> bids = parseOrderBookLevels(data.get("bids"));
        List<OrderBookLevel> asks = parseOrderBookLevels(data.get("asks"));

        // OKX instId format: BTC-USDT -> BTCUSDT
        String symbol = instId.replace("-", "");

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.OKX,
                    MarketType.SPOT,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTrades(JsonNode data) {
        for (JsonNode trade : data) {
            String instId = trade.get("instId").asText();
            String symbol = instId.replace("-", "");
            BigDecimal price = new BigDecimal(trade.get("px").asText());
            BigDecimal quantity = new BigDecimal(trade.get("sz").asText());

            incrementTradeUpdates();
            orderBookManager.updateLastPrice(symbol, Exchange.OKX, MarketType.SPOT, price);

            volumeTracker.addVolume(
                    symbol,
                    Exchange.OKX,
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
