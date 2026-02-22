# CryptoView — Техническое задание

## 1. Общее описание

**CryptoView** — система мониторинга криптовалютных ордербуков для обнаружения аномальных плотностей (крупных лимитных заявок) на множестве бирж. Предоставляет Telegram-алерты и real-time API для React-фронтенда.

### 1.1 Цели проекта
- Мониторинг стаканов (orderbook) на 7 криптобиржах
- Обнаружение аномальных плотностей по двум алгоритмам
- Отправка алертов в Telegram
- Отслеживание времени жизни плотностей (density lifetime tracking)
- REST API и WebSocket стрим для React-фронтенда
- Workspaces — пресеты фильтрации для разных сценариев использования
- Гибкая система настроек (глобальные, по бирже, по типу рынка, по монете)

### 1.2 Биржи и типы рынков

| Биржа | Spot | Futures | Котировочная валюта | Статус |
|-------|------|---------|---------------------|--------|
| Binance | ✅ | ✅ | USDT | Активна |
| Bybit | ✅ | ✅ | USDT | Активна |
| OKX | ✅ | ✅ | USDT | Активна |
| Bitget | ✅ | ✅ | USDT | Активна |
| Gate | ✅ | ✅ | USDT | Отключена (таймауты) |
| MEXC | ✅ | ❌ | USDT | Активна (только Spot) |
| Hyperliquid | ❌ | ✅ | USDC | Активна (perpetual DEX) |
| Lighter | ✅ | ✅ | USDT | Отключена |

---

## 2. Архитектура

### 2.1 Два независимых pipeline

Система имеет два data pipeline, которые работают параллельно и независимо:

```
Exchange WS Connectors (Binance, Bybit, OKX, Bitget, MEXC, Hyperliquid)
         │                              │
         │ OrderBookUpdateEvent         │ TradeEvent
         ▼                              ▼
   OrderBookManager                VolumeTracker
   (in-memory ConcurrentHashMap)   (15-min sliding window)
         │                              │
         ├──────────────┬───────────────┘
         │              │
         ▼              ▼
  ┌─────────────────────────────┐
  │      Anomaly Detector       │     ┌──────────────────────┐
  │  (Z-score, IQR, Vol-based)  │     │   Density Tracker     │
  └──────────┬──────────────────┘     │ (lifetime tracking,   │
             │                        │  ConcurrentHashMap)    │
             │ DensityDetectedEvent   └──────────┬────────────┘
             ▼                                   │
       AlertService                    DensityFilterService
       (dedup, cooldown)               (workspace filtering)
             │                              │           │
             │ AlertEvent                   ▼           ▼
             ▼                        REST API    WebSocket стрим
       TelegramService             /api/v1/...   /ws/densities
       (rate-limited)
```

**Pipeline 1: Alert (Telegram)** — `OrderBookUpdateEvent` → `AnomalyDetector` → `DensityDetectedEvent` → `AlertService` → `AlertEvent` → `TelegramService`. Использует пороги из `application.yml` и `symbols-config.json`. Дедупликация по `{exchange}_{marketType}_{symbol}_{side}_{alertType}` с cooldown.

**Pipeline 2: Density Tracking (Frontend)** — `OrderBookUpdateEvent` → `DensityTracker` → `DensityFilterService` → REST/WebSocket. Отслеживает ВСЕ плотности > $50K (TRACKING_FLOOR), ведёт lifetime (firstSeenAt/lastSeenAt). Фильтрация через workspaces — view-layer, НЕ влияет на pipeline 1.

### 2.2 Event-driven внутренняя архитектура

Все коммуникации между компонентами — через Spring Events:

| Event | Publisher | Listeners |
|-------|-----------|-----------|
| `OrderBookUpdateEvent` | OrderBookManager | AnomalyDetector, DensityTracker |
| `TradeEvent` | Exchange Connectors | VolumeTracker |
| `DensityDetectedEvent` | AnomalyDetector | AlertService |
| `AlertEvent` | AlertService | TelegramService |

### 2.3 Хранение данных

