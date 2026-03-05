# План: Добавление бирж Lighter и AsterDEX (Spot + Futures)

## Контекст
Нужно добавить 2 новые DEX-биржи: **Lighter** и **AsterDEX**, каждая с Spot и Futures коннекторами (итого 4 новых коннектора). Lighter уже имеет заглушку `LighterConnector.java` с неверными URL и форматами — нужно переписать. AsterDEX — новая биржа, API Binance-like (diff-based depth с REST snapshot).

## API биржи

### Lighter
- **WS URL:** `wss://mainnet.zklighter.elliot.ai/stream`
- **REST URL:** `https://mainnet.zklighter.elliot.ai/api/v1/`
- **Orderbook WS:** `{"type":"subscribe","channel":"order_book/{MARKET_INDEX}"}` — snapshot при подписке + updates каждые 50ms. Поля: `asks`, `bids` (массивы `{price, size}`), `offset`, `nonce`, `begin_nonce`
- **Trades WS:** `{"type":"subscribe","channel":"trade/{MARKET_INDEX}"}`
- **Список рынков REST:** `GET /api/v1/orderBookDetails?filter=all` → `order_book_details[]` и `spot_order_book_details[]` с `market_id`, `symbol`, `market_type` (perp/spot)
- **Глубина REST:** `GET /api/v1/orderBookOrders?market_id=X&limit=250` → `{asks: [{price, remaining_base_amount}], bids: [...]}`
- **Аутентификация:** Не нужна для публичных каналов
- **Ping:** Не указан явно, но WS стандартный
- **Особенность:** Подписка по `market_id` (число), а не по символу. Нужен маппинг symbol↔market_id

### AsterDEX
- **Spot WS:** `wss://sstream.asterdex.com/stream?streams=...`
- **Futures WS:** `wss://fstream.asterdex.com/stream?streams=...`
- **Spot REST:** `https://sapi.asterdex.com/api/v1/`
- **Futures REST:** `https://fapi.asterdex.com/fapi/v1/`
- **Формат:** Binance-совместимый
  - Depth stream: `<symbol>@depth@100ms` — diff с `U`, `u`, `pu`, `b[]`, `a[]`
  - AggTrade: `<symbol>@aggTrade` — `p`, `q`, `T`
  - Combined stream wraps: `{"stream":"...","data":{...}}`
- **Snapshot REST:** `GET /depth?symbol=X&limit=1000` (Spot: sapi, Futures: fapi)
- **Gap detection:** Futures использует `pu` (как Binance Futures). Spot использует `U`/`u` (как Binance Spot)
- **Лимиты:** 200 стримов/соединение, 24h lifetime, ping каждые 5 мин
- **Список пар REST:** `GET /exchangeInfo` (предположительно, Binance-like)
- **Символы:** lowercase в WS, uppercase в REST

## Изменения

### 1. Backend: Enum Exchange — добавить ASTER
**Файл:** `src/main/java/com/cryptoview/model/enums/Exchange.java`
- Добавить `ASTER("Aster")` в enum

### 2. Backend: Lighter — переписать LighterConnector (Spot + Futures)
**Файлы:**
- `src/main/java/com/cryptoview/exchange/lighter/LighterConnector.java` — УДАЛИТЬ
- `src/main/java/com/cryptoview/exchange/lighter/LighterSpotConnector.java` — НОВЫЙ
- `src/main/java/com/cryptoview/exchange/lighter/LighterFuturesConnector.java` — НОВЫЙ

**Паттерн:** Аналогично HyperliquidConnector (простой WS, snapshot при подписке + updates)
- `subscribeAll()`: REST `GET /orderBookDetails?filter=spot|perp` → получить market_id + symbol
- Хранить `Map<Integer, String> marketIdToSymbol` и обратный маппинг
- `subscribe()`: отправить `{"type":"subscribe","channel":"order_book/{market_id}"}` + `{"type":"subscribe","channel":"trade/{market_id}"}`
- `handleMessage()`: парсить channel из ответа, обработать orderbook (snapshot+updates) и trades
- Orderbook: при первом сообщении — полный snapshot (asks/bids), далее — только изменения. Поля: `price`, `size` (или `remaining_base_amount`)
- Symbol формат: Lighter использует "ETH", "BTC" → нужно добавить суффикс "USD" для spot или "USDC" для perp (уточнить по data)
- Ping: стандартный WS ping/pong

