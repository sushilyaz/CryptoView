package com.cryptoview.exchange.okx;

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
public class OkxSpotConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/public";
    private static final String REST_URL = "https://www.okx.com/api/v5/public/instruments?instType=SPOT";
    private static final String DEPTH_SNAPSHOT_URL = "https://www.okx.com/api/v5/market/books";

    private final Map<String, LocalOrderBook> localBooks = new ConcurrentHashMap<>();

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
        Request request = new Request.Builder().url(REST_URL).build();

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

        // Subscribe to books (400 levels, incremental) and trades separately
        String bookMsg = buildSubscribeForChannel(symbols, "books");
        webSocket.send(bookMsg);

        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        String tradeMsg = buildSubscribeForChannel(symbols, "trades");
        webSocket.send(tradeMsg);

        subscribedSymbols.addAll(symbols);
        log.debug("[OKX:SPOT] Subscribed to {} symbols", symbols.size());
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        return buildSubscribeForChannel(symbols, "books");
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

        if ("books".equals(channel)) {
            String action = root.has("action") ? root.get("action").asText() : "snapshot";
            handleOrderBook(data.get(0), instId, action);
        } else if ("trades".equals(channel)) {
            handleTrades(data);
        }
    }

    private void handleOrderBook(JsonNode data, String instId, String action) {
        String symbol = instId.replace("-", "");
        LocalOrderBook book = localBooks.computeIfAbsent(symbol, LocalOrderBook::new);

        long seqId = data.has("seqId") ? data.get("seqId").asLong() : 0;
        long prevSeqId = data.has("prevSeqId") ? data.get("prevSeqId").asLong() : 0;

        if ("snapshot".equals(action)) {
            List<List<String>> bids = parseLevelsRaw(data.get("bids"));
            List<List<String>> asks = parseLevelsRaw(data.get("asks"));
            book.applySnapshot(bids, asks, 0, seqId);
        } else if ("update".equals(action)) {
            if (!book.isInitialized()) {
                return;
            }

            // Verify sequence continuity
            if (prevSeqId != 0 && prevSeqId != book.getLastSeqId()) {
                log.warn("[OKX:SPOT] Seq gap for {} (prevSeqId={}, lastSeqId={}), fetching REST snapshot",
                        symbol, prevSeqId, book.getLastSeqId());
                fetchRestSnapshot(instId, book);
                return;
            }

            List<List<String>> bids = parseLevelsRaw(data.get("bids"));
            List<List<String>> asks = parseLevelsRaw(data.get("asks"));
            book.applyDelta(bids, asks, 0, seqId);
        }

        incrementOrderbookUpdates();
        publishOrderBook(symbol, book);
    }

    private void fetchRestSnapshot(String instId, LocalOrderBook book) {
        Thread.startVirtualThread(() -> {
            String url = DEPTH_SNAPSHOT_URL + "?instId=" + instId + "&sz=400";
            Request request = new Request.Builder().url(url).build();

            try (Response response = executeWithRetry(request, 3, 5000)) {
                if (response.body() != null) {
                    JsonNode root = objectMapper.readTree(response.body().string());
                    JsonNode data = root.get("data");
                    if (data != null && data.isArray() && !data.isEmpty()) {
                        JsonNode bookData = data.get(0);
                        long seqId = bookData.has("seqId") ? bookData.get("seqId").asLong() : 0;
                        List<List<String>> bids = parseLevelsRaw(bookData.get("bids"));
                        List<List<String>> asks = parseLevelsRaw(bookData.get("asks"));
                        book.applySnapshot(bids, asks, 0, seqId);
                        log.info("[OKX:SPOT] REST snapshot restored for {}", instId);
                    }
                }
            } catch (IOException e) {
                log.error("[OKX:SPOT] Failed to fetch REST snapshot for {}", instId, e);
            }
        });
    }

    private void publishOrderBook(String symbol, LocalOrderBook book) {
        LocalOrderBook.Snapshot snapshot = book.getSnapshot();
        if (!snapshot.bids().isEmpty() || !snapshot.asks().isEmpty()) {
            orderBookManager.updateOrderBook(
                    symbol,
                    Exchange.OKX,
                    MarketType.SPOT,
                    snapshot.bids(),
                    snapshot.asks(),
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

    private List<List<String>> parseLevelsRaw(JsonNode levels) {
        List<List<String>> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) {
            return result;
        }
        for (JsonNode level : levels) {
            // OKX format: [price, qty, deprecated, numOrders]
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
