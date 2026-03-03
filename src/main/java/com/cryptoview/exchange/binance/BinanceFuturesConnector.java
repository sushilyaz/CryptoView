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
import java.util.Queue;
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
    private static final long SNAPSHOT_FETCH_DELAY_MS = 600; // weight 20 × 100/min = 2000 weight/min (limit 2400)
    private static final long PUBLISH_THROTTLE_MS = 2000; // publish orderbook max once per 2 sec per symbol
    private static final int MAX_SYMBOLS = 512; // 1024 streams / 2 streams per symbol
    private static final int MAX_GAP_RETRIES = 3;
    private static final int MAX_PENDING_BUFFER_SIZE = 500; // safety limit for pending bridging buffer

    private final Map<String, LocalOrderBook> localBooks = new ConcurrentHashMap<>();
    private final Map<String, Queue<JsonNode>> eventBuffers = new ConcurrentHashMap<>();
    private final Set<String> initializing = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastPublishTime = new ConcurrentHashMap<>();
    private final BlockingQueue<String> refetchQueue = new LinkedBlockingQueue<>();
    private final Set<String> refetchPending = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> gapRetryCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> gapCounts = new ConcurrentHashMap<>();
    // Pending bridging: symbols that got snapshot but bridging event not yet in buffer
    // Key = symbol, Value = snapshotLastUpdateId
    private final Map<String, Long> pendingBridging = new ConcurrentHashMap<>();
    private volatile CountDownLatch initLatch = new CountDownLatch(1);

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
        initLatch = new CountDownLatch(1);
        gapRetryCounts.clear();
        refetchPending.clear();
        refetchQueue.clear();
        pendingBridging.clear();
        for (String symbol : subscribedSymbols) {
            LocalOrderBook book = localBooks.get(symbol);
            if (book != null) {
                book.reset();
            }
            initializing.add(symbol);
            eventBuffers.put(symbol, new ConcurrentLinkedQueue<>());
        }
        List<String> symbolsCopy = List.copyOf(subscribedSymbols);
        Thread.startVirtualThread(() -> fetchSnapshotsForAll(symbolsCopy));
    }

    private void startRefetchWorker() {
        if (refetchWorkerThread != null && refetchWorkerThread.isAlive()) {
            return;
        }
        refetchWorkerThread = Thread.startVirtualThread(() -> {
            log.info("[BINANCE:FUTURES] Refetch worker started, waiting for initial fetch to complete...");
            try {
                initLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("[BINANCE:FUTURES] Refetch worker active, processing gap queue");
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

        try {
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
        } finally {
            initLatch.countDown();
            log.info("[BINANCE:FUTURES] Init latch released, refetch worker can now process gap queue");
        }
    }

    private void fetchAndApplySnapshot(String symbol) {
        initializing.add(symbol);
        eventBuffers.putIfAbsent(symbol, new ConcurrentLinkedQueue<>());

        String url = DEPTH_SNAPSHOT_URL + "?symbol=" + symbol + "&limit=" + SNAPSHOT_LIMIT;
        Request request = new Request.Builder().url(url).build();

        try (Response response = executeWithRetry(request, 3, 2000)) {
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

            // Try to find bridging event in buffer and apply chain
            if (tryDrainBuffer(symbol, book, lastUpdateId)) {
                // Bridging found and buffer drained successfully
                // Now transition to runtime: remove initializing, drain tail, cleanup
                initializing.remove(symbol);

                // Drain tail events that arrived between buffer drain and initializing.remove
                Queue<JsonNode> buffer = eventBuffers.get(symbol);
                int tailApplied = 0;
                if (buffer != null) {
                    JsonNode tailEvent;
                    while ((tailEvent = buffer.poll()) != null) {
                        long u = tailEvent.get("u").asLong();
                        if (u <= book.getLastUpdateId()) continue;
                        long pu = tailEvent.has("pu") ? tailEvent.get("pu").asLong() : -1;
                        if (pu != -1 && pu != book.getLastUpdateId()) {
                            log.warn("[BINANCE:FUTURES] Gap in tail for {} (pu={}, expected={}), re-queuing",
                                    symbol, pu, book.getLastUpdateId());
                            book.reset();
                            eventBuffers.remove(symbol);
                            queueRefetch(symbol);
                            return;
                        }
                        applyDiffEvent(book, tailEvent);
                        tailApplied++;
                    }
                }
                eventBuffers.remove(symbol);
                pendingBridging.remove(symbol);
                publishOrderBook(symbol, book);
                gapRetryCounts.remove(symbol);
                log.info("[BINANCE:FUTURES] Initialized {} (snapshotId={}, tail={}, bookLastId={})",
                        symbol, lastUpdateId, tailApplied, book.getLastUpdateId());
            } else {
                // Bridging event NOT in buffer yet — keep initializing, let handleDepthUpdate find it
                pendingBridging.put(symbol, lastUpdateId);
                log.debug("[BINANCE:FUTURES] {} waiting for bridging event (snapshotId={})", symbol, lastUpdateId);
            }
        } catch (IOException e) {
            log.error("[BINANCE:FUTURES] Snapshot fetch failed for {}: {}", symbol, e.getMessage());
            initializing.remove(symbol);
            eventBuffers.remove(symbol);
            pendingBridging.remove(symbol);
        }
    }

    /**
     * Try to find bridging event in buffer and drain all applicable events.
     * Bridging event for Futures: U <= lastUpdateId AND u >= lastUpdateId.
     * Returns true if bridging was found and chain applied, false if bridging not yet in buffer.
     */
    private boolean tryDrainBuffer(String symbol, LocalOrderBook book, long snapshotLastUpdateId) {
        Queue<JsonNode> buffer = eventBuffers.get(symbol);
        if (buffer == null || buffer.isEmpty()) return false;

        // Collect all events from buffer into a list for scanning
        List<JsonNode> events = new ArrayList<>();
        JsonNode event;
        while ((event = buffer.poll()) != null) {
            events.add(event);
        }

        // Find bridging event index
        int bridgingIdx = -1;
        boolean allTooNew = false;
        for (int i = 0; i < events.size(); i++) {
            JsonNode e = events.get(i);
            long u = e.get("u").asLong();
            long U = e.get("U").asLong();
            if (u < snapshotLastUpdateId) continue; // too old, skip
            if (U <= snapshotLastUpdateId && u >= snapshotLastUpdateId) {
                bridgingIdx = i;
                break;
            }
            if (U > snapshotLastUpdateId) {
                // Passed the snapshot point without finding bridging — bridging is lost
                allTooNew = true;
                break;
            }
        }

        if (bridgingIdx == -1) {
            if (allTooNew) {
                // Bridging event will never come — all buffered events are newer than snapshot
                // Need to re-fetch snapshot
                log.warn("[BINANCE:FUTURES] {} bridging lost (all events newer than snapshotId={}), re-queuing",
                        symbol, snapshotLastUpdateId);
                LocalOrderBook book2 = localBooks.get(symbol);
                if (book2 != null) book2.reset();
                eventBuffers.remove(symbol);
                initializing.remove(symbol);
                pendingBridging.remove(symbol);
                queueRefetch(symbol);
                return true; // handled via re-queue
            }
            // No bridging found yet — put events back into buffer for later
            for (JsonNode e : events) {
                buffer.add(e);
            }
            return false;
        }

        // Apply bridging event and all subsequent events with valid pu chain
        long prevU = snapshotLastUpdateId;
        int applied = 0;
        for (int i = bridgingIdx; i < events.size(); i++) {
            JsonNode e = events.get(i);
            long u = e.get("u").asLong();
            long pu = e.has("pu") ? e.get("pu").asLong() : -1;

            if (i > bridgingIdx) {
                // After bridging, validate pu chain
                if (pu != -1 && pu != prevU) {
                    log.warn("[BINANCE:FUTURES] Gap in buffer chain for {} at event #{} (pu={}, expected={}), re-queuing",
                            symbol, i, pu, prevU);
                    book.reset();
                    eventBuffers.remove(symbol);
                    initializing.remove(symbol);
                    pendingBridging.remove(symbol);
                    queueRefetch(symbol);
                    return true; // return true because we handled it (via re-queue)
                }
            }

            applyDiffEvent(book, e);
            prevU = u;
            applied++;
        }

        log.debug("[BINANCE:FUTURES] {} buffer drained: skipped={}, applied={}", symbol, bridgingIdx, applied);
        return true;
    }

    private void queueRefetch(String symbol) {
        int retries = gapRetryCounts.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
        if (retries > MAX_GAP_RETRIES) {
            log.error("[BINANCE:FUTURES] Symbol {} exceeded max gap retries ({}), giving up", symbol, MAX_GAP_RETRIES);
            return;
        }
        if (refetchPending.add(symbol)) {
            refetchQueue.offer(symbol);
            log.debug("[BINANCE:FUTURES] Queued {} for refetch (retry {}/{})", symbol, retries, MAX_GAP_RETRIES);
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
        // If initializing — buffer the event, then try bridging if pending
        if (initializing.contains(symbol)) {
            Queue<JsonNode> buffer = eventBuffers.get(symbol);
            if (buffer != null) {
                buffer.add(data);
            }

            // If we have a snapshot but are waiting for bridging event, check if THIS event is bridging
            Long snapshotId = pendingBridging.get(symbol);
            if (snapshotId != null) {
                long u = data.get("u").asLong();
                long U = data.get("U").asLong();

                // Check if current event could be or contain bridging
                boolean couldBeBridging = (U <= snapshotId && u >= snapshotId);
                boolean passedBridgingPoint = (U > snapshotId);

                if (couldBeBridging || passedBridgingPoint) {
                    LocalOrderBook book = localBooks.get(symbol);
                    if (book != null && book.isInitialized()) {
                        // Safety: if buffer is too large, re-snapshot
                        if (buffer != null && buffer.size() > MAX_PENDING_BUFFER_SIZE) {
                            log.warn("[BINANCE:FUTURES] {} pending buffer overflow ({}), re-queuing snapshot",
                                    symbol, buffer.size());
                            book.reset();
                            eventBuffers.remove(symbol);
                            pendingBridging.remove(symbol);
                            queueRefetch(symbol);
                            return;
                        }

                        if (tryDrainBuffer(symbol, book, snapshotId)) {
                            // Bridging found or handled (re-queued)!
                            // Check if we actually transitioned (book still initialized = success)
                            if (!book.isInitialized()) {
                                // tryDrainBuffer reset the book and re-queued — we're done
                                return;
                            }
                            // Transition to runtime
                            initializing.remove(symbol);

                            // Drain tail
                            Queue<JsonNode> buf = eventBuffers.get(symbol);
                            if (buf != null) {
                                JsonNode tailEvent;
                                while ((tailEvent = buf.poll()) != null) {
                                    long tu = tailEvent.get("u").asLong();
                                    if (tu <= book.getLastUpdateId()) continue;
                                    long tpu = tailEvent.has("pu") ? tailEvent.get("pu").asLong() : -1;
                                    if (tpu != -1 && tpu != book.getLastUpdateId()) {
                                        log.warn("[BINANCE:FUTURES] Gap in tail for {} after bridging (pu={}, expected={}), re-queuing",
                                                symbol, tpu, book.getLastUpdateId());
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
                            log.info("[BINANCE:FUTURES] Initialized {} via WS bridging (snapshotId={}, bookLastId={})",
                                    symbol, snapshotId, book.getLastUpdateId());
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

        // Skip already applied (stale) events
        if (u <= book.getLastUpdateId()) {
            return;
        }

        // Gap detection: pu should equal book's last update id
        if (pu != -1 && pu != book.getLastUpdateId()) {
            int gapCount = gapCounts.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
            log.warn("[BINANCE:FUTURES] Gap #{} for {} (expected pu={}, got pu={}, u={}, delta={}), queuing refetch",
                    gapCount, symbol, book.getLastUpdateId(), pu, u, Math.abs(book.getLastUpdateId() - pu));
            book.reset();
            initializing.add(symbol);
            Queue<JsonNode> buf = new ConcurrentLinkedQueue<>();
            buf.add(data);
            eventBuffers.put(symbol, buf);
            queueRefetch(symbol);
            return;
        }

        // Apply delta
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
        int totalGaps = gapCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        long exhausted = gapRetryCounts.values().stream().filter(c -> c.get() > MAX_GAP_RETRIES).count();
        return String.format("msgs=%d, errs=%d, ob=%d, trades=%d, last=%s, syms=%d, books=%d/%d, gaps=%d, refetchQ=%d, pendingRefetch=%d, pendingBridging=%d, exhausted=%d",
                messagesReceived.get(), messageErrors.get(),
                orderbookUpdates.get(), tradeUpdates.get(),
                lastMsgStr, subscribedSymbols.size(),
                initialized, localBooks.size(), totalGaps, refetchQueue.size(),
                refetchPending.size(), pendingBridging.size(), exhausted);
    }
}
