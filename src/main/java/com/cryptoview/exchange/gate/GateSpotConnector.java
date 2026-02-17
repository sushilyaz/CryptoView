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
public class GateSpotConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://api.gateio.ws/ws/v4/";
    private static final String REST_URL = "https://api.gateio.ws/api/v4/spot/currency_pairs";

    public GateSpotConnector(OkHttpClient httpClient,
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
        return MarketType.SPOT;
    }

    @Override
    protected String getWebSocketUrl() {
        return WS_URL;
    }

    @Override
    protected void onConnected() {
        log.info("[GATE:SPOT] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            preloadVolumes(symbols);

            if (!connectAndWait(5000)) {
                log.error("[GATE:SPOT] Failed to connect WebSocket, aborting subscribe");
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
            log.info("[GATE:SPOT] Subscribed to {} symbols", symbols.size());
        }
    }

    @Override
    protected void preloadVolumes(List<String> symbols) {
        log.info("[GATE:SPOT] Pre-loading volumes for {} symbols...", symbols.size());
        int loaded = 0;
        for (int i = 0; i < symbols.size(); i += 5) {
            int end = Math.min(i + 5, symbols.size());
            for (int j = i; j < end; j++) {
                String currencyPair = symbols.get(j);
                String normalizedSymbol = currencyPair.replace("_", "");
                try {
                    String url = "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=" + currencyPair + "&interval=1m&limit=15";
                    Request request = new Request.Builder().url(url).build();
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            JsonNode klines = objectMapper.readTree(response.body().string());
                            BigDecimal totalVolume = BigDecimal.ZERO;
                            if (klines != null && klines.isArray()) {
                                for (JsonNode kline : klines) {
                                    totalVolume = totalVolume.add(new BigDecimal(kline.get(1).asText())); // quote volume
                                }
                            }
                            volumeTracker.seedVolume(normalizedSymbol, Exchange.GATE, MarketType.SPOT, totalVolume);
                            loaded++;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[GATE:SPOT] Failed to preload volume for {}: {}", currencyPair, e.getMessage());
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        log.info("[GATE:SPOT] Pre-loaded volume for {}/{} symbols", loaded, symbols.size());
    }

    private List<String> fetchAllSymbols() {
        Request request = new Request.Builder()
                .url(REST_URL)
                .build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() != null) {
                JsonNode pairs = objectMapper.readTree(response.body().string());

                List<String> usdtSymbols = new ArrayList<>();
                for (JsonNode pair : pairs) {
                    String id = pair.get("id").asText();
                    String tradeStatus = pair.get("trade_status").asText();
                    String quote = pair.get("quote").asText();

                    if ("tradable".equals(tradeStatus) && "USDT".equals(quote)) {
                        usdtSymbols.add(id);
                    }
                }

                log.info("[GATE:SPOT] Found {} USDT trading pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[GATE:SPOT] Failed to fetch symbols after retries", e);
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
            log.warn("[GATE:SPOT] Cannot subscribe - not connected");
            return;
        }

        // Gate.io requires one currency pair per subscription message
        for (String symbol : symbols) {
            // Orderbook subscription: payload = [currency_pair, level, interval]
            String obMsg = String.format(
                    "{\"time\":%d,\"channel\":\"spot.order_book\",\"event\":\"subscribe\",\"payload\":[\"%s\",\"20\",\"100ms\"]}",
                    System.currentTimeMillis() / 1000, symbol
            );
            send(obMsg);

            // Trades subscription: payload = [currency_pair]
            String tradeMsg = String.format(
                    "{\"time\":%d,\"channel\":\"spot.trades\",\"event\":\"subscribe\",\"payload\":[\"%s\"]}",
                    System.currentTimeMillis() / 1000, symbol
            );
            send(tradeMsg);

            subscribedSymbols.add(symbol);
        }
        log.debug("[GATE:SPOT] Subscribed to {} symbols", symbols.size());
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

        if ("spot.order_book".equals(channel)) {
            handleOrderBook(result);
        } else if ("spot.trades".equals(channel)) {
            handleTrades(result);
        }
    }

    private void handleOrderBook(JsonNode data) {
        String currencyPair = data.has("s") ? data.get("s").asText() :
                (data.has("currency_pair") ? data.get("currency_pair").asText() : null);

        if (currencyPair == null) return;

        List<OrderBookLevel> bids = parseOrderBookLevels(data.get("bids"));
        List<OrderBookLevel> asks = parseOrderBookLevels(data.get("asks"));

        // Gate format: BTC_USDT -> BTCUSDT
        String symbol = currencyPair.replace("_", "");

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.GATE,
                    MarketType.SPOT,
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
        String currencyPair = trade.has("currency_pair") ?
                trade.get("currency_pair").asText() : null;
        if (currencyPair == null) return;

        String symbol = currencyPair.replace("_", "");
        BigDecimal price = new BigDecimal(trade.get("price").asText());
        BigDecimal quantity = new BigDecimal(trade.get("amount").asText());

        incrementTradeUpdates();
        orderBookManager.updateLastPrice(symbol, Exchange.GATE, MarketType.SPOT, price);

        volumeTracker.addVolume(
                symbol,
                Exchange.GATE,
                MarketType.SPOT,
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
        return String.format("{\"time\":%d,\"channel\":\"spot.ping\"}",
                System.currentTimeMillis() / 1000);
    }

    @Override
    protected long getPingIntervalMs() {
        return 10000;
    }
}
