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

### Технологии
- Java 21 (virtual threads для WebSocket)
- Spring Boot 4.0.2
- Gradle Groovy DSL
- OkHttp для WebSocket (биржевые коннекторы)
- Spring WebSocket (фронтенд стрим)
- Jackson для JSON
- Telegram Bots API
- Apache Commons Math3 (статистика: Z-score, IQR)
- Protobuf (MEXC)

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
```
Workspaces — view-layer фильтр. Они **НЕ влияют** на alert pipeline. Только на то, что видит фронтенд.

## Workspaces

Рабочие пространства — пресеты фильтрации для фронтенда.

### Что хранит workspace
- `enabledMarkets` — включённые exchange+marketType пары (пусто = все)
- `minDensityOverrides` — минимальный порог по exchange+marketType
- `symbolMinDensityOverrides` — минимальный порог по символу
- `blacklistedSymbols` — скрытые символы
- `symbolComments` — пользовательские комментарии к символам
- `sortType` — сортировка densities (DURATION_DESC, SIZE_USD_DESC, DISTANCE_ASC)

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
# Сборка
./gradlew build

# Запуск
./gradlew bootRun

# Тесты
./gradlew test

# Только компиляция
./gradlew compileJava
```

## TODO

- [x] Реализовать все коннекторы
- [x] Telegram rate limiter
- [x] Умная дедупликация (по символу, а не по цене)
- [x] Убрать REST seed, перейти на real-time volume
- [x] REST API для настроек (workspaces, densities, status)
- [x] Density lifetime tracking
- [x] WebSocket стрим densities для фронтенда
- [ ] Починить Gate (таймауты)
- [ ] Добавить метрики (Micrometer)
- [ ] Health checks для каждой биржи
- [ ] Frontend на React

## Известные особенности бирж

### Binance
- Используем `/stream` (combined) вместо `/ws` (raw) — сообщения содержат имя стрима
- `@depth20@500ms` — partial depth snapshot, 20 уровней
- Spot: `@trade`, Futures: `@aggTrade`

### Bybit
- Unified API v5
- `orderbook.50` для глубины стакана

### OKX
- `books5` — единственный канал без лимита подписок
- `books50-l2-tbt` молча отбрасывает подписки при превышении лимита
- Подписки orderbook и trades отправляются **отдельными** WS-сообщениями

### Bitget
- API v2: `wss://ws.bitget.com/v2/ws/public`
- Канал `books15` (15 уровней). В handleMessage проверять именно `books15`

### Hyperliquid
- Только USDC пары, perpetual DEX
- Бесплатная глубина ограничена
- Нестандартный формат: l2Book subscription per coin

### MEXC
- Только Spot мониторим
- Protobuf формат + WebSocket connection pool
- Глубина до 20 уровней
- Медленная загрузка символов (много пар)

### Gate
- Отключена из-за SocketTimeoutException при подключении

## Структура пакетов

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
│   ├── WorkspaceController   # CRUD workspaces
│   ├── SymbolController      # Blacklist, comments, min-density per symbol
│   └── StatusController      # GET /api/v1/status
├── model
│   ├── domain            # OrderBook, OrderBookLevel, Density, Alert, Trade, TrackedDensity
│   ├── enums             # Exchange, MarketType, AlertType, Side, DensitySortType
│   ├── config            # GlobalConfig, ExchangeConfig, MarketTypeConfig, SymbolConfig, EffectiveConfig, ExchangeMarketKey, Workspace
│   └── dto               # DensityResponse, WorkspaceRequest
├── exchange
│   ├── common            # ExchangeConnector (interface), AbstractWebSocketConnector
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
│   └── workspace         # WorkspaceService (CRUD + JSON persistence)
└── event                 # OrderBookUpdateEvent, DensityDetectedEvent, AlertEvent, TradeEvent
```