### 3. Backend: AsterDEX — создать Spot и Futures коннекторы
**Файлы:**
- `src/main/java/com/cryptoview/exchange/aster/AsterSpotConnector.java` — НОВЫЙ
- `src/main/java/com/cryptoview/exchange/aster/AsterFuturesConnector.java` — НОВЫЙ

**Паттерн:** Аналогично BinanceSpotConnector / BinanceFuturesConnector (diff-based depth)
- Используют `LocalOrderBook` для incremental обновлений
- **AsterSpotConnector:**
  - WS: `wss://sstream.asterdex.com/stream?streams=...`
  - REST snapshot: `https://sapi.asterdex.com/api/v1/depth?symbol=X&limit=1000`
  - REST symbols: `https://sapi.asterdex.com/api/v1/exchangeInfo`
  - Streams: `{symbol}@depth@100ms` + `{symbol}@aggTrade`
  - Gap detection: `U > lastUpdateId + 1` (Binance Spot алгоритм)
  - Combined format: `{"stream":"...", "data":{...}}`
- **AsterFuturesConnector:**
  - WS: `wss://fstream.asterdex.com/stream?streams=...`
  - REST snapshot: `https://fapi.asterdex.com/fapi/v1/depth?symbol=X&limit=1000`
  - REST symbols: `https://fapi.asterdex.com/fapi/v1/exchangeInfo`
  - Streams: `{symbol}@depth@500ms` + `{symbol}@aggTrade`
  - Gap detection: `pu != lastUpdateId` (Binance Futures алгоритм)
- **Общее:** event buffering, sequential snapshot fetch, refetch queue с dedup и max retries — **переиспользовать паттерны из BinanceSpot/FuturesConnector**, но упрощённо (меньше символов, меньше rate limit давление)
- Лимит: 200 стримов/соединение → 100 символов × 2 (depth+trade)
- Batch subscribe: `{"method":"SUBSCRIBE","params":["sym1@depth","sym2@depth"...],"id":1}`

### 4. Backend: application.yml — конфигурация
**Файл:** `src/main/resources/application.yml`
- Lighter: `enabled: true`, spot + futures, `min-density-usd: 400000`
- Aster: добавить блок ASTER, `enabled: true`, spot + futures, `min-density-usd: 400000`

### 5. Frontend: enums.ts — добавить LIGHTER и ASTER
**Файл:** `frontend/src/types/enums.ts`
- Добавить `'LIGHTER'` и `'ASTER'` в `Exchange` type

### 6. Frontend: constants.ts — labels, colors, списки
**Файл:** `frontend/src/utils/constants.ts`
- `EXCHANGE_LABELS`: добавить `LIGHTER: 'Lighter'`, `ASTER: 'Aster'`
- `EXCHANGE_COLORS`: добавить цвета (Lighter — `#6366F1` indigo, Aster — `#EC4899` pink)
- `EXCHANGES_WITH_FUTURES`: добавить `'LIGHTER'`, `'ASTER'`
- `EXCHANGES_WITH_SPOT`: добавить `'LIGHTER'`, `'ASTER'`
- `DISABLED_MARKETS`: убрать если были, ничего не добавлять

## Порядок реализации

1. Enum `Exchange` + `application.yml` + frontend enums/constants
2. Lighter: `LighterSpotConnector` + `LighterFuturesConnector` (переписать старый)
3. Aster: `AsterSpotConnector` + `AsterFuturesConnector` (новые файлы)
4. Компиляция + проверка

## Верификация

1. `./gradlew compileJava` — бэкенд компилируется без ошибок
2. `cd frontend && npm run build` — фронтенд компилируется
3. `./gradlew bootRun` — приложение запускается, в логах видны коннекторы Lighter и Aster
4. Проверить в UI что плотности с Lighter и Aster отображаются

## Ключевые файлы для повторного использования

- `AbstractWebSocketConnector.java` — базовый класс (reconnect, ping/pong, stale detection)
- `LocalOrderBook.java` — для diff-based depth (AsterDEX)
- `HyperliquidConnector.java` — паттерн для простого DEX (Lighter)
- `BinanceFuturesConnector.java` — паттерн для diff-based depth с snapshot (AsterDEX Futures)
- `BinanceSpotConnector.java` — паттерн для diff-based depth Spot (AsterDEX Spot)
