# Plan: Fix Exchange Connectors & Improve Logging

## Context

CryptoView - system for monitoring cryptocurrency orderbooks to detect abnormal densities. The application connects via WebSocket to 7 exchanges (Binance, Bybit, OKX, Bitget, Gate.io, MEXC, Hyperliquid) and monitors orderbooks for all USDT pairs. When a density exceeding configured thresholds is found, a Telegram alert is sent.

**Problems identified after thorough code review:**

### Critical Issues by Exchange

#### 1. Binance (Spot & Futures)
- **Ping format wrong**: Uses `{"method":"PING"}` but Binance WS uses OkHttp-level WebSocket protocol pings, not text pings. Text `{"method":"PING"}` is not a valid Binance WS message. Binance's combined stream endpoint returns `{"result":null,"id":1}` for pings via `{"method":"LIST_SUBSCRIPTIONS"}` but doesn't support `{"method":"PING"}`. The proper approach is to **not send text pings** since OkHttp handles WebSocket-level ping/pong automatically.
- **No combined stream**: Subscribing to hundreds of symbols on a single raw `/ws` connection is problematic. Binance recommends max 200 subscription per connection. With depth + trade = 2 per symbol, 500+ USDT pairs = 1000+ streams on one connection. Should use the **combined stream** endpoint or multiple connections.
- **Message routing**: When using `/ws` (not `/stream`), responses come without the `stream` field. The `handleMessage` checks for `root.has("stream")` which will always be false for the raw endpoint. Binance docs: for the raw stream, data comes directly; for combined stream (`/stream`), it wraps in `{stream, data}`.

#### 2. Bybit (Spot & Futures)
- **Mostly correct**. `orderbook.50` and `publicTrade` topics match Bybit V5 docs.
- **Ping format**: `{"op":"ping"}` is correct for Bybit.
- **Minor**: Bybit sends `pong` response as `{"op":"pong","ret_msg":"pong","conn_id":"...","success":true}` which is correctly filtered by `root.has("success")`.
- **Subscription limit**: Bybit allows max ~30 connections per account for public topics. The current single-connection approach is fine for public data.

#### 3. OKX (Spot & Futures)
- **books5 only provides 5 levels** - this is insufficient for density detection where we need to see levels further from the price. Should use `books` (400 levels, pushed every 100ms) or `books50-l2-tbt` (50 levels, tick-by-tick).
- **Ping**: Sending text `"ping"` is correct for OKX WebSocket, returns `"pong"`.
- **Connection limit**: OKX limits 3 connections per IP for public WebSocket. With both Spot and Futures trying to connect to the same endpoint, this could be an issue.

#### 4. Bitget (Spot & Futures)
- **books15 = only 15 levels**. For proper density analysis need `books` (full depth) or `books50` (50 levels).
- **Ping**: Text `"ping"` is correct, Bitget returns `"pong"`.
- **Subscription format**: The current format is correct for Bitget V2 API.

#### 5. Gate.io (Spot & Futures)
- **Subscription format problematic**: Gate WS `spot.order_book` payload format is `["BTC_USDT", "20", "100ms"]` where only ONE currency pair per subscription. Current code puts ALL symbols in one payload array, which is incorrect. Each symbol needs its own subscription message.
- **Trade subscription**: Same issue - one symbol per subscription call for `spot.trades`.
- **Ping format**: Correct `{"time":..., "channel":"spot.ping"}`.

#### 6. MEXC
- **Subscription method**: `"SUBSCRIPTION"` is correct for MEXC Spot WS V3.
- **WS URL**: `wss://wbs.mexc.com/ws` is correct.
- **Ping**: `{"method":"PING"}` is correct, MEXC returns `{"msg":"PONG"}`.
- **Orderbook format**: MEXC's depth data uses `{"bids":{"p":"price","v":"qty"}, ...}` format with objects, not arrays. The current code handles both array and object formats, which is good.

#### 7. Hyperliquid
- **Mostly correct**. `l2Book` and `trades` subscriptions per coin match the docs.
- **Ping**: `{"method":"ping"}` is correct.
- **Symbol handling**: Correctly maps coin name to `COINUSDC` format.

### Structural/Logging Issues

1. **No structured startup log** showing what was actually subscribed successfully vs failed.
2. **No logging of first few WS messages** to debug parsing issues.
3. **No connection status tracking** with timestamps (when connected, when disconnected, why).
4. **No metric counting** of received orderbook updates vs parse errors per exchange.
5. **Gate.io subscribe() overrides parent** but also calls `super.subscribe()` which sends the broken batch subscription too.

---

## Plan

### Step 1: Fix AbstractWebSocketConnector Logging

**File**: `src/main/java/com/cryptoview/exchange/common/AbstractWebSocketConnector.java`

Changes:
- Add counters: `messagesReceived`, `messageErrors`, `orderbookUpdates`, `tradeUpdates` (AtomicLong)
- Add `lastMessageTime` (AtomicReference<Instant>) per connector
- Log first 3 raw messages per connector at INFO level for debugging
- Log connection state changes with timestamps
- Add method `getStatusSummary()` returning a string with stats
- Override `onMessage` to count messages and track timing before delegating

### Step 2: Fix Binance Connectors

**Files**:
- `src/main/java/com/cryptoview/exchange/binance/BinanceSpotConnector.java`
- `src/main/java/com/cryptoview/exchange/binance/BinanceFuturesConnector.java`