| Данные | Хранилище | Persistence |
|--------|-----------|-------------|
| Orderbooks | `ConcurrentHashMap` | Нет (in-memory) |
| Volume (15 мин) | `ConcurrentHashMap<Queue>` | Нет (in-memory) |
| Tracked Densities | `ConcurrentHashMap` | Нет (сброс при рестарте) |
| Workspaces | `ConcurrentHashMap` | `workspaces.json` (переживает рестарт) |
| Symbol configs | `ConcurrentHashMap` | `symbols-config.json` |
| App config | Spring Properties | `application.yml` |

---

## 3. Функциональные требования

### 3.1 Мониторинг ордербуков

#### 3.1.1 Подключение к биржам
- WebSocket соединения для real-time данных
- Автоматический реконнект при обрыве (exponential backoff, max 10 попыток)
- Ping/pong heartbeat каждые 30 сек
- Stale data detection: если нет данных 90 сек — force reconnect
- Подписка на все доступные торговые пары
- Virtual threads (Java 21) для WebSocket обработки

#### 3.1.2 Фильтрация данных
- Анализируются только уровни в пределах **10% от текущей цены**
- Если биржа предоставляет меньше 10% глубины — используется всё доступное

#### 3.1.3 Сбор объёмов
- Агрегация торговых объёмов за последние **15 минут** (скользящее окно)
- Отдельно для каждой комбинации: биржа + тип рынка + монета
- Объёмы собираются в реальном времени из WebSocket trade-потоков (без REST seed)
- VOLUME_BASED алерты активируются per-symbol после накопления **5 минут** данных
- STATISTICAL алерты работают сразу после подключения

### 3.2 Детекция аномалий (Alert Pipeline)

#### 3.2.0 Общий алгоритм фильтрации (пошагово)

При каждом обновлении стакана система проходит следующие шаги для **каждого уровня цены**:

```
Шаг 1: Предварительный фильтр (min-density-usd)
│   Объём уровня < minDensityUsd?
│   ├─ ДА → уровень ОТБРОШЕН, дальше не проверяется
│   └─ НЕТ → переходим к Шагу 2
│
Шаг 2: Проверка VOLUME_BASED (если включён)
│   Объём уровня > volume15min?
│   ├─ ДА → проверка дедупликации → если не дубликат → АЛЕРТ
│   └─ НЕТ → ничего
│
Шаг 3: Проверка STATISTICAL (если включён, независимо от Шага 2)
│   Объём уровня > statisticalThreshold?
│   ├─ ДА → проверка дедупликации → если не дубликат → АЛЕРТ
│   └─ НЕТ → ничего
```

**Важно:** `min-density-usd` — это входной порог-фильтр. Если уровень его не прошёл, ни VOLUME_BASED, ни STATISTICAL проверки не выполняются. Если прошёл — обе проверки выполняются **независимо друг от друга** и могут сгенерировать **два отдельных алерта** на один и тот же уровень.

#### 3.2.0.1 Разрешение значения min-density-usd (каскад приоритетов)

Значение `minDensityUsd` определяется по каскадной иерархии (первое найденное не-null):

```
1. symbols-config.json  (конкретная монета, наивысший приоритет)
2. application.yml      (marketType: spot/futures для биржи)
3. application.yml      (exchange: уровень биржи)
4. application.yml      (global: глобальное значение, самый низкий приоритет)
```

#### 3.2.1 Тип 1: VOLUME_BASED (по торговой стратегии)
Плотность считается аномальной, если:
```
orderSizeUSD > volume15minUSD
```
Где:
- `orderSizeUSD` — объём лимитной заявки в USD
- `volume15minUSD` — суммарный проторгованный объём за 15 минут

#### 3.2.2 Тип 2: STATISTICAL (статистический)
Используется комбинация методов для определения аномалий:

**Z-score метод:**
```
μ = среднее значение ордеров в стакане
σ = стандартное отклонение
Z = (orderSize - μ) / σ
isAnomaly = |Z| > 3
```

