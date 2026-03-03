# CLAUDE.md — Контекст для разработки

## О проекте

CryptoView — система мониторинга криптовалютных ордербуков для обнаружения аномальных плотностей. Отслеживает стаканы на 7 биржах (Spot + Futures), находит аномальные уровни, отправляет алерты в Telegram. Предоставляет REST API и WebSocket стрим для React-фронтенда.

## Ключевые решения

### Архитектура
- **Монолит** на Spring Boot (достаточно для текущих требований)
- **Event-driven** внутри приложения (Spring Events)
- **In-memory** хранение стаканов (ConcurrentHashMap)
- **Скользящее окно** для объёмов (15 минут)
- **Workspaces** — view-layer фильтры для фронтенда (НЕ влияют на коннекторы, Telegram, AnomalyDetector)
- **Density Lifetime Tracking** — отслеживание времени жизни плотностей в стакане
- **REST API** — CRUD workspaces, настройки символов, статус системы
- **WebSocket** — real-time стрим отфильтрованных плотностей (500мс интервал)
- **React Frontend** — SPA на React + TypeScript + Vite + Tailwind CSS + Zustand

### Технологии (бэкенд)
- Java 21 (virtual threads для WebSocket)
- Spring Boot 4.0.2
- Gradle Groovy DSL
- OkHttp для WebSocket (биржевые коннекторы)
- Spring WebSocket (фронтенд стрим)
- Jackson для JSON
- Telegram Bots API
- Apache Commons Math3 (статистика: Z-score, IQR)
- Protobuf (MEXC)

### Технологии (фронтенд)
- React 19 + TypeScript
- Vite 7 (сборщик, dev-сервер на порту 3000)
- Tailwind CSS v4 (утилитарные стили, тёмная тема)
- Zustand (state management)
- Браузерный WebSocket API (wsClient singleton)
- Vite proxy: `/api` и `/ws` → `localhost:8080`

### Биржи
- Binance, Bybit, OKX, Bitget — полный мониторинг (Spot + Futures)
- Gate — **отключена** (таймауты подключения)
- MEXC — только Spot (без Futures)
- Hyperliquid — только Futures (USDC perpetual DEX)
- Lighter — **отключена**

## Два data pipeline

### 1. Alert pipeline (Telegram) — существующий
```
Exchange WS → OrderBookUpdateEvent → AnomalyDetector → DensityDetectedEvent → AlertService → AlertEvent → TelegramService
                                          ↑
Trade WS → TradeEvent → VolumeTracker (15-min volume)
```
Использует `configService.getEffectiveConfig()` для пороговых значений. Дедупликация по символу с cooldown 5 мин.

### 2. Density tracking pipeline (Frontend) — новый
```
Exchange WS → OrderBookUpdateEvent → DensityTracker (ConcurrentHashMap, lifetime tracking)
                                          ↓
                              DensityFilterService (фильтрация по workspace)
                                     ↓              ↓
                              REST GET /densities    WebSocket /ws/densities (500мс broadcast)
                                                           ↓
                                                   React Frontend (Zustand store)
```
Workspaces — view-layer фильтр. Они **НЕ влияют** на alert pipeline. Только на то, что видит фронтенд.

## Workspaces

Рабочие пространства — пресеты фильтрации для фронтенда.

### Что хранит workspace
- `enabledMarkets` — включённые exchange+marketType пары (пусто = все)
- `minDensityOverrides` — минимальный порог по exchange+marketType
- `symbolMinDensityOverrides` — минимальный порог по символу
- `blacklistedSymbols` — скрытые символы
- `favoritedSymbols` — символы в избранном (отображаются первыми)
- `symbolComments` — пользовательские комментарии к символам
- `sortType` — сортировка densities (DURATION_DESC, SIZE_USD_DESC, DISTANCE_ASC)
- `newBadgeDurationMinutes` — сколько минут плотность помечается как NEW (дефолт: 5)

### Приоритет resolveMinDensity
1. `workspace.symbolMinDensityOverrides[symbol]`
2. `workspace.minDensityOverrides[exchange_marketType]`
3. `configService.getEffectiveConfig()` из application.yml

