package com.cryptoview.exchange.binance;

import com.cryptoview.exchange.common.AbstractWebSocketConnector;
import com.cryptoview.exchange.common.LocalOrderBook;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BinanceSpotConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://stream.binance.com:9443/stream";
    private static final String REST_URL = "https://api.binance.com/api/v3/exchangeInfo";
    private static final String DEPTH_SNAPSHOT_URL = "https://api.binance.com/api/v3/depth";
    private static final int SNAPSHOT_LIMIT = 5000;

    // Local orderbooks for diff-based depth management
    private final Map<String, LocalOrderBook> localBooks = new ConcurrentHashMap<>();
    // Buffer for events received before snapshot
    private final Map<String, CopyOnWriteArrayList<JsonNode>> eventBuffers = new ConcurrentHashMap<>();
    // Track symbols that are currently initializing (fetching snapshot)
    private final Map<String, Boolean> initializing = new ConcurrentHashMap<>();

    public BinanceSpotConnector(OkHttpClient httpClient,
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
        return MarketType.SPOT;
    }

    @Override
    protected String getWebSocketUrl() {
        return WS_URL;
    }

    @Override
    protected void onConnected() {
        log.info("[BINANCE:SPOT] Connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (!symbols.isEmpty()) {
            // Each symbol = 2 streams (depth + trade), limit 1024
            int maxSymbols = 512;
            if (symbols.size() > maxSymbols) {
                log.warn("[BINANCE:SPOT] Limiting from {} to {} symbols (1024 stream limit)",
                        symbols.size(), maxSymbols);
                symbols = symbols.subList(0, maxSymbols);
            }

            if (!connectAndWait(5000)) {
                log.error("[BINANCE:SPOT] Failed to connect WebSocket, aborting subscribe");
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
            log.info("[BINANCE:SPOT] Subscribed to {} symbols", symbols.size());

            // Fetch snapshots for all symbols in background
            Thread.startVirtualThread(() -> fetchSnapshotsForAll(List.copyOf(subscribedSymbols)));
        }
    }

    private void fetchSnapshotsForAll(List<String> symbols) {
        log.info("[BINANCE:SPOT] Fetching depth snapshots for {} symbols...", symbols.size());
        int count = 0;
        for (String symbol : symbols) {
            try {
                fetchAndApplySnapshot(symbol);
                count++;
                // Rate limit: limit=5000 costs 250 weight, IP limit is 6000/min
                // So ~24 requests/min max. Be conservative: 1 per 3 seconds
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[BINANCE:SPOT] Failed to fetch snapshot for {}: {}", symbol, e.getMessage());
            }
        }
        log.info("[BINANCE:SPOT] Fetched {} of {} snapshots", count, symbols.size());
    }

    private void fetchAndApplySnapshot(String symbol) {
        initializing.put(symbol, true);

        // Create event buffer before fetching
        eventBuffers.putIfAbsent(symbol, new CopyOnWriteArrayList<>());

        String url = DEPTH_SNAPSHOT_URL + "?symbol=" + symbol + "&limit=" + SNAPSHOT_LIMIT;
        Request request = new Request.Builder().url(url).build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body().string());
                long lastUpdateId = root.get("lastUpdateId").asLong();
                List<List<String>> bids = parseLevelsRaw(root.get("bids"));
                List<List<String>> asks = parseLevelsRaw(root.get("asks"));

                LocalOrderBook book = localBooks.computeIfAbsent(symbol, LocalOrderBook::new);
                book.applySnapshot(bids, asks, lastUpdateId);

                // Apply buffered events
                CopyOnWriteArrayList<JsonNode> buffer = eventBuffers.remove(symbol);
                if (buffer != null) {
                    int applied = 0;
                    for (JsonNode event : buffer) {
                        long u = event.get("u").asLong();
                        long U = event.get("U").asLong();

                        // Drop events where u <= snapshot lastUpdateId
                        if (u <= lastUpdateId) continue;

                        // First valid event should have lastUpdateId in [U, u]
                        if (applied == 0 && (U > lastUpdateId + 1)) {
                            // Gap — need to re-fetch
                            log.warn("[BINANCE:SPOT] Gap detected for {} after snapshot, re-fetching", symbol);
                            book.reset();
                            initializing.remove(symbol);
                            return;
                        }

                        applyDiffEvent(book, event);
                        applied++;
                    }
                    if (applied > 0) {
                        log.debug("[BINANCE:SPOT] Applied {} buffered events for {}", applied, symbol);
                    }
                }

                publishOrderBook(symbol, book);
                initializing.remove(symbol);

                log.debug("[BINANCE:SPOT] Initialized {} with {} bids + {} asks",
                        symbol, book.getBidCount(), book.getAskCount());
            }
        } catch (IOException e) {
            log.error("[BINANCE:SPOT] Failed to fetch snapshot for {}", symbol, e);
            initializing.remove(symbol);
        }
    }

    private List<String> fetchAllSymbols() {
        Request request = new Request.Builder().url(REST_URL).build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode symbols = root.get("symbols");

                List<String> usdtSymbols = new ArrayList<>();
                for (JsonNode symbol : symbols) {
                    String name = symbol.get("symbol").asText();
                    String status = symbol.get("status").asText();
                    String quoteAsset = symbol.get("quoteAsset").asText();

                    if ("TRADING".equals(status) && "USDT".equals(quoteAsset)) {
                        usdtSymbols.add(name);
                    }
                }

                log.info("[BINANCE:SPOT] Found {} USDT trading pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[BINANCE:SPOT] Failed to fetch symbols after retries", e);
        }

        return List.of();
    }

    @Override
    public void subscribe(List<String> symbols) {
        if (!connected.get()) {
            log.warn("[BINANCE:SPOT] Cannot subscribe - not connected");
            return;
        }

        List<String> allStreams = new ArrayList<>();
        for (String s : symbols) {
            allStreams.add(s.toLowerCase() + "@depth@100ms");
            allStreams.add(s.toLowerCase() + "@trade");
        }
        String msg = String.format("{\"method\":\"SUBSCRIBE\",\"params\":%s,\"id\":%d}",
                toJsonArray(allStreams), System.currentTimeMillis());
        webSocket.send(msg);

        subscribedSymbols.addAll(symbols);
        log.debug("[BINANCE:SPOT] Subscribed to {} symbols ({} streams)", symbols.size(), allStreams.size());
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        List<String> allStreams = new ArrayList<>();
        for (String s : symbols) {
            allStreams.add(s.toLowerCase() + "@depth@100ms");
            allStreams.add(s.toLowerCase() + "@trade");
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

        if (root.has("result") && root.has("id")) {
            return;
        }

        String stream = root.has("stream") ? root.get("stream").asText() : null;
        JsonNode data = root.has("data") ? root.get("data") : root;

        if (stream != null) {
            if (stream.contains("@depth")) {
                handleDepthUpdate(data, extractSymbol(stream));
            } else if (stream.contains("@trade")) {
                handleTradeUpdate(data);
            }
        } else {
            String eventType = data.has("e") ? data.get("e").asText() : null;
            if ("trade".equals(eventType)) {
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
        // If still initializing — buffer the event
        if (initializing.containsKey(symbol)) {
            CopyOnWriteArrayList<JsonNode> buffer = eventBuffers.get(symbol);
            if (buffer != null) {
                buffer.add(data);
            }
            return;
        }

        LocalOrderBook book = localBooks.get(symbol);
        if (book == null || !book.isInitialized()) {
            // Not yet initialized — buffer event and trigger snapshot fetch
            eventBuffers.putIfAbsent(symbol, new CopyOnWriteArrayList<>());
            CopyOnWriteArrayList<JsonNode> buffer = eventBuffers.get(symbol);
            if (buffer != null) {
                buffer.add(data);
            }
            if (!initializing.containsKey(symbol)) {
                Thread.startVirtualThread(() -> fetchAndApplySnapshot(symbol));
            }
            return;
        }

        long u = data.get("u").asLong();
        long U = data.get("U").asLong();

        // Gap detection: U should be <= lastUpdateId + 1
        if (U > book.getLastUpdateId() + 1) {
            log.warn("[BINANCE:SPOT] Gap detected for {} (U={}, lastUpdateId={}), re-initializing",
                    symbol, U, book.getLastUpdateId());
            book.reset();
            Thread.startVirtualThread(() -> fetchAndApplySnapshot(symbol));
            return;
        }

        // Skip already applied events
        if (u <= book.getLastUpdateId()) {
            return;
        }

        applyDiffEvent(book, data);
        incrementOrderbookUpdates();
        publishOrderBook(symbol, book);
    }

    private void applyDiffEvent(LocalOrderBook book, JsonNode data) {
        long u = data.get("u").asLong();
        List<List<String>> bids = parseLevelsRaw(data.get("b"));
        List<List<String>> asks = parseLevelsRaw(data.get("a"));

        if (bids.isEmpty()) {
            bids = parseLevelsRaw(data.get("bids"));
        }
        if (asks.isEmpty()) {
            asks = parseLevelsRaw(data.get("asks"));
        }

        book.applyDelta(bids, asks, u);
    }

    private void publishOrderBook(String symbol, LocalOrderBook book) {
        LocalOrderBook.Snapshot snapshot = book.getSnapshot();
        if (!snapshot.bids().isEmpty() || !snapshot.asks().isEmpty()) {
            orderBookManager.updateOrderBook(
                    symbol.toUpperCase(),
                    Exchange.BINANCE,
                    MarketType.SPOT,
                    snapshot.bids(),
                    snapshot.asks(),
                    null
            );
        }
    }

    private void handleTradeUpdate(JsonNode data) {
        String symbol = data.get("s").asText();
        BigDecimal price = new BigDecimal(data.get("p").asText());
        BigDecimal quantity = new BigDecimal(data.get("q").asText());

        incrementTradeUpdates();
        orderBookManager.updateLastPrice(symbol, Exchange.BINANCE, MarketType.SPOT, price);

        volumeTracker.addVolume(
                symbol,
                Exchange.BINANCE,
                MarketType.SPOT,
                price.multiply(quantity)
        );
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
        return null;
    }
}