**IQR метод (более устойчив к выбросам):**
```
Q1 = 25-й перцентиль размеров ордеров
Q3 = 75-й перцентиль
IQR = Q3 - Q1
upperBound = Q3 + 3 × IQR
isAnomaly = orderSize > upperBound
```

**Итоговый порог:** берётся `min(Z-score threshold, IQR threshold)` для более строгой фильтрации.

#### 3.2.3 Дедупликация алертов

Ключ дедупликации: `{exchange}_{marketType}_{symbol}_{side}_{alertType}`

Cooldown: настраиваемый (по умолчанию 5 минут). Если алерт с таким же ключом уже отправлялся в пределах cooldown-периода, повторный алерт не отправляется.

**Агрегация:** при анализе стакана система собирает все аномальные уровни и публикует только **самый крупный** для каждой комбинации side + alertType. Максимум 4 алерта на символ за один анализ стакана (BID_VOLUME_BASED, BID_STATISTICAL, ASK_VOLUME_BASED, ASK_STATISTICAL).

### 3.3 Density Lifetime Tracking (Density Tracking Pipeline)

#### 3.3.1 Принцип работы

DensityTracker слушает каждый `OrderBookUpdateEvent` и ведёт реестр всех плотностей > $50K:

```
Структуры данных:
  activeDensities: ConcurrentHashMap<trackingKey, TrackedDensity>
  densitiesByOrderBookKey: ConcurrentHashMap<orderBookKey, Set<trackingKey>>

trackingKey = "{exchange}_{marketType}_{symbol}_{side}_{price}"
orderBookKey = "{exchange}_{marketType}_{symbol}"
```

#### 3.3.2 Алгоритм обновления (на каждый OrderBookUpdateEvent)

```
1. Построить orderBookKey
2. Получить previousKeys для этого orderBookKey
3. Для каждого уровня bids + asks:
   a. volumeUsd = price × quantity
   b. Если volumeUsd < TRACKING_FLOOR (50K) → skip
   c. Построить trackingKey
   d. Если уже есть в activeDensities → обновить lastSeenAt, quantity, volumeUsd, distancePercent
   e. Если нет → создать новый TrackedDensity с firstSeenAt = now
   f. Добавить trackingKey в currentKeys
4. Удалить исчезнувшие: previousKeys - currentKeys → remove из activeDensities
5. Обновить densitiesByOrderBookKey
```

#### 3.3.3 Cleanup

`@Scheduled` каждые 10 секунд: удалить `TrackedDensity` с `lastSeenAt > 2 мин` назад (для случаев когда ордербук перестал обновляться).

#### 3.3.4 TrackedDensity

```java
record TrackedDensity(
    String symbol, Exchange exchange, MarketType marketType, Side side,
    BigDecimal price, BigDecimal quantity, BigDecimal volumeUsd,
    BigDecimal distancePercent, BigDecimal lastPrice,
    Instant firstSeenAt, Instant lastSeenAt
)
// durationSeconds() = lastSeenAt - firstSeenAt
```

### 3.4 Workspaces

Рабочие пространства — view-layer фильтры для фронтенда. Они **НЕ влияют** на alert pipeline (Telegram). Только на данные, возвращаемые через REST API и WebSocket.

#### 3.4.1 Что хранит workspace

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | String (UUID) | Уникальный идентификатор |
| `name` | String | Название |
| `active` | boolean | Активный ли (один активный) |
| `enabledMarkets` | Set\<String\> | Включённые exchange+marketType (пусто = все) |
| `minDensityOverrides` | Map\<String, BigDecimal\> | Мин. порог по exchange_marketType |
| `symbolMinDensityOverrides` | Map\<String, BigDecimal\> | Мин. порог по символу |
| `blacklistedSymbols` | Set\<String\> | Скрытые символы |
| `symbolComments` | Map\<String, String\> | Комментарии к символам |
| `sortType` | DensitySortType | Сортировка (DURATION_DESC, SIZE_USD_DESC, DISTANCE_ASC) |

#### 3.4.2 Фильтрация densities по workspace