### Хранение
In-memory + `workspaces.json` (переживает рестарт). Tracked densities — только в памяти.

## DensityTracker — алгоритм

### Структуры данных
```
activeDensities: ConcurrentHashMap<trackingKey, TrackedDensity>
densitiesByOrderBookKey: ConcurrentHashMap<"EXCHANGE_MARKETTYPE_SYMBOL", Set<trackingKey>>
```

### На каждый OrderBookUpdateEvent
1. Построить `orderBookKey` = `EXCHANGE_MARKETTYPE_SYMBOL`
2. Для каждого уровня (bids + asks):
   - `volumeUsd = price × quantity`
   - Если `volumeUsd < TRACKING_FLOOR (50K)` → skip
   - `trackingKey = EXCHANGE_MARKETTYPE_SYMBOL_SIDE_PRICE`
   - Если уже есть → обновить `lastSeenAt`, `quantity`, `volumeUsd`, `distancePercent`
   - Если нет → создать с `firstSeenAt = now`
3. Удалить исчезнувшие: `previousKeys - currentKeys` → remove из `activeDensities`

### Cleanup (`@Scheduled`, каждые 10 сек)
Удалить `TrackedDensity` с `lastSeenAt > 2 мин` назад.

## REST API

### Workspaces
| Method | Path | Описание |
|--------|------|----------|
| GET | `/api/v1/workspaces` | Список всех workspaces |
| GET | `/api/v1/workspaces/active` | Активный workspace |
| POST | `/api/v1/workspaces` | Создать workspace |
| PUT | `/api/v1/workspaces/{id}` | Обновить workspace |
| DELETE | `/api/v1/workspaces/{id}` | Удалить workspace (нельзя удалить активный) |
| POST | `/api/v1/workspaces/{id}/activate` | Активировать workspace |
| GET | `/api/v1/workspaces/{id}/export` | Экспортировать workspace как JSON |
| POST | `/api/v1/workspaces/import` | Импортировать workspace из JSON (создаёт новый с новым ID) |

### Densities
| Method | Path | Query Params | Описание |
|--------|------|--------------|----------|
| GET | `/api/v1/densities` | `sort`, `limit`, `workspaceId` | Плотности с фильтрацией по workspace |

### Symbol settings (внутри workspace)
| Method | Path | Описание |
|--------|------|----------|
| POST | `/api/v1/workspaces/{id}/blacklist/{symbol}` | Добавить в ЧС |
| DELETE | `/api/v1/workspaces/{id}/blacklist/{symbol}` | Убрать из ЧС |
| PUT | `/api/v1/workspaces/{id}/symbols/{symbol}/comment` | Установить комментарий |
| DELETE | `/api/v1/workspaces/{id}/symbols/{symbol}/comment` | Удалить комментарий |
| PUT | `/api/v1/workspaces/{id}/symbols/{symbol}/min-density` | Установить мин. плотность |
| DELETE | `/api/v1/workspaces/{id}/symbols/{symbol}/min-density` | Удалить override |

### Status
| Method | Path | Описание |
|--------|------|----------|
| GET | `/api/v1/status` | connectedExchanges, totalSymbols, trackedDensities, activeWorkspace |

## WebSocket API

### Подключение
```
ws://localhost:8080/ws/densities?workspaceId=<uuid>
```
Если `workspaceId` не указан — используется активный workspace сервера.

### Смена workspace (client→server)
```json
{"action": "setWorkspace", "workspaceId": "..."}
```

### Обновление densities (server→client, каждые 500мс)
```json
{
  "type": "densities",
  "timestamp": "2026-02-22T12:00:00Z",
  "count": 42,
  "data": [{
    "symbol": "BTCUSDT",
    "exchange": "BINANCE",
    "marketType": "FUTURES",
    "side": "BID",
    "price": 64500.00,
    "quantity": 15.5,
    "volumeUsd": 999750.00,
    "distancePercent": 1.23,
    "lastPrice": 65300.00,
    "firstSeenAt": "2026-02-22T11:55:00Z",
    "lastSeenAt": "2026-02-22T12:00:00Z",
    "durationSeconds": 300,
    "comment": "крупный бид"
  }]
}
```

