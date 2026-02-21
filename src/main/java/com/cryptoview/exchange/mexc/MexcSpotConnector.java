package com.cryptoview.exchange.mexc;

import com.cryptoview.exchange.common.ExchangeConnector;
import com.cryptoview.exchange.mexc.proto.MexcProto.PushDataV3ApiWrapper;
import com.cryptoview.exchange.mexc.proto.MexcProto.PublicLimitDepthsV3Api;
import com.cryptoview.exchange.mexc.proto.MexcProto.PublicLimitDepthV3ApiItem;
import com.cryptoview.exchange.mexc.proto.MexcProto.PublicDealsV3Api;
import com.cryptoview.exchange.mexc.proto.MexcProto.PublicDealsV3ApiItem;
import com.cryptoview.model.domain.OrderBookLevel;
import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;
import com.cryptoview.service.orderbook.OrderBookManager;
import com.cryptoview.service.volume.VolumeTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MexcSpotConnector implements ExchangeConnector {

    private static final String WS_URL = "wss://wbs-api.mexc.com/ws";
    private static final String REST_URL = "https://api.mexc.com/api/v3/exchangeInfo";
    private static final String TICKER_URL = "https://api.mexc.com/api/v3/ticker/24hr";

    private static final int MAX_SUBSCRIPTIONS_PER_CONNECTION = 30;
    private static final int MAX_SYMBOLS_PER_CONNECTION = MAX_SUBSCRIPTIONS_PER_CONNECTION / 2; // 15
    private static final long PING_INTERVAL_MS = 20_000;
    private static final long STALE_DATA_THRESHOLD_MS = 90_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OrderBookManager orderBookManager;
    private final VolumeTracker volumeTracker;

    private final List<MexcWebSocketConnection> connections = new CopyOnWriteArrayList<>();
    private final Set<String> allSubscribedSymbols = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mexc-spot-scheduler");
        t.setDaemon(true);
        return t;
    });

    // Global metrics
    private final AtomicLong totalMessagesReceived = new AtomicLong(0);
    private final AtomicLong totalMessageErrors = new AtomicLong(0);
    private final AtomicLong totalOrderbookUpdates = new AtomicLong(0);
    private final AtomicLong totalTradeUpdates = new AtomicLong(0);
    private final AtomicReference<Instant> lastMessageTime = new AtomicReference<>();

    public MexcSpotConnector(OkHttpClient httpClient,
                             ObjectMapper objectMapper,
                             OrderBookManager orderBookManager,
                             VolumeTracker volumeTracker) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.orderBookManager = orderBookManager;
        this.volumeTracker = volumeTracker;
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
    public void connect() {
        // Connection is managed per-pool in subscribeAll
    }

    @Override
    public void disconnect() {
        log.info("[MEXC:SPOT] Disconnecting all {} connections", connections.size());
        for (MexcWebSocketConnection conn : connections) {
            conn.close();
        }
        connections.clear();
        allSubscribedSymbols.clear();
        scheduler.shutdown();
    }

    @Override
    public void subscribe(List<String> symbols) {
        // Not used externally — subscribeAll handles everything
    }

    @Override
    public void subscribeAll() {
        List<String> symbols = fetchAllSymbols();
        if (symbols.isEmpty()) {
            log.warn("[MEXC:SPOT] No symbols to subscribe");
            return;
        }

        // Split symbols into batches of MAX_SYMBOLS_PER_CONNECTION
        List<List<String>> batches = partitionList(symbols, MAX_SYMBOLS_PER_CONNECTION);
        log.info("[MEXC:SPOT] Subscribing to {} symbols across {} connections ({} symbols/connection)",
                symbols.size(), batches.size(), MAX_SYMBOLS_PER_CONNECTION);

        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            MexcWebSocketConnection conn = new MexcWebSocketConnection(i + 1, batch);
            connections.add(conn);
            conn.connectAndSubscribe();

            // Small delay between connections to avoid rate limiting
            if (i < batches.size() - 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        allSubscribedSymbols.addAll(symbols);

        // Start global ping/stale check task
        scheduler.scheduleAtFixedRate(this::pingAndCheckAllConnections, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isConnected() {
        return connections.stream().anyMatch(MexcWebSocketConnection::isConnected);
    }

    @Override
    public int getSubscribedSymbolsCount() {
        return allSubscribedSymbols.size();
    }

    @Override
    public String getStatusSummary() {
        long connectedCount = connections.stream().filter(MexcWebSocketConnection::isConnected).count();
        Instant lastMsg = lastMessageTime.get();
        String lastMsgStr = lastMsg != null
                ? java.time.Duration.between(lastMsg, Instant.now()).toSeconds() + "s ago"
                : "never";
        return String.format("conns=%d/%d, msgs=%d, errs=%d, ob=%d, trades=%d, last=%s, syms=%d",
                connectedCount, connections.size(),
                totalMessagesReceived.get(), totalMessageErrors.get(),
                totalOrderbookUpdates.get(), totalTradeUpdates.get(),
                lastMsgStr, allSubscribedSymbols.size());
    }

    // ========================= Symbol Fetching =========================

    private List<String> fetchAllSymbols() {
        List<String> validSymbols = fetchValidSymbols();
        if (validSymbols.isEmpty()) return List.of();

        // Sort by 24h volume to prioritize high-volume pairs
        return sortByVolume(validSymbols);
    }

    private List<String> fetchValidSymbols() {
        Request request = new Request.Builder().url(REST_URL).build();
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

    private List<String> sortByVolume(List<String> validSymbols) {
        Request tickerRequest = new Request.Builder().url(TICKER_URL).build();
        try (Response response = executeWithRetry(tickerRequest, 3, 5000)) {
            if (response.body() != null) {
                JsonNode tickers = objectMapper.readTree(response.body().string());
                Set<String> validSet = new HashSet<>(validSymbols);

                List<Map.Entry<String, Double>> symbolVolumes = new ArrayList<>();
                for (JsonNode ticker : tickers) {
                    String symbol = ticker.get("symbol").asText();
                    if (validSet.contains(symbol) && ticker.has("quoteVolume")) {
                        double volume = ticker.get("quoteVolume").asDouble();
                        symbolVolumes.add(Map.entry(symbol, volume));
                    }
                }

                // Sort by volume descending
                symbolVolumes.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                List<String> sorted = symbolVolumes.stream()
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());

                log.info("[MEXC:SPOT] Sorted {} symbols by 24h volume", sorted.size());
                return sorted;
            }
        } catch (IOException e) {
            log.warn("[MEXC:SPOT] Failed to fetch tickers for sorting, using unsorted list", e);
        }
        return new ArrayList<>(validSymbols);
    }

    private Response executeWithRetry(Request request, int maxRetries, long retryDelayMs) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Response response = httpClient.newCall(request).execute();
                if (response.isSuccessful()) {
                    return response;
                }
                log.warn("[MEXC:SPOT] HTTP request failed with code {}, attempt {}/{}",
                        response.code(), attempt, maxRetries);
                response.close();
            } catch (IOException e) {
                lastException = e;
                log.warn("[MEXC:SPOT] HTTP request failed: {}, attempt {}/{}",
                        e.getMessage(), attempt, maxRetries);
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

    // ========================= Protobuf Message Handling =========================

    private void handleBinaryMessage(ByteString bytes, int connectionId) {
        try {
            PushDataV3ApiWrapper wrapper = PushDataV3ApiWrapper.parseFrom(bytes.toByteArray());
            String channel = wrapper.getChannel();
            String symbol = wrapper.hasSymbol() ? wrapper.getSymbol() : extractSymbolFromChannel(channel);

            if (symbol == null || symbol.isEmpty()) {
                return;
            }

            if (wrapper.hasPublicLimitDepths()) {
                handleOrderBook(wrapper.getPublicLimitDepths(), symbol);
            } else if (wrapper.hasPublicDeals()) {
                handleTrades(wrapper.getPublicDeals(), symbol);
            }

        } catch (InvalidProtocolBufferException e) {
            totalMessageErrors.incrementAndGet();
            log.error("[MEXC:SPOT] conn#{} Failed to parse protobuf message ({} bytes)",
                    connectionId, bytes.size(), e);
        }
    }

    private void handleOrderBook(PublicLimitDepthsV3Api depth, String symbol) {
        List<OrderBookLevel> bids = new ArrayList<>();
        List<OrderBookLevel> asks = new ArrayList<>();

        for (PublicLimitDepthV3ApiItem item : depth.getBidsList()) {
            BigDecimal price = new BigDecimal(item.getPrice());
            BigDecimal quantity = new BigDecimal(item.getQuantity());
            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                bids.add(new OrderBookLevel(price, quantity));
            }
        }

        for (PublicLimitDepthV3ApiItem item : depth.getAsksList()) {
            BigDecimal price = new BigDecimal(item.getPrice());
            BigDecimal quantity = new BigDecimal(item.getQuantity());
            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                asks.add(new OrderBookLevel(price, quantity));
            }
        }

        if (!bids.isEmpty() || !asks.isEmpty()) {
            totalOrderbookUpdates.incrementAndGet();
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

    private void handleTrades(PublicDealsV3Api deals, String symbol) {
        for (PublicDealsV3ApiItem deal : deals.getDealsList()) {
            BigDecimal price = new BigDecimal(deal.getPrice());
            BigDecimal quantity = new BigDecimal(deal.getQuantity());

            totalTradeUpdates.incrementAndGet();
            orderBookManager.updateLastPrice(symbol, Exchange.MEXC, MarketType.SPOT, price);

            volumeTracker.addVolume(
                    symbol,
                    Exchange.MEXC,
                    MarketType.SPOT,
                    price.multiply(quantity)
            );
        }
    }

    private void handleTextMessage(String text, int connectionId) {
        // Text messages are subscription confirmations, pong, or errors
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (Exception e) {
            log.debug("[MEXC:SPOT] conn#{} Non-JSON text message: {}", connectionId,
                    text.length() > 200 ? text.substring(0, 200) + "..." : text);
            return;
        }

        if (root.has("msg")) {
            String msg = root.get("msg").asText();
            if ("PONG".equalsIgnoreCase(msg)) {
                return;
            }
            if (msg.contains("Not Subscribed")) {
                log.warn("[MEXC:SPOT] conn#{} Subscription failed: {}", connectionId,
                        msg.length() > 200 ? msg.substring(0, 200) + "..." : msg);
            } else {
                log.debug("[MEXC:SPOT] conn#{} Service message: {}", connectionId, msg);
            }
        }
    }

    private String extractSymbolFromChannel(String channel) {
        // Channel format: spot@public.limit.depth.v3.api.pb@BTCUSDT@20
        //            or:  spot@public.deals.v3.api.pb@BTCUSDT
        if (channel == null) return null;
        String[] parts = channel.split("@");
        if (parts.length >= 3) {
            return parts[2];
        }
        return null;
    }

    // ========================= Connection Pool Management =========================

    private void pingAndCheckAllConnections() {
        for (MexcWebSocketConnection conn : connections) {
            conn.sendPing();
            conn.checkStale();
        }
    }

    private static <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }

    // ========================= Inner Class: Single WebSocket Connection =========================

    private class MexcWebSocketConnection {
        private final int id;
        private final List<String> symbols;
        private WebSocket webSocket;
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private final AtomicBoolean connecting = new AtomicBoolean(false);
        private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
        private final AtomicReference<Instant> connLastMessageTime = new AtomicReference<>();
        private final AtomicInteger debugMessageCount = new AtomicInteger(0);
        private static final int DEBUG_MESSAGE_LIMIT = 3;

        MexcWebSocketConnection(int id, List<String> symbols) {
            this.id = id;
            this.symbols = new ArrayList<>(symbols);
        }

        void connectAndSubscribe() {
            if (connected.get() || connecting.get()) return;
            connecting.set(true);

            log.info("[MEXC:SPOT] conn#{} Connecting ({} symbols)...", id, symbols.size());

            Request request = new Request.Builder().url(WS_URL).build();
            webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    log.info("[MEXC:SPOT] conn#{} Connected, subscribing to {} symbols", id, symbols.size());
                    connected.set(true);
                    connecting.set(false);
                    reconnectAttempts.set(0);
                    sendSubscription(ws);
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    totalMessagesReceived.incrementAndGet();
                    Instant now = Instant.now();
                    lastMessageTime.set(now);
                    connLastMessageTime.set(now);
                    handleTextMessage(text, id);
                }

                @Override
                public void onMessage(WebSocket ws, ByteString bytes) {
                    totalMessagesReceived.incrementAndGet();
                    Instant now = Instant.now();
                    lastMessageTime.set(now);
                    connLastMessageTime.set(now);

                    if (debugMessageCount.getAndIncrement() < DEBUG_MESSAGE_LIMIT) {
                        log.info("[MEXC:SPOT] conn#{} Binary MSG #{}: {} bytes, channel={}",
                                id, debugMessageCount.get(), bytes.size(),
                                tryExtractChannel(bytes));
                    }

                    try {
                        handleBinaryMessage(bytes, id);
                    } catch (Exception e) {
                        totalMessageErrors.incrementAndGet();
                        log.error("[MEXC:SPOT] conn#{} Error handling binary message: {}",
                                id, e.getMessage());
                    }
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    log.error("[MEXC:SPOT] conn#{} WebSocket failure", id, t);
                    handleDisconnect();
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    log.info("[MEXC:SPOT] conn#{} WebSocket closing: {} - {}", id, code, reason);
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    log.info("[MEXC:SPOT] conn#{} WebSocket closed: {} - {}", id, code, reason);
                    handleDisconnect();
                }
            });
        }

        private void sendSubscription(WebSocket ws) {
            List<String> params = new ArrayList<>();
            for (String symbol : symbols) {
                params.add(String.format("spot@public.limit.depth.v3.api.pb@%s@20", symbol));
                params.add(String.format("spot@public.deals.v3.api.pb@%s", symbol));
            }

            String message = String.format("{\"method\":\"SUBSCRIPTION\",\"params\":[%s],\"id\":%d}",
                    params.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")),
                    id);
            ws.send(message);
            log.debug("[MEXC:SPOT] conn#{} Sent subscription for {} params", id, params.size());
        }

        void sendPing() {
            if (connected.get() && webSocket != null) {
                webSocket.send("{\"method\":\"PING\"}");
            }
        }

        void checkStale() {
            Instant lastMsg = connLastMessageTime.get();
            if (lastMsg != null && connected.get()) {
                long silenceMs = java.time.Duration.between(lastMsg, Instant.now()).toMillis();
                if (silenceMs > STALE_DATA_THRESHOLD_MS) {
                    log.warn("[MEXC:SPOT] conn#{} Stale connection (no data for {}s), forcing reconnect",
                            id, silenceMs / 1000);
                    if (webSocket != null) {
                        webSocket.close(1000, "Stale data reconnect");
                    }
                }
            }
        }

        boolean isConnected() {
            return connected.get();
        }

        void close() {
            connected.set(false);
            connecting.set(false);
            if (webSocket != null) {
                webSocket.close(1000, "Normal closure");
                webSocket = null;
            }
        }

        private void handleDisconnect() {
            connected.set(false);
            connecting.set(false);
            scheduleReconnect();
        }

        private void scheduleReconnect() {
            int attempts = reconnectAttempts.incrementAndGet();
            if (attempts > MAX_RECONNECT_ATTEMPTS) {
                log.error("[MEXC:SPOT] conn#{} Max reconnect attempts ({}) reached", id, MAX_RECONNECT_ATTEMPTS);
                return;
            }

            long delay = Math.min(INITIAL_RECONNECT_DELAY_MS * (1L << (attempts - 1)), MAX_RECONNECT_DELAY_MS);
            log.info("[MEXC:SPOT] conn#{} Scheduling reconnect in {}ms (attempt {})", id, delay, attempts);

            scheduler.schedule(() -> {
                debugMessageCount.set(0);
                connectAndSubscribe();
            }, delay, TimeUnit.MILLISECONDS);
        }

        private String tryExtractChannel(ByteString bytes) {
            try {
                PushDataV3ApiWrapper wrapper = PushDataV3ApiWrapper.parseFrom(bytes.toByteArray());
                return wrapper.getChannel();
            } catch (Exception e) {
                return "unknown";
            }
        }
    }
}
