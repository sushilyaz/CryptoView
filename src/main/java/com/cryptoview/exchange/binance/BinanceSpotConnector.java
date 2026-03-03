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
public class BinanceSpotConnector extends AbstractWebSocketConnector {

    private static final String WS_URL = "wss://stream.binance.com:9443/stream";
    private static final String REST_URL = "https://api.binance.com/api/v3/exchangeInfo";
    private static final String DEPTH_SNAPSHOT_URL = "https://api.binance.com/api/v3/depth";
    private static final int SNAPSHOT_LIMIT = 1000; // weight 10
    private static final long SNAPSHOT_FETCH_DELAY_MS = 500; // weight 10 × 120/min = 1200 weight/min (limit 6000)
    private static final long PUBLISH_THROTTLE_MS = 2000; // publish orderbook max once per 2 sec per symbol
    private static final int MAX_SYMBOLS = 512; // 1024 streams / 2 streams per symbol

    private static final int MAX_GAP_RETRIES = 3;

    // Local orderbooks for diff-based depth management
    private final Map<String, LocalOrderBook> localBooks = new ConcurrentHashMap<>();
    // Buffer for events received before snapshot is ready
    private final Map<String, Queue<JsonNode>> eventBuffers = new ConcurrentHashMap<>();
    // Track symbols currently initializing (fetching snapshot)
    private final Set<String> initializing = ConcurrentHashMap.newKeySet();
    // Throttle: last publish time per symbol
    private final Map<String, Instant> lastPublishTime = new ConcurrentHashMap<>();
    // Queue for symbols that need snapshot refetch (gap detected)
    private final BlockingQueue<String> refetchQueue = new LinkedBlockingQueue<>();
    // Dedup set: symbols already in refetchQueue
    private final Set<String> refetchPending = ConcurrentHashMap.newKeySet();
    // Gap retry counter per symbol
    private final Map<String, AtomicInteger> gapRetryCounts = new ConcurrentHashMap<>();
    // Gap counter per symbol (for logging)
    private final Map<String, AtomicInteger> gapCounts = new ConcurrentHashMap<>();
    // Latch: refetch worker waits until initial fetch completes
    private volatile CountDownLatch initLatch = new CountDownLatch(1);

    private volatile Thread refetchWorkerThread;

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
        log.info("[BINANCE:SPOT] WebSocket connected, ready to subscribe");
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (symbols.isEmpty()) {
            log.error("[BINANCE:SPOT] No symbols found, aborting");
            return;
        }

        if (symbols.size() > MAX_SYMBOLS) {
            log.warn("[BINANCE:SPOT] Limiting from {} to {} symbols (1024 stream limit)", symbols.size(), MAX_SYMBOLS);
            symbols = symbols.subList(0, MAX_SYMBOLS);
        }

        if (!connectAndWait(5000)) {
            log.error("[BINANCE:SPOT] Failed to connect WebSocket, aborting subscribe");
            return;
        }

        // Subscribe in batches
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
        log.info("[BINANCE:SPOT] Subscribed to {} symbols ({} streams)", symbols.size(), symbols.size() * 2);

        // Start sequential snapshot fetcher
        List<String> symbolsCopy = List.copyOf(subscribedSymbols);
        Thread.startVirtualThread(() -> fetchSnapshotsForAll(symbolsCopy));