Фильтрация по workspace (enabledMarkets, blacklist, minDensity, sort) работает одинаково для REST и WebSocket. Логика вынесена в `DensityFilterService`.

## Паттерны кода

### Коннекторы бирж
Все коннекторы реализуют `ExchangeConnector` и наследуются от `AbstractWebSocketConnector`:
```java
public interface ExchangeConnector {
    Exchange getExchange();
    MarketType getMarketType();
    void connect();
    void disconnect();
    void subscribe(List<String> symbols);
    void subscribeAll();
    boolean isConnected();
    int getSubscribedSymbolsCount();
}
```
Общая логика: реконнект с exponential backoff (max 10 попыток), ping/pong (30s), stale detection (90s).

### Конфигурация
Иерархия настроек: Global → Exchange → MarketType → Symbol
```java
configService.getEffectiveConfig(exchange, marketType, symbol);
```

### События
```java
// Публикация
eventPublisher.publishEvent(new OrderBookUpdateEvent(this, orderBook));

// Обработка
@EventListener
public void onOrderBookUpdate(OrderBookUpdateEvent event) { ... }
```

## Важные константы

| Константа | Значение | Назначение |
|-----------|----------|------------|
| `VOLUME_WINDOW_MINUTES` | 15 | Окно для подсчёта объёма |
| `MIN_VOLUME_TRACKING_SEC` | 300 (5 мин) | Минимум данных для VOLUME_BASED |
| `DEFAULT_MIN_DENSITY_USD` | 100,000 | Минимальная плотность (global) |
| `TRACKING_FLOOR` | 50,000 | Минимальный объём для отслеживания lifetime |
| `MAX_DISTANCE_PERCENT` | 10.0 | Максимум от цены для анализа |
| `COOLDOWN_MINUTES` | 5 | Пауза между алертами |
| `SEND_INTERVAL_MS` | 50 | Интервал отправки Telegram (~20 msg/sec) |
| `STALE_THRESHOLD` | 2 мин | Cleanup давно не обновлявшихся densities |
| `WS_BROADCAST_INTERVAL` | 500мс | Интервал broadcast densities по WebSocket |
| `Z_SCORE_THRESHOLD` | 3.0 | Порог статистической аномалии |
| `IQR_MULTIPLIER` | 3.0 | Множитель IQR |
| `NEW_BADGE_DURATION_MINUTES` | 5 | Дефолт времени показа бейджа NEW (фронтенд) |

## Форматы данных

### OrderBook
```java
record OrderBook(
    String symbol, Exchange exchange, MarketType marketType,
    List<OrderBookLevel> bids,  // отсортированы по убыванию цены
    List<OrderBookLevel> asks,  // отсортированы по возрастанию цены
    BigDecimal lastPrice, Instant timestamp
)
```

### TrackedDensity
```java
record TrackedDensity(
    String symbol, Exchange exchange, MarketType marketType, Side side,
    BigDecimal price, BigDecimal quantity, BigDecimal volumeUsd,
    BigDecimal distancePercent, BigDecimal lastPrice,
    Instant firstSeenAt, Instant lastSeenAt
)
// trackingKey() = "EXCHANGE_MARKETTYPE_SYMBOL_SIDE_PRICE"
// durationSeconds() = lastSeenAt - firstSeenAt
```

### Alert
```java
record Alert(
    String symbol, Exchange exchange, MarketType marketType,
    AlertType alertType, Side side,
    BigDecimal price, BigDecimal volumeUsd, BigDecimal distancePercent,
    BigDecimal volume15min, String comment, Instant timestamp
)
```

## Команды разработки

```bash
# Бэкенд — сборка
./gradlew build

# Бэкенд — запуск (порт 8080)
./gradlew bootRun

# Бэкенд — только компиляция
./gradlew compileJava

# Бэкенд — тесты
./gradlew test

# Фронтенд — установка зависимостей (первый раз)
cd frontend && npm install

# Фронтенд — dev-сервер с hot reload (порт 3000)
cd frontend && npm run dev

# Фронтенд — production сборка
cd frontend && npm run build
```