```
allActiveDensities (из DensityTracker)
  → filter: exchange+marketType ∈ enabledMarkets (или все, если пусто)
  → filter: symbol ∉ blacklistedSymbols
  → filter: volumeUsd >= resolvedMinDensity(workspace, density)
  → enrich: добавить comment из workspace.symbolComments
  → sort: по workspace.sortType
```

#### 3.4.3 Приоритет resolveMinDensity (для workspace)

1. `workspace.symbolMinDensityOverrides[symbol]` (наивысший)
2. `workspace.minDensityOverrides[exchange_marketType]`
3. `configService.getEffectiveConfig()` из application.yml (fallback)

#### 3.4.4 Хранение

In-memory (`ConcurrentHashMap`) + JSON файл `workspaces.json` (переживает рестарт). При первом запуске создаётся default workspace.

### 3.5 Система алертов

#### 3.5.1 Содержание алерта
- Биржа и тип рынка
- Символ (торговая пара)
- Тип алерта (VOLUME_BASED / STATISTICAL)
- Сторона (BID / ASK)
- Цена уровня
- Объём плотности в USD
- Расстояние от текущей цены (%)
- Объём за 15 минут (для контекста)
- Комментарий к монете (если есть)

#### 3.5.2 Telegram интеграция
- Отправка через Telegram Bot API
- Поддержка форматирования (Markdown)
- Возможность указать несколько chat_id
- **Rate limiter:** очередь сообщений (~20 msg/sec, интервал 50мс), retry с backoff при 429
- Максимальный размер очереди: 500 сообщений

### 3.6 Система настроек

#### 3.6.1 Иерархия настроек (от общего к частному)
1. **Глобальные** — применяются ко всем
2. **По бирже** — переопределяют глобальные для конкретной биржи
3. **По типу рынка** — переопределяют настройки биржи для spot/futures
4. **По монете** — переопределяют все вышестоящие для конкретной монеты

#### 3.6.2 Параметры настроек
| Параметр | Описание | По умолчанию |
|----------|----------|--------------|
| `minDensityUsd` | Минимальный объём плотности для алерта | 100,000 |
| `enabled` | Включен ли мониторинг | true |
| `alertTypes` | Типы алертов (VOLUME_BASED, STATISTICAL) | оба |
| `cooldownMinutes` | Пауза между алертами для одной монеты | 5 |
| `maxDistancePercent` | Макс. расстояние от цены для анализа | 10.0 |

---

## 4. REST API

### 4.1 Workspaces

| Method | Path | Описание |
|--------|------|----------|
| GET | `/api/v1/workspaces` | Список всех workspaces |
| GET | `/api/v1/workspaces/active` | Активный workspace |
| POST | `/api/v1/workspaces` | Создать workspace |
| PUT | `/api/v1/workspaces/{id}` | Обновить workspace |
| DELETE | `/api/v1/workspaces/{id}` | Удалить (нельзя удалить активный) |
| POST | `/api/v1/workspaces/{id}/activate` | Активировать workspace |

**Тело POST/PUT запроса (WorkspaceRequest):**
```json
{
  "name": "My Workspace",
  "enabledMarkets": ["BINANCE_SPOT", "BINANCE_FUTURES"],
  "minDensityOverrides": {"BINANCE_FUTURES": 500000},
  "symbolMinDensityOverrides": {"BTCUSDT": 1000000},
  "blacklistedSymbols": ["DOGEUSDT"],
  "symbolComments": {"BTCUSDT": "основная монета"},
  "sortType": "SIZE_USD_DESC"
}
```

### 4.2 Densities

| Method | Path | Query Params | Описание |
|--------|------|--------------|----------|
| GET | `/api/v1/densities` | `sort`, `limit`, `workspaceId` | Плотности с фильтрацией по workspace |

**Query параметры:**
- `sort` — `DURATION_DESC`, `SIZE_USD_DESC`, `DISTANCE_ASC` (по умолчанию из workspace)
- `limit` — макс. количество (по умолчанию 200)
- `workspaceId` — UUID (по умолчанию — активный workspace)

**Ответ (DensityResponse):**
```json
{
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
}
```

### 4.3 Настройки символов (внутри workspace)