        // Start refetch worker for gap recovery
        startRefetchWorker();
    }

    @Override
    protected void onResubscribed() {
        // After reconnect, reset all books and refetch snapshots
        log.info("[BINANCE:SPOT] Reconnected, resetting all local books and refetching snapshots");
        initLatch = new CountDownLatch(1);
        gapRetryCounts.clear();
        refetchPending.clear();
        refetchQueue.clear();
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
            log.info("[BINANCE:SPOT] Refetch worker started, waiting for initial fetch to complete...");
            try {
                initLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("[BINANCE:SPOT] Refetch worker active, processing gap queue");
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
                    log.error("[BINANCE:SPOT] Refetch worker error", e);
                }
            }
            log.info("[BINANCE:SPOT] Refetch worker stopped");
        });
    }

    // ======================== Snapshot Initialization ========================

    private void fetchSnapshotsForAll(List<String> symbols) {
        log.info("[BINANCE:SPOT] Starting snapshot fetch for {} symbols (limit={}, delay={}ms)...",
                symbols.size(), SNAPSHOT_LIMIT, SNAPSHOT_FETCH_DELAY_MS);
        int count = 0;
        int failed = 0;
        long startTime = System.currentTimeMillis();

        try {
            for (String symbol : symbols) {
                if (!connected.get()) {
                    log.warn("[BINANCE:SPOT] Connection lost during snapshot fetch, stopping at {}/{}", count, symbols.size());
                    break;
                }
                try {
                    fetchAndApplySnapshot(symbol);
                    count++;
                    if (count % 50 == 0) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        int initialized = countInitializedBooks();
                        log.info("[BINANCE:SPOT] Snapshot progress: {}/{} fetched, {} failed, {} initialized, {}s elapsed",
                                count, symbols.size(), failed, initialized, elapsed);
                    }
                    Thread.sleep(SNAPSHOT_FETCH_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    failed++;
                    log.warn("[BINANCE:SPOT] Failed to fetch snapshot for {}: {}", symbol, e.getMessage());
                }
            }

            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            log.info("[BINANCE:SPOT] Snapshot fetch complete: {}/{} ok, {} failed, {}s total, {} books initialized",
                    count, symbols.size(), failed, elapsed, countInitializedBooks());
        } finally {
            initLatch.countDown();
            log.info("[BINANCE:SPOT] Init latch released, refetch worker can now process gap queue");
        }
    }

    private void fetchAndApplySnapshot(String symbol) {
        initializing.add(symbol);
        eventBuffers.putIfAbsent(symbol, new ConcurrentLinkedQueue<>());

        String url = DEPTH_SNAPSHOT_URL + "?symbol=" + symbol + "&limit=" + SNAPSHOT_LIMIT;
        Request request = new Request.Builder().url(url).build();

        try (Response response = executeWithRetry(request, 3, 2000)) {
            if (response.body() == null) {
                log.warn("[BINANCE:SPOT] Empty response body for snapshot {}", symbol);
                initializing.remove(symbol);
                return;
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            long lastUpdateId = root.get("lastUpdateId").asLong();
            List<List<String>> bids = parseLevelsRaw(root.get("bids"));
            List<List<String>> asks = parseLevelsRaw(root.get("asks"));

            LocalOrderBook book = localBooks.computeIfAbsent(symbol, LocalOrderBook::new);
            book.applySnapshot(bids, asks, lastUpdateId);

            // Phase 1: Drain buffer while initializing flag is set
            // handleDepthUpdate sees initializing=true → adds to buffer (not lost)
            Queue<JsonNode> buffer = eventBuffers.get(symbol);
            if (buffer != null) {
                int applied = 0;
                boolean foundFirst = false;
                JsonNode event;

                while ((event = buffer.poll()) != null) {
                    long u = event.get("u").asLong();
                    long U = event.get("U").asLong();

                    if (u <= lastUpdateId) continue;

                    if (!foundFirst) {
                        if (U <= lastUpdateId + 1 && u >= lastUpdateId + 1) {
                            foundFirst = true;
                        } else if (U > lastUpdateId + 1) {
                            log.warn("[BINANCE:SPOT] Gap after snapshot for {} (snapshotId={}, firstEventU={}, delta={}), re-queuing",
                                    symbol, lastUpdateId, U, U - lastUpdateId - 1);
                            book.reset();
                            eventBuffers.remove(symbol);
                            initializing.remove(symbol);
                            queueRefetch(symbol);
                            return;
                        } else {
                            continue;
                        }
                    }

                    applyDiffEvent(book, event);
                    applied++;
                }

                if (applied > 0) {
                    log.debug("[BINANCE:SPOT] {} buffered events applied for {}", applied, symbol);
                }
            }

            // Phase 2: Remove initializing flag — handleDepthUpdate now goes to runtime path
            initializing.remove(symbol);

            // Phase 3: Drain any events that arrived between phase 1 drain and initializing.remove
            if (buffer != null) {
                JsonNode tailEvent;
                while ((tailEvent = buffer.poll()) != null) {
                    long u = tailEvent.get("u").asLong();
                    long U = tailEvent.get("U").asLong();
                    if (u <= book.getLastUpdateId()) continue;
                    if (U > book.getLastUpdateId() + 1) {
                        log.warn("[BINANCE:SPOT] Gap in tail events for {} (expected U<={}, got U={}), re-queuing",
                                symbol, book.getLastUpdateId() + 1, U);
                        book.reset();
                        eventBuffers.remove(symbol);
                        queueRefetch(symbol);
                        return;
                    }
                    applyDiffEvent(book, tailEvent);
                }
            }

            // Cleanup buffer entry
            eventBuffers.remove(symbol);

            publishOrderBook(symbol, book);
            gapRetryCounts.remove(symbol);

            log.debug("[BINANCE:SPOT] Initialized {} ({} bids + {} asks, lastUpdateId={})",
                    symbol, book.getBidCount(), book.getAskCount(), lastUpdateId);
        } catch (IOException e) {
            log.error("[BINANCE:SPOT] Snapshot fetch failed for {}: {}", symbol, e.getMessage());
            initializing.remove(symbol);
            eventBuffers.remove(symbol);
        }
    }

    private void queueRefetch(String symbol) {
        int retries = gapRetryCounts.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
        if (retries > MAX_GAP_RETRIES) {
            log.error("[BINANCE:SPOT] Symbol {} exceeded max gap retries ({}), giving up", symbol, MAX_GAP_RETRIES);
            return;
        }
        if (refetchPending.add(symbol)) {
            refetchQueue.offer(symbol);
            log.debug("[BINANCE:SPOT] Queued {} for refetch (retry {}/{})", symbol, retries, MAX_GAP_RETRIES);
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

                    if ("TRADING".equals(status) && "USDT".equals(quoteAsset)) {
                        usdtSymbols.add(name);
                    }
                }

                log.info("[BINANCE:SPOT] Found {} USDT trading pairs", usdtSymbols.size());
                return usdtSymbols;
            }
        } catch (IOException e) {
            log.error("[BINANCE:SPOT] Failed to fetch symbols: {}", e.getMessage());
        }

        return List.of();
    }

    // ======================== Subscription ========================

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

    // ======================== Message Handling ========================

    @Override
    protected void handleMessage(String message) {
        JsonNode root = parseJson(message);
        if (root == null) return;

        // Skip subscription confirmation
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
        // If initializing — buffer the event
        if (initializing.contains(symbol)) {
            Queue<JsonNode> buffer = eventBuffers.get(symbol);
            if (buffer != null) {
                buffer.add(data);
            }
            return;
        }

        LocalOrderBook book = localBooks.get(symbol);
        if (book == null || !book.isInitialized()) {
            // Not yet initialized — buffer, fetchSnapshotsForAll will process it
            eventBuffers.computeIfAbsent(symbol, k -> new ConcurrentLinkedQueue<>()).add(data);
            return;
        }

        long u = data.get("u").asLong();
        long U = data.get("U").asLong();

        // Gap detection: U should be <= lastUpdateId + 1
        if (U > book.getLastUpdateId() + 1) {
            int gapCount = gapCounts.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
            log.warn("[BINANCE:SPOT] Gap #{} for {} (expected U<={}, got U={}, delta={}), queuing refetch",
                    gapCount, symbol, book.getLastUpdateId() + 1, U, U - book.getLastUpdateId() - 1);
            book.reset();
            initializing.add(symbol);
            Queue<JsonNode> buffer = new ConcurrentLinkedQueue<>();
            buffer.add(data);
            eventBuffers.put(symbol, buffer);
            queueRefetch(symbol);
            return;
        }

        // Skip already applied events
        if (u <= book.getLastUpdateId()) {
            return;
        }

        // Apply delta — always keep local book up-to-date
        applyDiffEvent(book, data);
        incrementOrderbookUpdates();

        // Throttled publish: only publish if enough time has passed
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

        // Fallback field names (snapshot format uses "bids"/"asks")
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
        // Binance sends WebSocket ping frames; OkHttp responds with pong automatically.
        // No application-level ping needed.
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
        return String.format("msgs=%d, errs=%d, ob=%d, trades=%d, last=%s, syms=%d, books=%d/%d, gaps=%d, refetchQ=%d, pendingRefetch=%d, exhausted=%d",
                messagesReceived.get(), messageErrors.get(),
                orderbookUpdates.get(), tradeUpdates.get(),
                lastMsgStr, subscribedSymbols.size(),
                initialized, localBooks.size(), totalGaps, refetchQueue.size(),
                refetchPending.size(), exhausted);
    }
}
