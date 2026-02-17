package com.cryptoview.exchange.gate;

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
public class GateFuturesConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://fx-ws.gateio.ws/v4/ws/usdt";
    private static final String REST_URL = "https://api.gateio.ws/api/v4/futures/usdt/contracts";

    public GateFuturesConnector(OkHttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 OrderBookManager orderBookManager,
                                 VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public Exchange getExchange() {
        return Exchange.GATE;
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
        log.info("[GATE:FUTURES] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            preloadVolumes(symbols);

            if (!connectAndWait(5000)) {
                log.error("[GATE:FUTURES] Failed to connect WebSocket, aborting subscribe");
                return;
            }

            int batchSize = 20;
            for (int i = 0; i < symbols.size(); i += batchSize) {
                int end = Math.min(i + batchSize, symbols.size());
                List<String> batch = symbols.subList(i, end);
                subscribe(batch);

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("[GATE:FUTURES] Subscribed to {} symbols", symbols.size());
        }
    }

    @Override
    protected void preloadVolumes(List<String> symbols) {
        log.info("[GATE:FUTURES] Pre-loading volumes for {} symbols...", symbols.size());
        int loaded = 0;
        for (int i = 0; i < symbols.size(); i += 5) {
            int end = Math.min(i + 5, symbols.size());
            for (int j = i; j < end; j++) {
                String contract = symbols.get(j);
                String normalizedSymbol = contract.replace("_", "");
                try {
                    String url = "https://api.gateio.ws/api/v4/futures/usdt/candlesticks?contract=" + contract + "&interval=1m&limit=15";
                    Request request = new Request.Builder().url(url).build();
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            JsonNode klines = objectMapper.readTree(response.body().string());
                            BigDecimal totalVolume = BigDecimal.ZERO;
                            if (klines != null && klines.isArray()) {
                                for (JsonNode kline : klines) {
                                    // Gate futures candles have "sum" field for quote volume
                                    if (kline.has("sum")) {
                                        totalVolume = totalVolume.add(new BigDecimal(kline.get("sum").asText()));
                                    }
                                }
                            }
                            volumeTracker.seedVolume(normalizedSymbol, Exchange.GATE, MarketType.FUTURES, totalVolume);
                            loaded++;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[GATE:FUTURES] Failed to preload volume for {}: {}", contract, e.getMessage());
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        log.info("[GATE:FUTURES] Pre-loaded volume for {}/{} symbols", loaded, symbols.size());
    }

    private List<String> fetchAllSymbols() {
        Request request = new Request.Builder()
                .url(REST_URL)
                .build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() != null) {
                JsonNode contracts = objectMapper.readTree(response.body().string());

                List<String> symbols = new ArrayList<>();
                for (JsonNode contract : contracts) {
                    String name = contract.get("name").asText();
                    boolean inDelisting = contract.has("in_delisting") &&
                            contract.get("in_delisting").asBoolean();

                    if (!inDelisting) {
                        symbols.add(name);
                    }
                }

                log.info("[GATE:FUTURES] Found {} USDT-M contracts", symbols.size());
                return symbols;
            }
        } catch (IOException e) {
            log.error("[GATE:FUTURES] Failed to fetch symbols after retries", e);
        }

        return List.of();
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        // Not used directly - subscribe() is overridden
        return null;
    }

    @Override
    public void subscribe(List<String> symbols) {
        if (!connected.get()) {
            log.warn("[GATE:FUTURES] Cannot subscribe - not connected");
            return;
        }

        // Gate.io requires one contract per subscription message
        for (String symbol : symbols) {
            // Orderbook subscription: payload = [contract, level, interval]
            String obMsg = String.format(
                    "{\"time\":%d,\"channel\":\"futures.order_book\",\"event\":\"subscribe\",\"payload\":[\"%s\",\"20\",\"100ms\"]}",
                    System.currentTimeMillis() / 1000, symbol
            );
            send(obMsg);

            // Trades subscription: payload = [contract]
            String tradeMsg = String.format(
                    "{\"time\":%d,\"channel\":\"futures.trades\",\"event\":\"subscribe\",\"payload\":[\"%s\"]}",
                    System.currentTimeMillis() / 1000, symbol
            );
            send(tradeMsg);

            subscribedSymbols.add(symbol);
        }
        log.debug("[GATE:FUTURES] Subscribed to {} symbols", symbols.size());
    }

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;

        String channel = root.has("channel") ? root.get("channel").asText() : null;
        String event = root.has("event") ? root.get("event").asText() : null;

        if ("subscribe".equals(event)) {
            return;
        }

        JsonNode result = root.get("result");
        if (result == null) {
            return;
        }

        if ("futures.order_book".equals(channel)) {
            handleOrderBook(result);
        } else if ("futures.trades".equals(channel)) {
            handleTrades(result);
        }
    }

    private void handleOrderBook(JsonNode data) {
        String contract = data.has("s") ? data.get("s").asText() :
                (data.has("contract") ? data.get("contract").asText() : null);

        if (contract == null) return;

        List<OrderBookLevel> bids = parseOrderBookLevels(data.get("bids"));
        List<OrderBookLevel> asks = parseOrderBookLevels(data.get("asks"));

        // Gate futures format: BTC_USDT -> BTCUSDT
        String symbol = contract.replace("_", "");

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.GATE,
                    MarketType.FUTURES,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTrades(JsonNode data) {
        if (!data.isArray()) {
            processSingleTrade(data);
            return;
        }

        for (JsonNode trade : data) {
            processSingleTrade(trade);
        }
    }

    private void processSingleTrade(JsonNode trade) {
        String contract = trade.has("contract") ?
                trade.get("contract").asText() : null;
        if (contract == null) return;

        String symbol = contract.replace("_", "");
        BigDecimal price = new BigDecimal(trade.get("price").asText());
        BigDecimal quantity = new BigDecimal(trade.get("size").asText()).abs();

        incrementTradeUpdates();
        orderBookManager.updateLastPrice(symbol, Exchange.GATE, MarketType.FUTURES, price);

        volumeTracker.addVolume(
                symbol,
                Exchange.GATE,
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
            BigDecimal price;
            BigDecimal quantity;

            if (level.isArray()) {
                price = new BigDecimal(level.get(0).asText());
                quantity = new BigDecimal(level.get(1).asText());
            } else {
                price = new BigDecimal(level.get("p").asText());
                quantity = new BigDecimal(level.get("s").asText());
            }

            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new OrderBookLevel(price, quantity));
            }
        }

        return result;
    }

    @Override
    protected String getPingMessage() {
        return String.format("{\"time\":%d,\"channel\":\"futures.ping\"}",
                System.currentTimeMillis() / 1000);
    }

    @Override
    protected long getPingIntervalMs() {
        return 10000;
    }
}