| Method | Path | Body | Описание |
|--------|------|------|----------|
| POST | `/api/v1/workspaces/{id}/blacklist/{symbol}` | — | Добавить в ЧС |
| DELETE | `/api/v1/workspaces/{id}/blacklist/{symbol}` | — | Убрать из ЧС |
| PUT | `/api/v1/workspaces/{id}/symbols/{symbol}/comment` | `{"comment": "..."}` | Комментарий |
| DELETE | `/api/v1/workspaces/{id}/symbols/{symbol}/comment` | — | Удалить комментарий |
| PUT | `/api/v1/workspaces/{id}/symbols/{symbol}/min-density` | `{"minDensity": 500000}` | Мин. плотность |
| DELETE | `/api/v1/workspaces/{id}/symbols/{symbol}/min-density` | — | Удалить override |

### 4.4 Статус

| Method | Path | Описание |
|--------|------|----------|
| GET | `/api/v1/status` | Общий статус системы |

**Ответ:**
```json
{
  "connectedExchanges": 9,
  "totalSymbols": 1250,
  "trackedDensities": 342,
  "activeWorkspace": "Default",
  "activeWorkspaceId": "uuid-here"
}
```

---

## 5. WebSocket API

### 5.1 Подключение

```
ws://localhost:8080/ws/densities?workspaceId=<uuid>
```

- Raw WebSocket (без STOMP) — клиент использует native WebSocket API
- Если `workspaceId` не указан — используется активный workspace сервера
- Allowed origins: `http://localhost:3000`

### 5.2 Протокол

#### Смена workspace (client → server)
```json
{"action": "setWorkspace", "workspaceId": "uuid-here"}
```

#### Обновление densities (server → client, каждые 500мс)
```json
{
  "type": "densities",
  "timestamp": "2026-02-22T12:00:00Z",
  "count": 42,
  "data": [{ ...DensityResponse... }]
}
```

### 5.3 Поведение

- Broadcast каждые 500мс (scheduled)
- Для каждого клиента — своя фильтрация по его workspace
- Если нет подключённых клиентов — broadcast не выполняется
- Фильтрация идентична REST (`DensityFilterService` — shared логика)
- При ошибке отправки — сессия закрывается и удаляется

---

## 6. Технический стек

### 6.1 Backend
- **Язык:** Java 21 (virtual threads)
- **Фреймворк:** Spring Boot 4.0.2
- **Сборщик:** Gradle (Groovy DSL)
- **WebSocket (биржи):** OkHttp 4.12.0
- **WebSocket (фронтенд):** Spring WebSocket (raw, без STOMP)
- **JSON:** Jackson + JavaTimeModule (ISO-8601)
- **Telegram:** Telegram Bots API 6.9.7.1
- **Статистика:** Apache Commons Math3 3.6.1
- **Protobuf:** Google Protobuf 3.25.3 (MEXC)
- **Env:** springboot4-dotenv

### 6.2 Frontend (Фаза 2, планируется)
- **Фреймворк:** React 18+
- **Язык:** TypeScript
- **WebSocket:** native WebSocket API
- **UI библиотека:** TBD

---

## 7. API бирж

### 7.1 Binance
- **Spot WebSocket:** `wss://stream.binance.com:9443/stream` (combined stream)
- **Futures WebSocket:** `wss://fstream.binance.com/stream` (combined stream)
- **Orderbook stream:** `<symbol>@depth20@500ms` (partial depth, 20 уровней)
- **Trade stream:** Spot: `<symbol>@trade`, Futures: `<symbol>@aggTrade`

### 7.2 Bybit
- **Spot WebSocket:** `wss://stream.bybit.com/v5/public/spot`
- **Futures WebSocket:** `wss://stream.bybit.com/v5/public/linear`
- **Orderbook stream:** `orderbook.50.<symbol>` (50 уровней)

### 7.3 OKX
- **WebSocket:** `wss://ws.okx.com:8443/ws/v5/public`
- **Orderbook channel:** `books5` (5 уровней, единственный канал без лимита подписок)
- **Trade channel:** `trades`
- **Особенность:** подписки orderbook и trades отправляются отдельными WS-сообщениями

