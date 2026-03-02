package com.cryptoview.exchange.binance;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BinanceFuturesConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://fstream.binance.com/stream";
    private static final String REST_URL = "https://fapi.binance.com/fapi/v1/exchangeInfo";
    private static final String DEPTH_SNAPSHOT_URL = "https://fapi.binance.com/fapi/v1/depth";
    private static final int SNAPSHOT_LIMIT = 1000; // weight 20 for futures
    private static final long SNAPSHOT_FETCH_DELAY_MS = 1000; // 60 req/min at weight 20, IP limit 2400/min
    private static final long PUBLISH_THROTTLE_MS = 2000; // publish orderbook max once per 2 sec per symbol
    private static final int MAX_SYMBOLS = 512; // 1024 streams / 2 streams per symbol

    private final Map<String, LocalOrderBook> localBooks = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<JsonNode>> eventBuffers = new ConcurrentHashMap<>();
    private final Set<String> initializing = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastPublishTime = new ConcurrentHashMap<>();
    private final BlockingQueue<String> refetchQueue = new LinkedBlockingQueue<>();
    private final Map<String, AtomicInteger> gapCounts = new ConcurrentHashMap<>();

    private volatile Thread refetchWorkerThread;

    public BinanceFuturesConnector(OkHttpClient httpClient,
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
        return MarketType.FUTURES;
    }

    @Override
    protected String getWebSocketUrl() {
        return WS_URL;
    }

    @Override
    protected void onConnected() {
        log.info("[BINANCE:FUTURES] WebSocket connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (symbols.isEmpty()) {
            log.error("[BINANCE:FUTURES] No symbols found, aborting");
            return;
        }

        if (symbols.size() > MAX_SYMBOLS) {
            log.warn("[BINANCE:FUTURES] Limiting from {} to {} symbols (1024 stream limit)", symbols.size(), MAX_SYMBOLS);
            symbols = symbols.subList(0, MAX_SYMBOLS);
        }

        if (!connectAndWait(5000)) {
            log.error("[BINANCE:FUTURES] Failed to connect WebSocket, aborting subscribe");
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
        log.info("[BINANCE:FUTURES] Subscribed to {} symbols ({} streams)", symbols.size(), symbols.size() * 2);

List<String> symbolsCopy = List.copyOf(subscribedSymbols);
        Thread.startVirtualThread(() -> fetchSnapshotsForAll(symbolsCopy));

        startRefetchWorker();
    }

    @Override
    protected void onResubscribed() {
        log.info("[BINANCE:FUTURES] Reconnected, resetting all local books and refetching snapshots");
        for (String symbol : subscribedSymbols) {
            LocalOrderBook book = localBooks.get(symbol);
            if (book != null) {
                book.reset();
            }
            initializing.add(symbol);
            eventBuffers.put(symbol, new CopyOnWriteArrayList<>());
        }
        List<String> symbolsCopy = List.copyOf(subscribedSymbols);
        Thread.startVirtualThread(() -> fetchSnapshotsForAll(symbolsCopy));
    }

    private void startRefetchWorker() {
        if (refetchWorkerThread != null && refetchWorkerThread.isAlive()) {
            return;
        }
        refetchWorkerThread = Thread.startVirtualThread(() -> {
            log.info("[BINANCE:FUTURES] Refetch worker started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String symbol = refetchQueue.poll(5, TimeUnit.SECONDS);
                    if (symbol != null) {
                        if (!initializing.contains(symbol)) {
                            initializing.add(symbol);
                            eventBuffers.put(symbol, new CopyOnWriteArrayList<>());
                        }
                        fetchAndApplySnapshot(symbol);
                        Thread.sleep(SNAPSHOT_FETCH_DELAY_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[BINANCE:FUTURES] Refetch worker error", e);
                }
            }
            log.info("[BINANCE:FUTURES] Refetch worker stopped");
        });
    }

    // ======================== Snapshot Initialization ========================

    private void fetchSnapshotsForAll(List<String> symbols) {
        log.info("[BINANCE:FUTURES] Starting snapshot fetch for {} symbols (limit={}, delay={}ms)...",
                symbols.size(), SNAPSHOT_LIMIT, SNAPSHOT_FETCH_DELAY_MS);
        int count = 0;
        int failed = 0;
        long startTime = System.currentTimeMillis();

        for (String symbol : symbols) {
            if (!connected.get()) {
                log.warn("[BINANCE:FUTURES] Connection lost during snapshot fetch, stopping at {}/{}", count, symbols.size());
                break;
            }
            try {
                fetchAndApplySnapshot(symbol);
                count++;
                if (count % 50 == 0) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    int initialized = countInitializedBooks();
                    log.info("[BINANCE:FUTURES] Snapshot progress: {}/{} fetched, {} failed, {} initialized, {}s elapsed",
                            count, symbols.size(), failed, initialized, elapsed);
                }
                Thread.sleep(SNAPSHOT_FETCH_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failed++;
                log.warn("[BINANCE:FUTURES] Failed to fetch snapshot for {}: {}", symbol, e.getMessage());
            }
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        log.info("[BINANCE:FUTURES] Snapshot fetch complete: {}/{} ok, {} failed, {}s total, {} books initialized",
                count, symbols.size(), failed, elapsed, countInitializedBooks());
    }

    private void fetchAndApplySnapshot(String symbol) {
        initializing.add(symbol);
        eventBuffers.putIfAbsent(symbol, new CopyOnWriteArrayList<>());

        String url = DEPTH_SNAPSHOT_URL + "?symbol=" + symbol + "&limit=" + SNAPSHOT_LIMIT;
        Request request = new Request.Builder().url(url).build();

        try (Response response = executeWithRetry(request, 3, 5000)) {
            if (response.body() == null) {
                log.warn("[BINANCE:FUTURES] Empty response body for snapshot {}", symbol);
                initializing.remove(symbol);
                return;
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            long lastUpdateId = root.get("lastUpdateId").asLong();
            List<List<String>> bids = parseLevelsRaw(root.get("bids"));
            List<List<String>> asks = parseLevelsRaw(root.get("asks"));

            LocalOrderBook book = localBooks.computeIfAbsent(symbol, LocalOrderBook::new);
            book.applySnapshot(bids, asks, lastUpdateId);

            // Apply buffered events (Futures algorithm):
            // 1. Drop events where u < lastUpdateId
            // 2. First valid event must have U <= lastUpdateId AND u >= lastUpdateId
            // 3. After first: validate pu chain (each pu == previous u)
            CopyOnWriteArrayList<JsonNode> buffer = eventBuffers.remove(symbol);
            if (buffer != null && !buffer.isEmpty()) {
                boolean foundFirst = false;
                long prevU = lastUpdateId;

                for (JsonNode event : buffer) {
                    long u = event.get("u").asLong();
                    long U = event.get("U").asLong();
                    long pu = event.has("pu") ? event.get("pu").asLong() : -1;

                    // Drop events before snapshot
                    if (u < lastUpdateId) continue;

                    if (!foundFirst) {
                        // First event: U <= lastUpdateId AND u >= lastUpdateId
                        if (U <= lastUpdateId && u >= lastUpdateId) {
                            foundFirst = true;
                            prevU = u;
                            applyDiffEvent(book, event);
                            continue;
                        } else {
                            // Skip events that don't bridge the snapshot
                            continue;
                        }
                    }

                    // Subsequent events: validate pu chain
                    if (pu != -1 && pu != prevU) {
                        log.warn("[BINANCE:FUTURES] Gap in buffered events for {} (pu={}, expectedPu={}), re-queuing",
                                symbol, pu, prevU);
                        book.reset();
                        initializing.remove(symbol);
                        refetchQueue.offer(symbol);
                        return;
                    }

                    applyDiffEvent(book, event);
                    prevU = u;
                }
            }

            publishOrderBook(symbol, book);
            initializing.remove(symbol);

            log.debug("[BINANCE:FUTURES] Initialized {} ({} bids + {} asks, lastUpdateId={})",
                    symbol, book.getBidCount(), book.getAskCount(), lastUpdateId);
        } catch (IOException e) {
            log.error("[BINANCE:FUTURES] Snapshot fetch failed for {}: {}", symbol, e.getMessage());
            initializing.remove(symbol);
        }
    }

    // ======================== Symbol Discovery ========================

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
                    String contractType = symbol.has("contractType") ?
                            symbol.get("contractType").asText() : "";

                    if ("TRADING".equals(status) && "USDT".equals(quoteAsset)
                            && "PERPETUAL".equals(contractType)) {
                        usdtSymbols.add(name);
                    }
                }

                log.info("[BINANCE:FUTURES] Found {} perpetual USDT pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[BINANCE:FUTURES] Failed to fetch symbols: {}", e.getMessage());
        }

        return List.of();
    }

    // ======================== Subscription ========================

    @Override
    public void subscribe(List<String> symbols) {
        if (!connected.get()) {
            log.warn("[BINANCE:FUTURES] Cannot subscribe - not connected");
            return;
        }

        List<String> allStreams = new ArrayList<>();
        for (String s : symbols) {
            allStreams.add(s.toLowerCase() + "@depth@500ms");
            allStreams.add(s.toLowerCase() + "@aggTrade");
        }
        String msg = String.format("{\"method\":\"SUBSCRIBE\",\"params\":%s,\"id\":%d}",
                toJsonArray(allStreams), System.currentTimeMillis());
        webSocket.send(msg);

        subscribedSymbols.addAll(symbols);
    }

    @Override
    protected String buildSubscribeMessage(List<String> symbols) {
        List<String> allStreams = new ArrayList<>();
        for (String s : symbols) {
            allStreams.add(s.toLowerCase() + "@depth@500ms");
            allStreams.add(s.toLowerCase() + "@aggTrade");
        }
        return String.format("{\"method\":\"SUBSCRIBE\",\"params\":%s,\"id\":%d}",
                toJsonArray(allStreams), System.currentTimeMillis());
    }

    private String toJsonArray(List<String> items) {
        return "[" + items.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(",")) + "]";
    }

    // ======================== Message Handling ========================

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
            } else if (stream.contains("@aggTrade")) {
                handleTradeUpdate(data);
            }
        } else {
            String eventType = data.has("e") ? data.get("e").asText() : null;
            if ("aggTrade".equals(eventType)) {
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
        // If initializing — buffer the event
        if (initializing.contains(symbol)) {
            CopyOnWriteArrayList<JsonNode> buffer = eventBuffers.get(symbol);
            if (buffer != null) {
                buffer.add(data);
            }
            return;
        }

        LocalOrderBook book = localBooks.get(symbol);
        if (book == null || !book.isInitialized()) {
            eventBuffers.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>()).add(data);
            return;
        }

        long u = data.get("u").asLong();
        // Futures uses pu (previous update id) for continuity chain
        long pu = data.has("pu") ? data.get("pu").asLong() : -1;

        // Gap detection: pu should equal book's last update id
        if (pu != -1 && pu != book.getLastUpdateId()) {
            int gapCount = gapCounts.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
            log.warn("[BINANCE:FUTURES] Gap #{} for {} (pu={}, lastUpdateId={}), queuing refetch",
                    gapCount, symbol, pu, book.getLastUpdateId());
            book.reset();
            initializing.add(symbol);
            eventBuffers.put(symbol, new CopyOnWriteArrayList<>());
            eventBuffers.get(symbol).add(data);
            refetchQueue.offer(symbol);
            return;
        }

        // Skip already applied events
        if (u <= book.getLastUpdateId()) {
            return;
        }

        // Apply delta — always keep local book up-to-date
        applyDiffEvent(book, data);
        incrementOrderbookUpdates();

        // Throttled publish
        Instant lastPublish = lastPublishTime.get(symbol);
        Instant now = Instant.now();
        if (lastPublish == null || java.time.Duration.between(lastPublish, now).toMillis() >= PUBLISH_THROTTLE_MS) {
            publishOrderBook(symbol, book);
            lastPublishTime.put(symbol, now);
        }
    }

    private void applyDiffEvent(LocalOrderBook book, JsonNode data) {
        long u = data.get("u").asLong();
        List<List<String>> bids = parseLevelsRaw(data.get("b"));
        List<List<String>> asks = parseLevelsRaw(data.get("a"));

        if (bids.isEmpty()) bids = parseLevelsRaw(data.get("bids"));
        if (asks.isEmpty()) asks = parseLevelsRaw(data.get("asks"));

        book.applyDelta(bids, asks, u);
    }

    private void publishOrderBook(String symbol, LocalOrderBook book) {
        LocalOrderBook.Snapshot snapshot = book.getSnapshot();
        if (!snapshot.bids().isEmpty() || !snapshot.asks().isEmpty()) {
            orderBookManager.updateOrderBook(
                    symbol.toUpperCase(),
                    Exchange.BINANCE,
                    MarketType.FUTURES,
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
        orderBookManager.updateLastPrice(symbol, Exchange.BINANCE, MarketType.FUTURES, price);

        volumeTracker.addVolume(
                symbol,
                Exchange.BINANCE,
                MarketType.FUTURES,
                price.multiply(quantity)
        );
    }

    // ======================== Utilities ========================

    private List<List<String>> parseLevelsRaw(JsonNode levels) {
        List<List<String>> result = new ArrayList<>();
        if (levels == null || !levels.isArray()) return result;
        for (JsonNode level : levels) {
            result.add(List.of(level.get(0).asText(), level.get(1).asText()));
        }
        return result;
    }

    private int countInitializedBooks() {
        return (int) localBooks.values().stream().filter(LocalOrderBook::isInitialized).count();
    }

    @Override
    protected String getPingMessage() {
        // Binance Futures sends ping frames every 3 min; OkHttp responds automatically.
        return null;
    }

    @Override
    public String getStatusSummary() {
        Instant lastMsg = lastMessageTime.get();
        String lastMsgStr = lastMsg != null
                ? java.time.Duration.between(lastMsg, Instant.now()).toSeconds() + "s ago"
                : "never";
        int initialized = countInitializedBooks();
        int pending = initializing.size();
        int totalGaps = gapCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        return String.format("msgs=%d, errs=%d, ob=%d, trades=%d, last=%s, syms=%d, books=%d/%d, gaps=%d, refetchQ=%d",
                messagesReceived.get(), messageErrors.get(),
                orderbookUpdates.get(), tradeUpdates.get(),
                lastMsgStr, subscribedSymbols.size(),
                initialized, localBooks.size(), totalGaps, refetchQueue.size());
    }
}