## Текущее состояние проекта

### Бэкенд — рабочий
- Все коннекторы работают (кроме Gate и Lighter — отключены)
- Alert pipeline → Telegram работает
- Density tracking pipeline → REST + WebSocket работает
- Workspaces: CRUD, export/import, favoritedSymbols, newBadgeDurationMinutes — всё реализовано
- Binance snapshot storm пофикшен (последовательная инициализация)

### Фронтенд — базовый каркас, НЕ ТЕСТИРОВАЛСЯ С БЭКЕНДОМ
Создана полная структура React-приложения, компилируется чисто (`npm run build` — OK).
Компоненты написаны, но **ещё не проверялись вместе с работающим бэкендом**.

Потенциальные проблемы, которые нужно будет отладить при первом запуске:
- Маппинг `enabledMarkets`: фронтенд отправляет `ExchangeMarketKey[]` (объекты `{exchange, marketType}`), бэкенд хранит `Set<String>` (ключи `"BINANCE_SPOT"`) — нужно проверить сериализацию/десериализацию
- WebSocket подключение через Vite proxy — нужно убедиться что `/ws/densities` проксируется корректно
- Workspace `maxDistancePercent` — есть на фронтенде в типе `Workspace`, но отсутствует в бэкенде `Workspace.java`
- Обработка ответа `del<void>` в `httpClient.ts` — при 204 No Content `res.json()` бросит ошибку
- `SettingsModal` отправляет `WorkspaceRequest` с `enabledMarkets` как массив объектов, а бэкенд ожидает `Set<String>`

### Запуск для разработки
1. Бэкенд: `./gradlew bootRun` (порт 8080). Ждёт 1-2 мин для подключения к биржам.
2. Фронтенд: `cd frontend && npm run dev` (порт 3000). Vite proxy → 8080.
3. Открыть `http://localhost:3000`

## TODO

- [x] Реализовать все коннекторы
- [x] Telegram rate limiter
- [x] Умная дедупликация (по символу, а не по цене)
- [x] Убрать REST seed, перейти на real-time volume
- [x] REST API для настроек (workspaces, densities, status)
- [x] Density lifetime tracking
- [x] WebSocket стрим densities для фронтенда
- [x] Diff-based depth для Binance Spot/Futures (LocalOrderBook + REST snapshot)
- [x] Diff-based depth для Bybit Spot/Futures (orderbook.200)
- [x] Diff-based depth для OKX Spot/Futures (books, 400 уровней + ctVal)
- [x] Diff-based depth для Bitget Spot/Futures (books, полная глубина)
- [x] Frontend на React (базовая структура: компоненты, stores, API клиент, WS клиент)
- [x] Workspace: поля favoritedSymbols и newBadgeDurationMinutes
- [x] Workspace: export/import эндпоинты
- [x] Fix Binance snapshot storm (последовательная инициализация)
- [ ] **Frontend: первый запуск и отладка с бэкендом** (маппинг enabledMarkets, WS proxy, 204 ответы)
- [ ] Frontend: иконки бирж (SVG в public/icons/)
- [ ] Frontend: полировка UI, анимации появления новых плотностей
- [ ] Frontend: управление несколькими workspaces из UI
- [ ] Починить Gate (таймауты)
- [ ] Добавить метрики (Micrometer)
- [ ] Health checks для каждой биржи

## LocalOrderBook — diff-based управление стаканом

Thread-safe класс для incremental обновлений ордербука. Используется всеми коннекторами кроме MEXC и Hyperliquid.

### API
- `applySnapshot(bids, asks, updateId)` — полная замена (Binance)
- `applySnapshot(bids, asks, updateId, seqId)` — с seqId (Bybit, OKX, Bitget)
- `applyDelta(bids, asks, updateId)` — инкрементальное обновление (Binance)
- `applyDelta(bids, asks, updateId, seqId)` — с seqId (Bybit, OKX, Bitget)
- `getSnapshot()` — получить текущий snapshot как `List<OrderBookLevel>`
- `getSnapshot(quantityMultiplier)` — с множителем (OKX Futures ctVal)
- `reset()` — сброс (при gap detection)