### 7.4 Bitget
- **WebSocket:** `wss://ws.bitget.com/v2/ws/public` (API v2)
- **Orderbook channel:** `books15` (15 уровней)
- **Trade channel:** `trade`

### 7.5 Gate (отключена)
- **Spot WebSocket:** `wss://api.gateio.ws/ws/v4/`
- **Futures WebSocket:** `wss://fx-ws.gateio.ws/v4/ws/usdt`
- **Статус:** отключена из-за SocketTimeoutException при подключении

### 7.6 MEXC (только Spot)
- **WebSocket:** `wss://wbs.mexc.com/ws`
- **Формат:** Protobuf + WebSocket connection pool
- **Глубина:** до 20 уровней

### 7.7 Hyperliquid (только Futures)
- **WebSocket:** `wss://api.hyperliquid.xyz/ws`
- **Orderbook:** l2Book subscription per coin
- **Особенности:** только USDC пары, perpetual DEX, ограниченная бесплатная глубина

---

## 8. Конфигурация

### 8.1 application.yml (основной конфиг)
```yaml
server:
  port: 8080

cryptoview:
  telegram:
    bot-token: ${TELEGRAM_BOT_TOKEN}
    chat-ids:
      - ${TELEGRAM_CHAT_ID}
    enabled: ${TELEGRAM_ENABLED:false}

  global:
    min-density-usd: 100000
    cooldown-minutes: 5
    max-distance-percent: 10.0
    alert-types:
      - VOLUME_BASED
      - STATISTICAL
    enabled: true

  exchanges:
    BINANCE:
      enabled: true
      spot:
        enabled: true
        min-density-usd: 600000
      futures:
        enabled: true
        min-density-usd: 600000
    BYBIT:
      enabled: true
      spot:
        enabled: true
        min-density-usd: 500000
      futures:
        enabled: true
        min-density-usd: 500000
    OKX:
      enabled: true
      spot:
        enabled: true
        min-density-usd: 400000
      futures:
        enabled: true
        min-density-usd: 400000
    BITGET:
      enabled: true
      spot:
        enabled: true
        min-density-usd: 400000
      futures:
        enabled: true
        min-density-usd: 400000
    GATE:
      enabled: false
    MEXC:
      enabled: true
      spot:
        enabled: true
        min-density-usd: 400000
      futures:
        enabled: false
    HYPERLIQUID:
      enabled: true
      futures:
        enabled: true
        min-density-usd: 400000
    LIGHTER:
      enabled: false
```

### 8.2 symbols-config.json (настройки монет)
```json
{
  "symbols": {
    "BTCUSDT": {
      "comment": "Основная монета, высокая ликвидность",
      "minDensityUsd": 500000,
      "cooldownMinutes": 3,
      "maxDistancePercent": 5.0,
      "alertTypes": ["VOLUME_BASED"],
      "enabled": true
    }
  }
}
```

### 8.3 workspaces.json (workspaces, автоматически)
```json
[{
  "id": "uuid-here",
  "name": "Default",
  "active": true,
  "enabledMarkets": [],
  "minDensityOverrides": {},
  "symbolMinDensityOverrides": {},
  "blacklistedSymbols": [],
  "symbolComments": {},
  "sortType": "SIZE_USD_DESC"
}]
```

---

## 9. Константы

