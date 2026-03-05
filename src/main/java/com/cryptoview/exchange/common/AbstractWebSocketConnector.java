package com.cryptoview.exchange.common;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.service.orderbook.OrderBookManager;
import com.cryptoview.service.volume.VolumeTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public abstract class AbstractWebSocketConnector implements ExchangeConnector {

    protected final OkHttpClient httpClient;
    protected final ObjectMapper objectMapper;
    protected final OrderBookManager orderBookManager;
    protected final VolumeTracker volumeTracker;

    protected WebSocket webSocket;
    protected final AtomicBoolean connected = new AtomicBoolean(false);
    protected final AtomicBoolean connecting = new AtomicBoolean(false);
    protected final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    protected final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    // Metrics
    protected final AtomicLong messagesReceived = new AtomicLong(0);
    protected final AtomicLong messageErrors = new AtomicLong(0);
    protected final AtomicLong orderbookUpdates = new AtomicLong(0);
    protected final AtomicLong tradeUpdates = new AtomicLong(0);
    protected final AtomicReference<Instant> lastMessageTime = new AtomicReference<>();
    protected final AtomicReference<Instant> connectionStartTime = new AtomicReference<>();
    private final AtomicInteger debugMessageCount = new AtomicInteger(0);
    private static final int DEBUG_MESSAGE_LIMIT = 3;

    protected final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    protected ScheduledFuture<?> reconnectTask;
    protected ScheduledFuture<?> pingTask;

    private static final int MAX_RECONNECT_ATTEMPTS = 10; // before switching to periodic reconnect
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60000;
    private static final long PERIODIC_RECONNECT_CHECK_MS = 300_000; // 5 min
    private static final long STALE_DATA_THRESHOLD_MS = 300_000; // 5 minutes (OkHttp handles ping/pong automatically)
    private static final long MAX_CONNECTION_LIFETIME_MS = 23 * 60 * 60 * 1000L + 55 * 60 * 1000L; // 23h 55min

    protected AbstractWebSocketConnector(OkHttpClient httpClient,
                                          ObjectMapper objectMapper,
                                          OrderBookManager orderBookManager,
                                          VolumeTracker volumeTracker) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.orderBookManager = orderBookManager;
        this.volumeTracker = volumeTracker;
    }

    protected abstract String getWebSocketUrl();

    protected abstract void handleMessage(String message);

    protected abstract String buildSubscribeMessage(List<String> symbols);

    protected abstract void onConnected();

    protected boolean isPongMessage(String text) {
        if (text == null) return false;
        String trimmed = text.trim().toLowerCase();
        return "pong".equals(trimmed)
                || trimmed.contains("\"pong\"")
                || trimmed.contains("\"msg\":\"pong\"");
    }

    protected String getPingMessage() {
        return "ping";
    }

    protected long getPingIntervalMs() {
        return 30000;
    }

    @Override
    public void connect() {
        if (connected.get() || connecting.get()) {
            return;
        }

        connecting.set(true);
        log.info("[{}:{}] Connecting to WebSocket...", getExchange(), getMarketType());

        Request request = new Request.Builder()
                .url(getWebSocketUrl())
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                log.info("[{}:{}] WebSocket connected", getExchange(), getMarketType());
                connected.set(true);
                connecting.set(false);
                reconnectAttempts.set(0);
                connectionStartTime.set(Instant.now());
                debugMessageCount.set(0);
                onConnected();
                startPingTask();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    messagesReceived.incrementAndGet();
                    lastMessageTime.set(Instant.now());

                    if (isPongMessage(text)) {
                        return;
                    }

                    // Log first N messages per connector for debugging
                    if (debugMessageCount.getAndIncrement() < DEBUG_MESSAGE_LIMIT) {
                        String preview = text.length() > 300 ? text.substring(0, 300) + "..." : text;
                        log.info("[{}:{}] RAW MSG #{}: {}", getExchange(), getMarketType(),
                                debugMessageCount.get(), preview);
                    }

                    handleMessage(text);
                } catch (Exception e) {
                    messageErrors.incrementAndGet();
                    String preview = text != null && text.length() > 200
                            ? text.substring(0, 200) + "..." : text;
                    log.error("[{}:{}] Error handling message: {} | msg: {}",
                            getExchange(), getMarketType(), e.getMessage(), preview);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.error("[{}:{}] WebSocket failure",
                        getExchange(), getMarketType(), t);
                handleDisconnect();
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                log.info("[{}:{}] WebSocket closing: {} - {}",
                        getExchange(), getMarketType(), code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                log.info("[{}:{}] WebSocket closed: {} - {}",
                        getExchange(), getMarketType(), code, reason);
                handleDisconnect();
            }
        });
    }

    protected boolean connectAndWait(long timeoutMs) {
        connect();
        long waited = 0;
        long step = 100;
        while (!connected.get() && waited < timeoutMs) {
            try {
                Thread.sleep(step);
                waited += step;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return connected.get();
    }

    @Override
    public void disconnect() {
        log.info("[{}:{}] Disconnecting...", getExchange(), getMarketType());
        stopPingTask();
        cancelReconnect();

        if (webSocket != null) {
            webSocket.close(1000, "Normal closure");
            webSocket = null;
        }

        connected.set(false);
        connecting.set(false);
    }

    @Override
    public void subscribe(List<String> symbols) {
        if (!connected.get()) {
            log.warn("[{}:{}] Cannot subscribe - not connected", getExchange(), getMarketType());
            return;
        }

        String message = buildSubscribeMessage(symbols);
        if (message != null && !message.isEmpty()) {
            webSocket.send(message);
            subscribedSymbols.addAll(symbols);
            log.debug("[{}:{}] Subscribed to {} symbols", getExchange(), getMarketType(), symbols.size());
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public int getSubscribedSymbolsCount() {
        return subscribedSymbols.size();
    }

    @Override
    public Set<String> getSubscribedSymbols() {
        return Set.copyOf(subscribedSymbols);
    }

    protected void handleDisconnect() {
        connected.set(false);
        connecting.set(false);
        stopPingTask();
        scheduleReconnect();
    }

    protected void scheduleReconnect() {
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        long delay;
        if (attempts <= MAX_RECONNECT_ATTEMPTS) {
            delay = Math.min(INITIAL_RECONNECT_DELAY_MS * (1L << (attempts - 1)), MAX_RECONNECT_DELAY_MS);
        } else {
            // After exhausting exponential backoff, switch to periodic reconnect every 5 min
            delay = PERIODIC_RECONNECT_CHECK_MS;
        }

        log.info("[{}:{}] Scheduling reconnect in {}ms (attempt {}{})",
                getExchange(), getMarketType(), delay, attempts,
                attempts > MAX_RECONNECT_ATTEMPTS ? ", periodic mode" : "");

        reconnectTask = scheduler.schedule(() -> {
            if (connectAndWait(5000) && !subscribedSymbols.isEmpty()) {
                resubscribeAll();
            } else if (!connected.get()) {
                // Connection failed — reschedule
                scheduleReconnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    protected void resubscribeAll() {
        List<String> symbols = List.copyOf(subscribedSymbols);
        int batchSize = getResubscribeBatchSize();
        for (int i = 0; i < symbols.size(); i += batchSize) {
            int end = Math.min(i + batchSize, symbols.size());
            subscribe(symbols.subList(i, end));
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("[{}:{}] Resubscribed to {} symbols", getExchange(), getMarketType(), symbols.size());
        onResubscribed();
    }

    /**
     * Called after resubscription completes. Override to trigger snapshot refetch etc.
     */
    protected void onResubscribed() {
        // Default: no-op. Subclasses can override.
    }

    protected int getResubscribeBatchSize() {
        return 50;
    }

    protected void cancelReconnect() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    protected void startPingTask() {
        stopPingTask();
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            if (connected.get() && webSocket != null) {
                // Send application-level ping (if connector needs it)
                String ping = getPingMessage();
                if (ping != null) {
                    webSocket.send(ping);
                }

                Instant now = Instant.now();

                // 24h connection lifetime check — reconnect before Binance kills it
                Instant connStart = connectionStartTime.get();
                if (connStart != null) {
                    long connAgeMs = java.time.Duration.between(connStart, now).toMillis();
                    if (connAgeMs > MAX_CONNECTION_LIFETIME_MS) {
                        log.info("[{}:{}] Connection approaching 24h limit (age={}h {}m), initiating graceful reconnect",
                                getExchange(), getMarketType(),
                                connAgeMs / 3600000, (connAgeMs % 3600000) / 60000);
                        if (webSocket != null) {
                            webSocket.close(1000, "24h lifetime reconnect");
                        }
                        return;
                    }
                }

                // Stale data detection
                Instant lastMsg = lastMessageTime.get();
                if (lastMsg != null && !subscribedSymbols.isEmpty()) {
                    long silenceMs = java.time.Duration.between(lastMsg, now).toMillis();
                    if (silenceMs > STALE_DATA_THRESHOLD_MS) {
                        log.warn("[{}:{}] Stale connection detected (no data for {}s), forcing reconnect",
                                getExchange(), getMarketType(), silenceMs / 1000);
                        if (webSocket != null) {
                            webSocket.close(1000, "Stale data reconnect");
                        }
                    }
                }
            }
        }, getPingIntervalMs(), getPingIntervalMs(), TimeUnit.MILLISECONDS);
    }

    protected void stopPingTask() {
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
    }

    protected void send(String message) {
        if (connected.get() && webSocket != null) {
            webSocket.send(message);
        }
    }

    protected JsonNode parseJson(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (Exception e) {
            String preview = message != null && message.length() > 200
                    ? message.substring(0, 200) + "..."
                    : message;
            log.error("[{}:{}] Failed to parse JSON: {} | message: {}",
                    getExchange(), getMarketType(), e.getMessage(), preview);
            return null;
        }
    }

    protected Response executeWithRetry(Request request, int maxRetries, long retryDelayMs) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Response response = httpClient.newCall(request).execute();
                if (response.isSuccessful()) {
                    return response;
                }
                int code = response.code();
                if (code == 429) {
                    long waitMs = retryDelayMs;
                    String retryAfter = response.header("Retry-After");
                    if (retryAfter != null) {
                        try {
                            waitMs = Math.max(Long.parseLong(retryAfter) * 1000, retryDelayMs);
                        } catch (NumberFormatException ignored) {}
                    } else {
                        waitMs = Math.max(retryDelayMs * (1L << attempt), 5000);
                    }
                    log.warn("[{}:{}] Rate limited (429), waiting {}ms before retry {}/{}",
                            getExchange(), getMarketType(), waitMs, attempt, maxRetries);
                    response.close();
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted during 429 backoff", ie);
                        }
                    }
                    continue;
                }
                log.warn("[{}:{}] HTTP request failed with code {}, attempt {}/{}",
                        getExchange(), getMarketType(), code, attempt, maxRetries);
                response.close();
            } catch (IOException e) {
                lastException = e;
                log.warn("[{}:{}] HTTP request failed: {}, attempt {}/{}",
                        getExchange(), getMarketType(), e.getMessage(), attempt, maxRetries);
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry", ie);
                }
            }
        }
        throw lastException != null ? lastException : new IOException("Request failed after " + maxRetries + " attempts");
    }

    protected void incrementOrderbookUpdates() {
        orderbookUpdates.incrementAndGet();
    }

    protected void incrementTradeUpdates() {
        tradeUpdates.incrementAndGet();
    }

    public String getStatusSummary() {
        Instant lastMsg = lastMessageTime.get();
        String lastMsgStr = lastMsg != null
                ? java.time.Duration.between(lastMsg, Instant.now()).toSeconds() + "s ago"
                : "never";
        return String.format("msgs=%d, errs=%d, ob=%d, trades=%d, last=%s, syms=%d",
                messagesReceived.get(), messageErrors.get(),
                orderbookUpdates.get(), tradeUpdates.get(),
                lastMsgStr, subscribedSymbols.size());
    }
}