### Формат данных
Уровни передаются как `List<List<String>>` — `[[price, qty], ...]`. Qty=0 → удалить уровень.

## Известные особенности бирж

### Binance
- Используем `/stream` (combined) вместо `/ws` (raw) — сообщения содержат имя стрима
- **Diff-based depth:** `@depth@100ms` (Spot) / `@depth@500ms` (Futures)
- Требует REST snapshot для инициализации (`/api/v3/depth` Spot, `/fapi/v1/depth` Futures)
- Event buffering до получения snapshot, gap detection по `U`/`pu`
- Spot: `@trade`, Futures: `@aggTrade`
- Лимит: 1024 стрима на соединение (512 символов × 2)
- **SNAPSHOT_LIMIT:** Spot=1000 (вес 10, delay 500ms), Futures=1000 (вес 20, delay 600ms)
- **Ping/pong:** Binance шлёт WebSocket ping frames (Spot: каждые 20 сек, Futures: каждые 3 мин). OkHttp отвечает автоматически. Application-level ping НЕ нужен (`getPingMessage()` = null)
- **24h limit:** Binance обрывает WS через 24ч. AbstractWebSocketConnector автоматически переподключается за 5 мин до этого
- **Publish throttle:** OrderBook публикуется не чаще 1 раз в 2 сек на символ (delta применяется к LocalOrderBook всегда)

#### Binance snapshot initialization — ВАЖНЫЙ ПАТТЕРН
Инициализация снапшотов выполняется **строго последовательно** в `fetchSnapshotsForAll()`:
1. При подключении подписка на WS-стримы (batch по 20, пауза 500мс)
2. Запуск `fetchSnapshotsForAll()` в отдельном virtual thread — Spot: 1 req/500ms, Futures: 1 req/600ms
3. WS-события для символов без снапшота **только буферизируются** (НЕ запускают параллельные запросы!)
4. Когда `fetchSnapshotsForAll` доходит до символа — применяет снапшот + буфер → публикует orderbook

**КРИТИЧНО:** `handleDepthUpdate()` НЕ запускает virtual thread для refetch. При gap detection:
1. `book.reset()` + `initializing.add(symbol)` + буферизация текущего event
2. `queueRefetch(symbol)` — проверяет `refetchPending` (dedup) и `gapRetryCounts` (max 3 retries)
3. Фоновый `refetchWorker` ждёт `initLatch` (завершение `fetchSnapshotsForAll`), затем обрабатывает очередь
4. Event buffers: `ConcurrentLinkedQueue` (lock-free O(1) add) вместо `CopyOnWriteArrayList`

#### Алгоритм обработки depth update (Spot)
```
1. initializing? → buffer event, return
2. book not initialized? → buffer event, return
3. U > lastUpdateId + 1? → GAP: reset book, buffer, queue refetch, return
4. u <= lastUpdateId? → skip (уже применено)
5. applyDelta(bids, asks, u)
6. if (now - lastPublish >= 2sec): publishOrderBook()
```

#### Алгоритм обработки depth update (Futures) — отличия от Spot
- Futures использует `pu` (previous update id) вместо `U` для gap detection
- Gap check: `pu != book.lastUpdateId` (каждый event.pu == previous event.u)
- Буфер-фильтрация: первый event после snapshot должен иметь `U <= lastUpdateId AND u >= lastUpdateId`

#### Алгоритм применения буфера после snapshot (Spot)
```
1. Пропустить events с u <= lastUpdateId (полностью до снапшота)
2. Первый оставшийся event: проверить U <= lastUpdateId+1 AND u >= lastUpdateId+1
   - Нет → GAP, re-queue для refetch
3. Применить оставшиеся events последовательно
```

#### Алгоритм применения буфера после snapshot (Futures)
```
1. Пропустить events с u < lastUpdateId
2. Первый event: U <= lastUpdateId AND u >= lastUpdateId → bridging event
3. Последующие: проверять pu chain (pu == prevU)
4. Разрыв chain → GAP, re-queue
```