Changes:
- **Remove text ping** - override `getPingMessage()` to return `null` (OkHttp handles WS-level pings). Binance WS closes connections that are idle for 24h but doesn't need application-level pings.
- **Fix message parsing**: For the raw `/ws` endpoint, messages come as direct data (no `stream` wrapper). The `handleMessage` already handles this case partially but needs cleanup. Alternatively, switch to the combined stream endpoint.
- **Best approach**: Keep `/ws` endpoint but fix parsing - when `stream` field is absent, detect message type by content (`lastUpdateId` for depth, `e":"trade"` for trade events).
- **Subscription limit**: Add connection splitting - if symbols > 100, create multiple WS connections (Binance allows max 1024 streams per connection for combined stream, but for raw ws each SUBSCRIBE can add streams).

### Step 3: Fix OKX Connectors - Increase Depth

**Files**:
- `src/main/java/com/cryptoview/exchange/okx/OkxSpotConnector.java`
- `src/main/java/com/cryptoview/exchange/okx/OkxFuturesConnector.java`

Changes:
- Change `books5` to `books50-l2-tbt` (50 levels, tick-by-tick, more efficient for monitoring)
- 50 levels gives good depth for density detection without excessive data

### Step 4: Fix Bitget Connectors - Increase Depth

**Files**:
- `src/main/java/com/cryptoview/exchange/bitget/BitgetSpotConnector.java`
- `src/main/java/com/cryptoview/exchange/bitget/BitgetFuturesConnector.java`

Changes:
- Change `books15` to `books50` (50 levels)
- Update subscription format to reflect new channel name

### Step 5: Fix Gate.io Connectors - Fix Subscription Format

**Files**:
- `src/main/java/com/cryptoview/exchange/gate/GateSpotConnector.java`
- `src/main/java/com/cryptoview/exchange/gate/GateFuturesConnector.java`

Changes:
- **Fix subscription**: Send one subscription per symbol instead of batching all symbols into one message. Gate.io's `spot.order_book` payload is `[currency_pair, level, interval]` not `[cp1, cp2, ..., level, interval]`.
- Remove `super.subscribe()` call that sends the broken batch, and handle both orderbook + trade subscriptions in the overridden `subscribe()` method directly.
- Fix `buildSubscribeMessage` to handle a single symbol correctly.

### Step 6: Improve ExchangeManager Logging

**File**: `src/main/java/com/cryptoview/exchange/ExchangeManager.java`

Changes:
- Enhance `logStatus()` to show per-connector stats: messages received, errors, last message time, orderbook count
- Add startup timing log (how long each connector took to initialize)
- Log a clear summary table after all connectors are initialized

### Step 7: Add Pong Handling in AbstractWebSocketConnector

**File**: `src/main/java/com/cryptoview/exchange/common/AbstractWebSocketConnector.java`

Changes:
- Improve pong detection: currently only checks `"pong"` text, but each exchange has different pong formats (e.g. Bitget/OKX send text `"pong"`, MEXC sends `{"msg":"PONG"}`, Bybit sends `{"op":"pong",...}`)
- Add method `isPongMessage(String text)` that subclasses can override
- Default implementation checks common patterns

### Step 8: Fix Hyperliquid - Minor Connection Issue

**File**: `src/main/java/com/cryptoview/exchange/hyperliquid/HyperliquidConnector.java`

The `subscribeAll()` doesn't call `connectAndWait()` before subscribing, but `subscribe()` uses `send()` which checks `connected.get()`. Actually it does call `connectAndWait(5000)`. This is correct. No changes needed.

### Step 9: Improve Bybit Cursor Handling

**Files**:
- `src/main/java/com/cryptoview/exchange/bybit/BybitSpotConnector.java`
- `src/main/java/com/cryptoview/exchange/bybit/BybitFuturesConnector.java`

Changes:
- Bybit's instruments-info API uses pagination (cursor-based). If there are more than 500 symbols, the current code only fetches the first page. Add cursor pagination to `fetchAllSymbols()`.

---

## Files Modified

1. `src/main/java/com/cryptoview/exchange/common/AbstractWebSocketConnector.java` - logging, metrics, pong handling
2. `src/main/java/com/cryptoview/exchange/binance/BinanceSpotConnector.java` - fix ping, fix message parsing
3. `src/main/java/com/cryptoview/exchange/binance/BinanceFuturesConnector.java` - fix ping, fix message parsing
4. `src/main/java/com/cryptoview/exchange/okx/OkxSpotConnector.java` - books5 → books50-l2-tbt
5. `src/main/java/com/cryptoview/exchange/okx/OkxFuturesConnector.java` - books5 → books50-l2-tbt
6. `src/main/java/com/cryptoview/exchange/bitget/BitgetSpotConnector.java` - books15 → books50
7. `src/main/java/com/cryptoview/exchange/bitget/BitgetFuturesConnector.java` - books15 → books50
8. `src/main/java/com/cryptoview/exchange/gate/GateSpotConnector.java` - fix per-symbol subscription
9. `src/main/java/com/cryptoview/exchange/gate/GateFuturesConnector.java` - fix per-symbol subscription
10. `src/main/java/com/cryptoview/exchange/bybit/BybitSpotConnector.java` - pagination fix
11. `src/main/java/com/cryptoview/exchange/bybit/BybitFuturesConnector.java` - pagination fix
12. `src/main/java/com/cryptoview/exchange/ExchangeManager.java` - enhanced status logging

---

## Verification

1. `./gradlew compileJava` - check compilation passes
2. `./gradlew bootRun` - launch and observe logs:
   - Each connector should log "Connected" then "Subscribed to N symbols"
   - First 3 raw messages per connector should be logged at INFO
   - After ~30 seconds, status log should show non-zero message counts
   - No parse errors in log
3. Check that orderbook updates are being published (AnomalyDetector should log at DEBUG level)
4. Verify volume preloading works (VolumeTracker should report tracked symbols)
