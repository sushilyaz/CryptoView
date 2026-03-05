package com.cryptoview.exchange.aster;

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
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AsterFuturesConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://fstream.asterdex.com/stream";
    private static final String REST_URL = "https://fapi.asterdex.com/fapi/v1/exchangeInfo";
    private static final String DEPTH_SNAPSHOT_URL = "https://fapi.asterdex.com/fapi/v1/depth";
    private static final int SNAPSHOT_LIMIT = 1000;
    private static final long SNAPSHOT_FETCH_DELAY_MS = 600;
    private static final long PUBLISH_THROTTLE_MS = 2000;
    private static final int MAX_SYMBOLS = 100; // 200 streams / 2 per symbol
    private static final int MAX_GAP_RETRIES = 3;
    private static final int MAX_PENDING_BUFFER_SIZE = 500;

    private final Map<String, LocalOrderBook> localBooks = new ConcurrentHashMap<>();
    private final Map<String, Queue<JsonNode>> eventBuffers = new ConcurrentHashMap<>();
    private final Set<String> initializing = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastPublishTime = new ConcurrentHashMap<>();
    private final BlockingQueue<String> refetchQueue = new LinkedBlockingQueue<>();
    private final Set<String> refetchPending = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> gapRetryCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> gapCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingBridging = new ConcurrentHashMap<>();
    private volatile CountDownLatch initLatch = new CountDownLatch(1);
    private volatile Thread refetchWorkerThread;

    public AsterFuturesConnector(OkHttpClient httpClient,
                                  ObjectMapper objectMapper,
                                  OrderBookManager orderBookManager,
                                  VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public Exchange getExchange() {
        return Exchange.ASTER;
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
        log.info("[ASTER:FUTURES] WebSocket connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (symbols.isEmpty()) {
            log.error("[ASTER:FUTURES] No symbols found, aborting");
            return;
        }

        if (symbols.size() > MAX_SYMBOLS) {
            log.warn("[ASTER:FUTURES] Limiting from {} to {} symbols", symbols.size(), MAX_SYMBOLS);
            symbols = symbols.subList(0, MAX_SYMBOLS);
        }

        if (!connectAndWait(5000)) {
            log.error("[ASTER:FUTURES] Failed to connect WebSocket, aborting subscribe");
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
        log.info("[ASTER:FUTURES] Subscribed to {} symbols ({} streams)", symbols.size(), symbols.size() * 2);

        List<String> symbolsCopy = List.copyOf(subscribedSymbols);
        Thread.startVirtualThread(() -> fetchSnapshotsForAll(symbolsCopy));
        startRefetchWorker();
    }

    @Override
    protected void onResubscribed() {
        log.info("[ASTER:FUTURES] Reconnected, resetting all local books and refetching snapshots");
        initLatch = new CountDownLatch(1);
        gapRetryCounts.clear();
        refetchPending.clear();
        refetchQueue.clear();
        pendingBridging.clear();
        for (String symbol : subscribedSymbols) {
            LocalOrderBook book = localBooks.get(symbol);
            if (book != null) book.reset();
            initializing.add(symbol);
            eventBuffers.put(symbol, new ConcurrentLinkedQueue<>());
        }
        List<String> symbolsCopy = List.copyOf(subscribedSymbols);
        Thread.startVirtualThread(() -> fetchSnapshotsForAll(symbolsCopy));
    }

    private void startRefetchWorker() {
        if (refetchWorkerThread != null && refetchWorkerThread.isAlive()) return;
        refetchWorkerThread = Thread.startVirtualThread(() -> {
            log.info("[ASTER:FUTURES] Refetch worker started, waiting for initial fetch...");
            try { initLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            log.info("[ASTER:FUTURES] Refetch worker active");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String symbol = refetchQueue.poll(5, TimeUnit.SECONDS);
                    if (symbol != null) {
                        refetchPending.remove(symbol);
                        if (!initializing.contains(symbol)) {
                            initializing.add(symbol);
                            eventBuffers.put(symbol, new ConcurrentLinkedQueue<>());
                        }
                        fetchAndApplySnapshot(symbol);
                        Thread.sleep(SNAPSHOT_FETCH_DELAY_MS);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break;
                } catch (Exception e) { log.error("[ASTER:FUTURES] Refetch worker error", e); }
            }
        });
    }

    // ======================== Snapshot Initialization ========================

    private void fetchSnapshotsForAll(List<String> symbols) {
        log.info("[ASTER:FUTURES] Starting snapshot fetch for {} symbols...", symbols.size());
        int count = 0, failed = 0;
        long startTime = System.currentTimeMillis();
        try {
            for (String symbol : symbols) {
                if (!connected.get()) break;
                try {
                    fetchAndApplySnapshot(symbol);
                    count++;
                    if (count % 20 == 0) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        log.info("[ASTER:FUTURES] Snapshot progress: {}/{}, {}s elapsed", count, symbols.size(), elapsed);
                    }
                    Thread.sleep(SNAPSHOT_FETCH_DELAY_MS);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break;
                } catch (Exception e) { failed++; log.warn("[ASTER:FUTURES] Snapshot failed for {}: {}", symbol, e.getMessage()); }
            }
            log.info("[ASTER:FUTURES] Snapshot fetch complete: {}/{} ok, {} failed, {} books initialized",
                    count, symbols.size(), failed, countInitializedBooks());
        } finally {
            initLatch.countDown();
        }
    }

    private void fetchAndApplySnapshot(String symbol) {
        initializing.add(symbol);
        eventBuffers.putIfAbsent(symbol, new ConcurrentLinkedQueue<>());

        String url = DEPTH_SNAPSHOT_URL + "?symbol=" + symbol + "&limit=" + SNAPSHOT_LIMIT;
        Request request = new Request.Builder().url(url).build();

        try (Response response = executeWithRetry(request, 3, 2000)) {
            if (response.body() == null) {
                initializing.remove(symbol);
                return;
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            long lastUpdateId = root.get("lastUpdateId").asLong();
            List<List<String>> bids = parseLevelsRaw(root.get("bids"));
            List<List<String>> asks = parseLevelsRaw(root.get("asks"));

            LocalOrderBook book = localBooks.computeIfAbsent(symbol, LocalOrderBook::new);
            book.applySnapshot(bids, asks, lastUpdateId);

            // Try to find bridging event (Futures algorithm: U <= lastUpdateId AND u >= lastUpdateId)
            if (tryDrainBuffer(symbol, book, lastUpdateId)) {
                if (!book.isInitialized()) return; // re-queued

                initializing.remove(symbol);
                // Drain tail
                Queue<JsonNode> buffer = eventBuffers.get(symbol);
                if (buffer != null) {
                    JsonNode tailEvent;
                    while ((tailEvent = buffer.poll()) != null) {
                        long u = tailEvent.get("u").asLong();
                        if (u <= book.getLastUpdateId()) continue;
                        long pu = tailEvent.has("pu") ? tailEvent.get("pu").asLong() : -1;
                        if (pu != -1 && pu != book.getLastUpdateId()) {
                            book.reset();
                            eventBuffers.remove(symbol);
                            pendingBridging.remove(symbol);
                            queueRefetch(symbol);
                            return;
                        }
                        applyDiffEvent(book, tailEvent);
                    }
                }
                eventBuffers.remove(symbol);
                pendingBridging.remove(symbol);
                publishOrderBook(symbol, book);
                gapRetryCounts.remove(symbol);
            } else {
                // Bridging not yet in buffer — keep initializing
                pendingBridging.put(symbol, lastUpdateId);
            }
        } catch (IOException e) {
            log.error("[ASTER:FUTURES] Snapshot fetch failed for {}: {}", symbol, e.getMessage());
            initializing.remove(symbol);
            eventBuffers.remove(symbol);
            pendingBridging.remove(symbol);
        }
    }

    private boolean tryDrainBuffer(String symbol, LocalOrderBook book, long snapshotLastUpdateId) {
        Queue<JsonNode> buffer = eventBuffers.get(symbol);
        if (buffer == null || buffer.isEmpty()) return false;

        List<JsonNode> events = new ArrayList<>();
        JsonNode event;
        while ((event = buffer.poll()) != null) events.add(event);

        int bridgingIdx = -1;
        boolean allTooNew = false;
        for (int i = 0; i < events.size(); i++) {
            JsonNode e = events.get(i);
            long u = e.get("u").asLong();
            long U = e.get("U").asLong();
            if (u < snapshotLastUpdateId) continue;
            if (U <= snapshotLastUpdateId && u >= snapshotLastUpdateId) { bridgingIdx = i; break; }
            if (U > snapshotLastUpdateId) { allTooNew = true; break; }
        }

        if (bridgingIdx == -1) {
            if (allTooNew) {
                LocalOrderBook book2 = localBooks.get(symbol);
                if (book2 != null) book2.reset();
                eventBuffers.remove(symbol);
                initializing.remove(symbol);
                pendingBridging.remove(symbol);
                queueRefetch(symbol);
                return true;
            }
            for (JsonNode e : events) buffer.add(e);
            return false;
        }

        long prevU = snapshotLastUpdateId;
        for (int i = bridgingIdx; i < events.size(); i++) {
            JsonNode e = events.get(i);
            long u = e.get("u").asLong();
            long pu = e.has("pu") ? e.get("pu").asLong() : -1;
            if (i > bridgingIdx && pu != -1 && pu != prevU) {
                book.reset();
                eventBuffers.remove(symbol);
                initializing.remove(symbol);
                pendingBridging.remove(symbol);
                queueRefetch(symbol);
                return true;
            }
            applyDiffEvent(book, e);
            prevU = u;
        }
        return true;
    }

    private void queueRefetch(String symbol) {
        int retries = gapRetryCounts.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
        if (retries > MAX_GAP_RETRIES) {
            log.error("[ASTER:FUTURES] {} exceeded max gap retries, giving up", symbol);
            return;
        }
        if (refetchPending.add(symbol)) {
            refetchQueue.offer(symbol);
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
                if (symbols != null && symbols.isArray()) {
                    for (JsonNode symbol : symbols) {
                        String name = symbol.get("symbol").asText();
                        String status = symbol.get("status").asText();
                        String quoteAsset = symbol.has("quoteAsset") ? symbol.get("quoteAsset").asText() : "";
                        String contractType = symbol.has("contractType") ? symbol.get("contractType").asText() : "";
                        if ("TRADING".equals(status) && "USDT".equals(quoteAsset) && "PERPETUAL".equals(contractType)) {
                            usdtSymbols.add(name);
                        }
                    }
                }
                log.info("[ASTER:FUTURES] Found {} perpetual USDT pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[ASTER:FUTURES] Failed to fetch symbols: {}", e.getMessage());
        }
        return List.of();
    }

    // ======================== Subscription ========================

    @Override
    public void subscribe(List<String> symbols) {
        if (!connected.get()) return;
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
        return "[" + items.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]";
    }

    // ======================== Message Handling ========================

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;
        if (root.has("result") && root.has("id")) return;

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
                if (symbol != null) handleDepthUpdate(data, symbol);
            }
        }
    }

    private String extractSymbol(String stream) {
        return stream.split("@")[0].toUpperCase();
    }

    private void handleDepthUpdate(JsonNode data, String symbol) {
        if (initializing.contains(symbol)) {
            Queue<JsonNode> buffer = eventBuffers.get(symbol);
            if (buffer != null) buffer.add(data);

            // Check for bridging event if pending
            Long snapshotId = pendingBridging.get(symbol);
            if (snapshotId != null) {
                long u = data.get("u").asLong();
                long U = data.get("U").asLong();
                boolean couldBeBridging = (U <= snapshotId && u >= snapshotId);
                boolean passedBridgingPoint = (U > snapshotId);

                if (couldBeBridging || passedBridgingPoint) {
                    LocalOrderBook book = localBooks.get(symbol);
                    if (book != null && book.isInitialized()) {
                        if (buffer != null && buffer.size() > MAX_PENDING_BUFFER_SIZE) {
                            book.reset();
                            eventBuffers.remove(symbol);
                            pendingBridging.remove(symbol);
                            queueRefetch(symbol);
                            return;
                        }

                        if (tryDrainBuffer(symbol, book, snapshotId)) {
                            if (!book.isInitialized()) return;
                            initializing.remove(symbol);
                            Queue<JsonNode> buf = eventBuffers.get(symbol);
                            if (buf != null) {
                                JsonNode tailEvent;
                                while ((tailEvent = buf.poll()) != null) {
                                    long tu = tailEvent.get("u").asLong();
                                    if (tu <= book.getLastUpdateId()) continue;
                                    long tpu = tailEvent.has("pu") ? tailEvent.get("pu").asLong() : -1;
                                    if (tpu != -1 && tpu != book.getLastUpdateId()) {
                                        book.reset();
                                        eventBuffers.remove(symbol);
                                        pendingBridging.remove(symbol);
                                        queueRefetch(symbol);
                                        return;
                                    }
                                    applyDiffEvent(book, tailEvent);
                                }
                            }
                            eventBuffers.remove(symbol);
                            pendingBridging.remove(symbol);
                            publishOrderBook(symbol, book);
                            gapRetryCounts.remove(symbol);
                        }
                    }
                }
            }
            return;
        }

        LocalOrderBook book = localBooks.get(symbol);
        if (book == null || !book.isInitialized()) {
            eventBuffers.computeIfAbsent(symbol, k -> new ConcurrentLinkedQueue<>()).add(data);
            return;
        }

        long u = data.get("u").asLong();
        long pu = data.has("pu") ? data.get("pu").asLong() : -1;

        if (u <= book.getLastUpdateId()) return;

        // Gap detection (Futures algorithm: pu should equal lastUpdateId)
        if (pu != -1 && pu != book.getLastUpdateId()) {
            gapCounts.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
            book.reset();
            initializing.add(symbol);
            Queue<JsonNode> buf = new ConcurrentLinkedQueue<>();
            buf.add(data);
            eventBuffers.put(symbol, buf);
            queueRefetch(symbol);
            return;
        }

        applyDiffEvent(book, data);
        incrementOrderbookUpdates();

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
                    symbol.toUpperCase(), Exchange.ASTER, MarketType.FUTURES,
                    snapshot.bids(), snapshot.asks(), null);
        }
    }

    private void handleTradeUpdate(JsonNode data) {
        String symbol = data.has("s") ? data.get("s").asText() : null;
        if (symbol == null) return;
        BigDecimal price = new BigDecimal(data.get("p").asText());
        BigDecimal quantity = new BigDecimal(data.get("q").asText());

        incrementTradeUpdates();
        orderBookManager.updateLastPrice(symbol, Exchange.ASTER, MarketType.FUTURES, price);
        volumeTracker.addVolume(symbol, Exchange.ASTER, MarketType.FUTURES, price.multiply(quantity));
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
        return null; // Aster sends WS ping frames like Binance
    }

    @Override
    public String getStatusSummary() {
        Instant lastMsg = lastMessageTime.get();
        String lastMsgStr = lastMsg != null
                ? java.time.Duration.between(lastMsg, Instant.now()).toSeconds() + "s ago" : "never";
        int initialized = countInitializedBooks();
        int totalGaps = gapCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        return String.format("msgs=%d, errs=%d, ob=%d, trades=%d, last=%s, syms=%d, books=%d/%d, gaps=%d, bridging=%d",
                messagesReceived.get(), messageErrors.get(),
                orderbookUpdates.get(), tradeUpdates.get(),
                lastMsgStr, subscribedSymbols.size(), initialized, localBooks.size(), totalGaps, pendingBridging.size());
    }
}