### Bybit
- Unified API v5
- **`orderbook.200`** — 200 уровней, incremental (snapshot + delta)
- `type: "snapshot"` → первое сообщение, `type: "delta"` → последующие
- Tracking: `u` (updateId), `seq` (sequence)

### OKX
- **`books`** канал — 400 уровней, incremental (snapshot + update)
- `action: "snapshot"` → первое сообщение, `action: "update"` → последующие
- Tracking: `seqId`, `prevSeqId` для gap detection
- REST fallback: `/api/v5/market/books?instId=X&sz=400`
- **Futures ctVal:** `sz` = контракты, реальное количество = `sz × ctVal` (загружается из instruments API)
- Подписки orderbook и trades отправляются **отдельными** WS-сообщениями

### Bitget
- API v2: `wss://ws.bitget.com/v2/ws/public`
- **`books`** канал — полная глубина, incremental (snapshot + update)
- `action: "snapshot"` → первое сообщение, `action: "update"` → последующие
- Tracking: `seq`, `pseq` (previous sequence) для gap detection
- REST fallback: Spot `/api/v2/spot/market/orderbook`, Futures `/api/v2/mix/market/merge-depth`

### Hyperliquid
- Только USDC пары, perpetual DEX
- Бесплатная глубина ограничена
- Нестандартный формат: l2Book subscription per coin

### MEXC
- Только Spot мониторим
- Protobuf формат + WebSocket connection pool (15 символов / соединение)
- URL: `wss://wbs-api.mexc.com/ws`
- Глубина до 20 уровней
- Медленная загрузка символов (много пар)

### Gate
- Отключена из-за SocketTimeoutException при подключении

## Структура пакетов (бэкенд)

```
com.cryptoview
├── config                # @Configuration классы
│   ├── AsyncConfig       # ThreadPoolTaskExecutor (4-16 threads)
│   ├── CryptoViewProperties  # @ConfigurationProperties (application.yml)
│   ├── JacksonConfig     # JavaTimeModule, ISO-8601 dates
│   ├── OkHttpConfig      # OkHttp client для бирж
│   ├── WebConfig         # CORS для REST API (localhost:3000)
│   └── WebSocketConfig   # WS endpoint /ws/densities
├── controller            # REST + WebSocket
│   ├── DensityController     # GET /api/v1/densities
│   ├── DensityWebSocketHandler  # WS /ws/densities (500мс broadcast)
│   ├── WorkspaceController   # CRUD workspaces + export/import
│   ├── SymbolController      # Blacklist, comments, min-density per symbol
│   └── StatusController      # GET /api/v1/status
├── model
│   ├── domain            # OrderBook, OrderBookLevel, Density, Alert, Trade, TrackedDensity
│   ├── enums             # Exchange, MarketType, AlertType, Side, DensitySortType
│   ├── config            # GlobalConfig, ExchangeConfig, MarketTypeConfig, SymbolConfig, EffectiveConfig, ExchangeMarketKey, Workspace
│   └── dto               # DensityResponse, WorkspaceRequest
├── exchange
│   ├── common            # ExchangeConnector (interface), AbstractWebSocketConnector, LocalOrderBook
│   ├── ExchangeManager   # Управление всеми коннекторами
│   ├── binance/          # BinanceSpotConnector, BinanceFuturesConnector
│   ├── bybit/            # BybitSpotConnector, BybitFuturesConnector
│   ├── okx/              # OkxSpotConnector, OkxFuturesConnector
│   ├── bitget/           # BitgetSpotConnector, BitgetFuturesConnector
│   ├── gate/             # GateSpotConnector, GateFuturesConnector (disabled)
│   ├── mexc/             # MexcSpotConnector (protobuf, connection pool)
│   ├── hyperliquid/      # HyperliquidConnector (USDC Futures only)
│   └── lighter/          # LighterConnector (disabled)
├── service
│   ├── config            # ConfigService (hierarchical config resolution)
│   ├── orderbook         # OrderBookManager (in-memory storage + event publishing)
│   ├── volume            # VolumeTracker (15-min sliding window)
│   ├── detector          # AnomalyDetector (Z-score, IQR, volume-based)
│   ├── alert             # AlertService (Density → Alert)
│   ├── telegram          # TelegramService (rate-limited sending)
│   ├── density           # DensityTracker (lifetime tracking), DensityFilterService (workspace filtering)
│   └── workspace         # WorkspaceService (CRUD + JSON persistence + import)
└── event                 # OrderBookUpdateEvent, DensityDetectedEvent, AlertEvent, TradeEvent
```

