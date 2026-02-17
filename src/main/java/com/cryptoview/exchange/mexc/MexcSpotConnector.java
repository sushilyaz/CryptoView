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
import java.util.ArrayList;
import java.util.List;
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
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            preloadVolumes(symbols);

            if (!connectAndWait(5000)) {
                log.error("[MEXC:SPOT] Failed to connect WebSocket, aborting subscribe");
                return;
            }

            int batchSize = 30;
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
            log.info("[MEXC:SPOT] Subscribed to {} symbols", symbols.size());
        }
    }

    @Override
    protected void preloadVolumes(List<String> symbols) {
        log.info("[MEXC:SPOT] Pre-loading volumes for {} symbols...", symbols.size());
        int loaded = 0;
        for (int i = 0; i < symbols.size(); i += 5) {
            int end = Math.min(i + 5, symbols.size());
            for (int j = i; j < end; j++) {
                String symbol = symbols.get(j);
                try {
                    String url = "https://api.mexc.com/api/v3/klines?symbol=" + symbol + "&interval=1m&limit=15";
                    Request request = new Request.Builder().url(url).build();
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            JsonNode klines = objectMapper.readTree(response.body().string());
                            BigDecimal totalVolume = BigDecimal.ZERO;
                            if (klines != null && klines.isArray()) {
                                for (JsonNode kline : klines) {
                                    totalVolume = totalVolume.add(new BigDecimal(kline.get(7).asText())); // quoteAssetVolume
                                }
                            }
                            volumeTracker.seedVolume(symbol, Exchange.MEXC, MarketType.SPOT, totalVolume);
                            loaded++;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[MEXC:SPOT] Failed to preload volume for {}: {}", symbol, e.getMessage());
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        log.info("[MEXC:SPOT] Pre-loaded volume for {}/{} symbols", loaded, symbols.size());
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

        // Пропускаем служебные сообщения
        if (root.has("msg") || root.has("code")) {
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