| Константа | Значение | Компонент | Назначение |
|-----------|----------|-----------|------------|
| `VOLUME_WINDOW_MINUTES` | 15 | VolumeTracker | Окно для подсчёта объёма |
| `MIN_VOLUME_TRACKING_SEC` | 300 (5 мин) | AnomalyDetector | Минимум данных для VOLUME_BASED |
| `DEFAULT_MIN_DENSITY_USD` | 100,000 | ConfigService | Глобальная мин. плотность |
| `TRACKING_FLOOR` | 50,000 | DensityTracker | Мин. объём для lifetime tracking |
| `MAX_DISTANCE_PERCENT` | 10.0 | OrderBookManager | Макс. расстояние от цены |
| `COOLDOWN_MINUTES` | 5 | AnomalyDetector | Пауза между алертами |
| `SEND_INTERVAL_MS` | 50 | TelegramService | Интервал отправки (~20 msg/sec) |
| `STALE_THRESHOLD` | 2 мин | DensityTracker | Cleanup давно не обновлённых |
| `WS_BROADCAST_INTERVAL` | 500мс | DensityWebSocketHandler | Интервал broadcast |
| `Z_SCORE_THRESHOLD` | 3.0 | AnomalyDetector | Порог статистической аномалии |
| `IQR_MULTIPLIER` | 3.0 | AnomalyDetector | Множитель IQR |
| `STALE_DATA_THRESHOLD_MS` | 90,000 | AbstractWebSocketConnector | Force reconnect при молчании |
| `MAX_RECONNECT_ATTEMPTS` | 10 | AbstractWebSocketConnector | Макс. попыток реконнекта |
| `PING_INTERVAL_SEC` | 30 | AbstractWebSocketConnector | Heartbeat интервал |

---

## 10. Формат алерта в Telegram

```
🔴 АНОМАЛЬНАЯ ПЛОТНОСТЬ

📊 BTC/USDT | Binance Futures
📈 Тип: VOLUME_BASED

💰 Объём: $1,250,000
📍 Цена: 65,432.50 (ASK)
📏 От рынка: -2.3%

📊 Объём 15м: $890,000
⏱ Время: 14:32:15 UTC

💬 Комментарий: Основная монета, высокая ликвидность
```

---

## 11. Фазы разработки

### Фаза 1: Backend + Telegram ✅
- Все коннекторы к биржам (WebSocket)
- OrderBookManager (in-memory хранение)
- VolumeTracker (15-мин скользящее окно)
- AnomalyDetector (Z-score, IQR, volume-based)
- AlertService (дедупликация, cooldown)
- TelegramService (rate-limited)
- ConfigService (иерархическая конфигурация)
- Логирование (console + file, rolling 50MB/30d)

### Фаза 1.5: REST API + WebSocket + Workspaces ✅
- DensityTracker (lifetime tracking, ConcurrentHashMap)
- DensityFilterService (фильтрация по workspace, shared логика)
- WorkspaceService (CRUD + JSON persistence)
- REST API: workspaces, densities, symbol settings, status
- WebSocket `/ws/densities` (500мс broadcast)
- CORS для React (localhost:3000)

### Фаза 2: Frontend (планируется)
- React приложение
- Дашборд с плотностями в реальном времени
- Управление workspaces
- Blacklist, комментарии, пороги через UI
- WebSocket подключение к `/ws/densities`

---

## 12. Нефункциональные требования

### 12.1 Производительность
- Обработка до 50,000 сообщений/сек от бирж
- Латентность детекции < 100ms
- Потребление RAM < 4 GB
- WebSocket broadcast фронтенду: 500мс интервал

### 12.2 Надёжность
- Автоматический реконнект при обрыве WebSocket
- Graceful shutdown (ExchangeManager.@PreDestroy)
- Логирование всех ошибок
- workspaces.json переживает рестарт

### 12.3 Масштабируемость
- Архитектура позволяет добавлять новые биржи
- Workspaces — view-layer, не нагружают data pipeline
- DensityTracker cleanup предотвращает memory leak

---

## 13. Риски и ограничения

### 13.1 Rate Limits
- Каждая биржа имеет лимиты на WebSocket подключения
- Необходимо соблюдать ограничения, иначе бан IP

### 13.2 Hyperliquid
- Бесплатная версия имеет ограниченную глубину стакана

### 13.3 Gate
- Отключена из-за SocketTimeoutException при подключении

### 13.4 Объём данных
- При мониторинге всех монет — высокая нагрузка
- Рекомендуется VPS с хорошим интернет-каналом

### 13.5 In-memory хранение
- Tracked densities сбрасываются при рестарте
- workspaces.json — единственный persistent storage
- БД (PostgreSQL) планируется в будущем
