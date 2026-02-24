package com.cryptoview.exchange.bitget;

import com.cryptoview.exchange.common.AbstractWebSocketConnector;
import com.cryptoview.exchange.common.LocalOrderBook;
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
public class BitgetSpotConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://ws.bitget.com/v2/ws/public";
    private static final String REST_URL = "https://api.bitget.com/api/v2/spot/public/symbols";
    private static final String DEPTH_SNAPSHOT_URL = "https://api.bitget.com/api/v2/spot/market/orderbook";

    private final Map<String, LocalOrderBook> localBooks = new ConcurrentHashMap<>();

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
        Request request = new Request.Builder().url(REST_URL).build();

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
            args.add(String.format("{\"instType\":\"SPOT\",\"channel\":\"books\",\"instId\":\"%s\"}", symbol));
            args.add(String.format("{\"instType\":\"SPOT\",\"channel\":\"trade\",\"instId\":\"%s\"}", symbol));
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

        if (arg == null || data == null) {
            return;
        }

        String channel = arg.get("channel").asText();
        String instId = arg.get("instId").asText();

        if ("books".equals(channel)) {
            String action = root.has("action") ? root.get("action").asText() : "snapshot";
            handleOrderBook(data, instId, action);
        } else if ("trade".equals(channel)) {
            handleTrades(data, instId);
        }
    }

    private void handleOrderBook(JsonNode data, String instId, String action) {
        if (!data.isArray() || data.isEmpty()) {
            return;
        }

        JsonNode bookData = data.get(0);
        LocalOrderBook book = localBooks.computeIfAbsent(instId, LocalOrderBook::new);

        long seq = bookData.has("seq") ? bookData.get("seq").asLong() : 0;
        long pseq = bookData.has("pseq") ? bookData.get("pseq").asLong() : 0;

        if ("snapshot".equals(action)) {
            List<List<String>> bids = parseLevelsRaw(bookData.get("bids"));
            List<List<String>> asks = parseLevelsRaw(bookData.get("asks"));
            book.applySnapshot(bids, asks, 0, seq);
        } else if ("update".equals(action)) {
            if (!book.isInitialized()) {
                return;
            }

            if (pseq != 0 && pseq != book.getLastSeqId()) {
                log.warn("[BITGET:SPOT] Seq gap for {} (pseq={}, lastSeq={}), fetching REST snapshot",
                        instId, pseq, book.getLastSeqId());
                fetchRestSnapshot(instId, book);
                return;
            }

            List<List<String>> bids = parseLevelsRaw(bookData.get("bids"));
            List<List<String>> asks = parseLevelsRaw(bookData.get("asks"));
            book.applyDelta(bids, asks, 0, seq);
        }

        incrementOrderbookUpdates();
        publishOrderBook(instId, book);
    }

    private void fetchRestSnapshot(String instId, LocalOrderBook book) {
        Thread.startVirtualThread(() -> {
            String url = DEPTH_SNAPSHOT_URL + "?symbol=" + instId + "&type=step0&limit=150";
            Request request = new Request.Builder().url(url).build();

            try (Response response = executeWithRetry(request, 3, 5000)) {
                if (response.body() != null) {
                    JsonNode root = objectMapper.readTree(response.body().string());
                    JsonNode data = root.get("data");
                    if (data != null) {
                        List<List<String>> bids = parseLevelsRaw(data.get("bids"));
                        List<List<String>> asks = parseLevelsRaw(data.get("asks"));
                        book.applySnapshot(bids, asks, 0, 0);
                        log.info("[BITGET:SPOT] REST snapshot restored for {}", instId);
                    }
                }
            } catch (IOException e) {
                log.error("[BITGET:SPOT] Failed to fetch REST snapshot for {}", instId, e);
            }
        });
    }

    private void publishOrderBook(String symbol, LocalOrderBook book) {
        LocalOrderBook.Snapshot snapshot = book.getSnapshot();
        if (!snapshot.bids().isEmpty() || !snapshot.asks().isEmpty()) {
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.BITGET,
                    MarketType.SPOT,
                    snapshot.bids(),
                    snapshot.asks(),
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

    private List<List<String>> parseLevelsRaw(JsonNode levels) {
        List<List<String>> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) {
            return result;
        }
        for (JsonNode level : levels) {
            result.add(List.of(level.get(0).asText(), level.get(1).asText()));
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
