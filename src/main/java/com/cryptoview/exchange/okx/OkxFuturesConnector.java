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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class OkxFuturesConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/public";
    private static final String REST_URL = "https://www.okx.com/api/v5/public/instruments?instType=SWAP";

    // OKX futures: sz = number of contracts, real quantity = sz * ctVal
    // Key: instId (e.g. "BTC-USDT-SWAP"), Value: ctVal (e.g. 0.01)
    private final Map<String, BigDecimal> contractValues = new ConcurrentHashMap<>();

    public OkxFuturesConnector(OkHttpClient httpClient,
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
        return MarketType.FUTURES;
    }

    @Override
    protected String getWebSocketUrl() {
        return WS_URL;
    }

    @Override
    protected void onConnected() {
        log.info("[OKX:FUTURES] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            if (!connectAndWait(5000)) {
                log.error("[OKX:FUTURES] Failed to connect WebSocket, aborting subscribe");
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
            log.info("[OKX:FUTURES] Subscribed to {} symbols", symbols.size());
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
                        String settleCcy = inst.get("settleCcy").asText();

                        // USDT-settled perpetual swaps
                        if ("live".equals(state) && "USDT".equals(settleCcy)) {
                            usdtSymbols.add(instId);

                            // Save contract value for converting contracts -> base currency
                            String ctVal = inst.has("ctVal") ? inst.get("ctVal").asText() : "1";
                            contractValues.put(instId, new BigDecimal(ctVal));
                        }
                    }
                }

                log.info("[OKX:FUTURES] Found {} USDT-M perpetual swaps, loaded {} contract values",
                        usdtSymbols.size(), contractValues.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[OKX:FUTURES] Failed to fetch symbols after retries", e);
        }

        return List.of();
    }

    @Override
    public void subscribe(List<String> symbols) {
        if (!connected.get()) {
            log.warn("[OKX:FUTURES] Cannot subscribe - not connected");
            return;
        }

        // Subscribe orderbook and trades separately to avoid arg limits
        String bookMsg = buildSubscribeForChannel(symbols, "books5");
        webSocket.send(bookMsg);

        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        String tradeMsg = buildSubscribeForChannel(symbols, "trades");
        webSocket.send(tradeMsg);

        subscribedSymbols.addAll(symbols);
        log.debug("[OKX:FUTURES] Subscribed to {} symbols", symbols.size());
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
        BigDecimal ctVal = contractValues.getOrDefault(instId, BigDecimal.ONE);
        List<OrderBookLevel> bids = parseOrderBookLevels(data.get("bids"), ctVal);
        List<OrderBookLevel> asks = parseOrderBookLevels(data.get("asks"), ctVal);

        // OKX instId format: BTC-USDT-SWAP -> BTCUSDT
        String symbol = instId.replace("-SWAP", "").replace("-", "");

        if (!bids.isEmpty() || !asks.isEmpty()) {
            incrementOrderbookUpdates();
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.OKX,
                    MarketType.FUTURES,
                    bids,
                    asks,
                    null
            );
        }
    }

    private void handleTrades(JsonNode data) {
        for (JsonNode trade : data) {
            String instId = trade.get("instId").asText();
            String symbol = instId.replace("-SWAP", "").replace("-", "");
            BigDecimal price = new BigDecimal(trade.get("px").asText());
            BigDecimal szContracts = new BigDecimal(trade.get("sz").asText());

            // Convert contracts to base currency: realQty = sz * ctVal
            BigDecimal ctVal = contractValues.getOrDefault(instId, BigDecimal.ONE);
            BigDecimal quantity = szContracts.multiply(ctVal);

            incrementTradeUpdates();
            orderBookManager.updateLastPrice(symbol, Exchange.OKX, MarketType.FUTURES, price);

            volumeTracker.addVolume(
                    symbol,
                    Exchange.OKX,
                    MarketType.FUTURES,
                    price.multiply(quantity)
            );
        }
    }

    private List<OrderBookLevel> parseOrderBookLevels(JsonNode levels, BigDecimal ctVal) {
        List<OrderBookLevel> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) {
            return result;
        }

        for (JsonNode level : levels) {
            BigDecimal price = new BigDecimal(level.get(0).asText());
            BigDecimal szContracts = new BigDecimal(level.get(1).asText());
            // Convert contracts to base currency: realQty = sz * ctVal
            BigDecimal quantity = szContracts.multiply(ctVal);
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