## Структура фронтенда

```
frontend/
├── package.json              # Зависимости (React, Vite, Tailwind, Zustand)
├── vite.config.ts            # Vite конфиг: proxy /api → 8080, /ws → 8080
├── tsconfig.json             # TypeScript конфиг
├── index.html                # HTML точка входа
├── public/
│   └── icons/               # SVG иконки бирж (TODO: добавить)
└── src/
    ├── main.tsx              # React entry point
    ├── App.tsx               # Корневой компонент: инициализация WS, загрузка workspace
    ├── index.css             # Tailwind import + глобальные стили (тёмная тема)
    ├── api/
    │   ├── httpClient.ts     # REST API клиент (workspaceApi, symbolApi, densityApi, statusApi)
    │   └── wsClient.ts       # WebSocket синглтон с авторекконектом
    ├── stores/
    │   ├── workspaceStore.ts # Zustand: workspaces, активный workspace, CRUD операции
    │   ├── densityStore.ts   # Zustand: densities из WS, группировка по символу
    │   └── uiStore.ts        # Zustand: состояние модалок (settings, blacklist, symbolSettings)
    ├── hooks/
    │   └── useWebSocket.ts   # Подключение WS при монтировании, переключение workspace
    ├── types/
    │   ├── density.ts        # DensityItem, SymbolGroup, WsDensitiesMessage
    │   ├── workspace.ts      # Workspace, WorkspaceRequest, ExchangeMarketKey
    │   └── enums.ts          # Exchange, MarketType, Side, DensitySortType
    ├── utils/
    │   ├── formatters.ts     # formatVolumeUsd, formatPrice, formatDuration, isNew
    │   └── constants.ts      # EXCHANGE_LABELS, EXCHANGE_COLORS, DISABLED_MARKETS
    └── components/
        ├── layout/
        │   └── Header.tsx           # Шапка: логотип, WS-индикатор, имя workspace, кнопки
        ├── density/
        │   ├── DensityGrid.tsx      # Сетка карточек (responsive: 1-5 колонок)
        │   ├── SymbolCard.tsx       # Карточка монеты: звёздочка, кнопки ЧС/настройки
        │   └── DensityRow.tsx       # Строка плотности: биржа, объём, цена, расстояние, NEW
        ├── workspace/
        │   └── SettingsModal.tsx    # Модалка настроек workspace (биржи, сортировка, мин. размер)
        ├── symbol/
        │   ├── BlacklistPanel.tsx   # Модалка просмотра/управления ЧС
        │   └── SymbolSettingsPopup.tsx  # Модалка настроек конкретной монеты
        └── common/
            ├── Modal.tsx            # Базовый компонент модального окна
            ├── Toggle.tsx           # Toggle switch (включить/отключить)
            └── Badge.tsx            # Цветной бейдж (NEW и др.)
```

### Важные паттерны фронтенда

**Состояние и данные:**
- Zustand store — единственный источник правды для workspace и densities
- WS данные попадают в `densityStore` через `useWebSocket` хук
- Группировка плотностей по символу — в `densityStore.getSymbolGroups()`, вызывается в `DensityGrid`
- Избранные символы всегда первыми (сортировка в `getSymbolGroups`)

**Workspace:**
- `updateActiveLocal(patch)` — оптимистичное локальное обновление без запроса
- Сохранение через `update(id, req)` → PUT `/api/v1/workspaces/{id}`
- Настройки символов сохраняются через отдельные API эндпоинты (`symbolApi.*`)

**Типы данных фронтенд ↔ бэкенд:**
- `enabledMarkets` на бэкенде — `Set<String>` (ключи типа `"BINANCE_SPOT"`), на фронтенде — `ExchangeMarketKey[]` с полями `exchange` и `marketType`
- `minDensityOverrides` — ключи `"BINANCE_SPOT"`, значения числа
- `favoritedSymbols` и `blacklistedSymbols` — массивы строк (символы)

## Исправленные баги (история)

### Binance snapshot storm (fix: f8b7812)
**Симптом:** При старте 166+ ошибок `SocketTimeoutException: timeout` от BinanceSpotConnector и BinanceFuturesConnector.
**Причина:** `handleDepthUpdate()` запускал `Thread.startVirtualThread(() -> fetchAndApplySnapshot(symbol))` для каждого символа без снапшота. При 440+512 символах → сотни одновременных REST-запросов к Binance API → все таймаутят.
**Решение:** Убраны параллельные запросы из `handleDepthUpdate()` — события только буферизируются. `fetchSnapshotsForAll()` ходит по очереди, 1 req/sec. Spot SNAPSHOT_LIMIT снижен с 5000 до 1000.
**Урок:** Никогда не запускать неограниченное количество HTTP-запросов из обработчика WS-сообщений. Binance REST API жёстко лимитирует по IP.

### Binance Futures каскадные gap + Connection reset
**Симптом:** Десятки повторных gap ошибок для одних символов (TRUTHUSDT, CGPTUSDT, SUSHIUSDT итд), затем `Connection reset`.
**Причина:** При gap detection запускался virtual thread с 1 сек задержкой для refetch. За эту секунду приходили новые WS-события для того же символа, тоже обнаруживали gap, тоже запускали virtual thread. Итог: десятки параллельных REST-запросов → перегрузка → Connection reset.
**Решение:**
1. Gap detection больше НЕ запускает virtual thread
2. Символ помещается в `refetchQueue` (BlockingQueue)
3. Фоновый `refetchWorker` обрабатывает очередь последовательно (1 символ/запрос, с паузой)
4. `initializing` Set (вместо Map) — handleDepthUpdate буферизирует events пока символ в refetch
5. Publish throttle 2 сек — снижение нагрузки на CPU

### Spot gap detection неполный алгоритм
**Симптом:** Потенциально некорректный стакан — пропущенные bridging events.
**Причина:** Код проверял только `U > lastUpdateId + 1`, но не валидировал что первый event после snapshot имеет `U <= lastUpdateId+1 AND u >= lastUpdateId+1` (требование документации Binance).
**Решение:** Полная имплементация алгоритма по документации Binance — и для начальной буферизации, и для runtime gap detection. Spot и Futures теперь используют разные алгоритмы валидации согласно их API.

### Spot snapshot depth снижена до 1000 + ускорение init
**Было:** limit=5000 (вес 250), delay=2000ms → ~15 мин на 440 символов, вес ~7500/min (выше лимита 6000).
**Стало:** limit=1000 (вес 10), delay=500ms → ~4 мин на 440 символов, вес ~1200/min.
Глубина 1000 уровней достаточна для обнаружения аномальных плотностей.

### Futures snapshot delay уменьшен
**Было:** delay=1000ms. **Стало:** delay=600ms → вес 2000/min (лимит 2400).

### Refetch queue deduplication + max retries
**Проблема:** При cascade gap один символ попадал в refetchQueue десятки раз.
**Решение:**
1. `refetchPending` Set — символ добавляется в очередь только если ещё не pending
2. `MAX_GAP_RETRIES = 3` — после 3 неудачных refetch символ пропускается
3. `CountDownLatch initLatch` — refetch worker ждёт завершения `fetchSnapshotsForAll()`
4. `ConcurrentLinkedQueue` вместо `CopyOnWriteArrayList` для event buffers (O(1) vs O(n))

### HTTP 429 rate limit handling
`AbstractWebSocketConnector.executeWithRetry()` теперь обрабатывает 429:
- Читает `Retry-After` header
- Использует exponential backoff если header отсутствует
- Логирует предупреждение

### Bybit batch size fix
**Было:** batchSize=10 символов → 20 args per message (Bybit лимит 10 args).
**Стало:** batchSize=5 → 10 args per message. `getResubscribeBatchSize()` = 5.
