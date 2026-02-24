# CryptoView

Система мониторинга криптовалютных ордербуков для обнаружения аномальных плотностей.

## Описание

CryptoView отслеживает стаканы (orderbook) на 7 криптобиржах, находит аномально крупные лимитные заявки, отслеживает время их жизни, отправляет алерты в Telegram и предоставляет REST API + WebSocket стрим для React-фронтенда.

### Поддерживаемые биржи

| Биржа | Spot | Futures | Статус |
|-------|------|---------|--------|
| Binance | ✅ | ✅ | Активна |
| Bybit | ✅ | ✅ | Активна |
| OKX | ✅ | ✅ | Активна |
| Bitget | ✅ | ✅ | Активна |
| Gate | ✅ | ✅ | Отключена (таймауты) |
| MEXC | ✅ | ❌ | Активна (только Spot) |
| Hyperliquid | ❌ | ✅ | Активна (USDC perpetual DEX) |
| Lighter | ✅ | ✅ | Отключена |

### Типы алертов

1. **VOLUME_BASED** — плотность превышает торговый объём за 15 минут (активируется после 5 мин сбора данных per-symbol)
2. **STATISTICAL** — плотность аномальна статистически (Z-score / IQR) — работает сразу

### Ключевые возможности

- **Real-time мониторинг:** WebSocket подключение ко всем биржам, diff-based incremental depth (до 400 уровней), обработка до 50,000 msg/sec
- **Два pipeline:** Telegram-алерты + density tracking для фронтенда (независимые)
- **Density Lifetime Tracking:** отслеживание времени жизни плотностей (firstSeenAt/lastSeenAt), порог $50K
- **Workspaces:** пресеты фильтрации (blacklist, min density overrides, комментарии, сортировка)
- **REST API:** CRUD workspaces, densities с фильтрацией, настройки символов, статус системы
- **WebSocket стрим:** `/ws/densities` — broadcast отфильтрованных плотностей каждые 500мс
- **Умная дедупликация:** max 4 алерта на символ (по side + alertType), cooldown 5 мин
- **Telegram rate limiter:** очередь сообщений (~20 msg/sec), retry при 429
- **Автореконнект:** exponential backoff (до 10 попыток), ping/pong (30s), stale detection (90s)

## Требования

- Java 21+
- Gradle 8+
- Telegram Bot Token (для алертов)

## Быстрый старт

### 1. Клонирование
```bash
git clone <repository-url>
cd CryptoView
```

### 2. Конфигурация

Создайте `.env` файл или используйте переменные окружения:

```
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_CHAT_ID=your_chat_id
TELEGRAM_ENABLED=true
```

Или настройте `application.yml`:

```yaml
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
```

### 3. Запуск
```bash
./gradlew bootRun
```

### 4. Проверка
```bash
# Статус системы
curl http://localhost:8080/api/v1/status

# Активные плотности
curl http://localhost:8080/api/v1/densities

# Workspaces
curl http://localhost:8080/api/v1/workspaces
```

## Конфигурация

### Иерархия настроек

Настройки применяются в порядке приоритета (первое найденное не-null):
1. **Монета** — `symbols-config.json` (высший приоритет)
2. **Тип рынка** — `application.yml` (spot/futures для конкретной биржи)
3. **Биржа** — `application.yml` (уровень биржи)
4. **Глобальные** — `application.yml` (низший приоритет)

### Пример настройки биржи
```yaml
cryptoview:
  exchanges:
    BINANCE:
      enabled: true
      spot:
        enabled: true
        min-density-usd: 600000
      futures:
        enabled: true
        min-density-usd: 600000
```

### Настройки монет (symbols-config.json)
```json
{
  "symbols": {
    "BTCUSDT": {
      "comment": "Основная монета",
      "minDensityUsd": 500000,
      "cooldownMinutes": 3,
      "maxDistancePercent": 5.0,
      "alertTypes": ["VOLUME_BASED"],
      "enabled": true
    }
  }
}
```

## REST API

### Workspaces
| Method | Path | Описание |
|--------|------|----------|
| GET | `/api/v1/workspaces` | Список всех workspaces |
| GET | `/api/v1/workspaces/active` | Активный workspace |
| POST | `/api/v1/workspaces` | Создать workspace |
| PUT | `/api/v1/workspaces/{id}` | Обновить workspace |
| DELETE | `/api/v1/workspaces/{id}` | Удалить (нельзя активный) |
| POST | `/api/v1/workspaces/{id}/activate` | Активировать |

### Densities
| Method | Path | Query Params | Описание |
|--------|------|--------------|----------|
| GET | `/api/v1/densities` | `sort`, `limit`, `workspaceId` | Плотности с фильтрацией |

Параметры `sort`: `DURATION_DESC`, `SIZE_USD_DESC`, `DISTANCE_ASC`. По умолчанию — из настроек workspace.

### Настройки символов (внутри workspace)
| Method | Path | Описание |
|--------|------|----------|
| POST | `/api/v1/workspaces/{id}/blacklist/{symbol}` | Добавить в ЧС |
| DELETE | `/api/v1/workspaces/{id}/blacklist/{symbol}` | Убрать из ЧС |
| PUT | `/api/v1/workspaces/{id}/symbols/{symbol}/comment` | Комментарий |
| DELETE | `/api/v1/workspaces/{id}/symbols/{symbol}/comment` | Удалить комментарий |
| PUT | `/api/v1/workspaces/{id}/symbols/{symbol}/min-density` | Мин. плотность |
| DELETE | `/api/v1/workspaces/{id}/symbols/{symbol}/min-density` | Удалить override |

### Статус
| Method | Path | Описание |
|--------|------|----------|
| GET | `/api/v1/status` | connectedExchanges, totalSymbols, trackedDensities, activeWorkspace |

## WebSocket API

### Подключение
```
ws://localhost:8080/ws/densities?workspaceId=<uuid>
```
Если `workspaceId` не указан — используется активный workspace сервера.

### Смена workspace (client → server)
```json
{"action": "setWorkspace", "workspaceId": "uuid-here"}
```

### Обновление densities (server → client, каждые 500мс)
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
    "comment": null
  }]
}
```

## Формат Telegram-алертов

```
🔴 АНОМАЛЬНАЯ ПЛОТНОСТЬ

📊 BTC/USDT | Binance Futures
📈 Тип: VOLUME_BASED

💰 Объём: $1,250,000
📍 Цена: 65,432.50 (ASK)
📏 От рынка: -2.3%

📊 Объём 15м: $890,000
```

## Архитектура

```
┌─────────────────────────────────────────────────────────┐
│              Exchange WebSocket Connectors               │
│      (Binance, Bybit, OKX, Bitget, MEXC, Hyperliquid)  │
└──────────────┬─────────────────────┬────────────────────┘
               │ OrderBookUpdateEvent │ TradeEvent
               ▼                     ▼
┌──────────────────────┐  ┌─────────────────────┐
│   OrderBook Manager  │  │   Volume Tracker     │
│ (in-memory storage)  │  │ (15-min sliding win) │
└──────────┬───────────┘  └──────────┬──────────┘
           │                         │
           ▼                         │
┌──────────────────────┐             │
│   Anomaly Detector   │◄────────────┘
│ (Z-score, IQR, Vol.) │
└──────────┬───────────┘
           │ DensityDetectedEvent        ┌──────────────────────┐
           ▼                             │   Density Tracker     │
┌──────────────────────┐                 │  (lifetime tracking)  │
│    Alert Service     │                 │  ConcurrentHashMap    │
│ (dedup, cooldown)    │                 └──────────┬───────────┘
└──────────┬───────────┘                            │
           │ AlertEvent                             ▼
           ▼                             ┌──────────────────────┐
┌──────────────────────┐                 │ DensityFilterService  │
│   Telegram Service   │                 │ (workspace filtering) │
│  (rate-limited send) │                 └────┬────────────┬────┘
└──────────────────────┘                      │            │
                                              ▼            ▼
                                      REST /densities   WS /ws/densities
                                                        (500ms broadcast)
```

**Два независимых pipeline:**
1. **Alert pipeline** (левая часть) — для Telegram-алертов. Используется AnomalyDetector с порогами из application.yml.
2. **Density tracking pipeline** (правая часть) — для фронтенда. Отслеживает все плотности >$50K, фильтрует по workspace.

Workspaces НЕ влияют на alert pipeline. Они фильтруют только то, что видит фронтенд.

## Структура проекта

```
src/main/java/com/cryptoview/
├── config/                # Конфигурация
│   ├── AsyncConfig        # ThreadPool (4-16 threads)
│   ├── CryptoViewProperties  # application.yml binding
│   ├── JacksonConfig      # JSON (JavaTimeModule, ISO-8601)
│   ├── OkHttpConfig       # OkHttp для бирж
│   ├── WebConfig          # CORS (localhost:3000)
│   └── WebSocketConfig    # WS endpoint /ws/densities
├── controller/            # REST + WebSocket
│   ├── DensityController  # GET /api/v1/densities
│   ├── DensityWebSocketHandler  # WS стрим (500мс broadcast)
│   ├── WorkspaceController     # CRUD workspaces
│   ├── SymbolController   # Blacklist, comments, min-density
│   └── StatusController   # GET /api/v1/status
├── model/
│   ├── domain/            # OrderBook, Density, Alert, Trade, TrackedDensity
│   ├── enums/             # Exchange, MarketType, AlertType, Side, DensitySortType
│   ├── config/            # GlobalConfig, ExchangeConfig, Workspace, ExchangeMarketKey, ...
│   └── dto/               # DensityResponse, WorkspaceRequest
├── exchange/              # Биржевые коннекторы
│   ├── common/            # ExchangeConnector, AbstractWebSocketConnector, LocalOrderBook
│   ├── ExchangeManager    # Управление коннекторами
│   ├── binance/           # Spot + Futures
│   ├── bybit/             # Spot + Futures
│   ├── okx/               # Spot + Futures
│   ├── bitget/            # Spot + Futures
│   ├── gate/              # Disabled
│   ├── mexc/              # Spot only (protobuf)
│   ├── hyperliquid/       # Futures only (USDC DEX)
│   └── lighter/           # Disabled
├── service/
│   ├── config/            # ConfigService (hierarchical config)
│   ├── orderbook/         # OrderBookManager (in-memory)
│   ├── volume/            # VolumeTracker (15-min window)
│   ├── detector/          # AnomalyDetector (Z-score, IQR)
│   ├── alert/             # AlertService (Density → Alert)
│   ├── telegram/          # TelegramService (rate-limited)
│   ├── density/           # DensityTracker + DensityFilterService
│   └── workspace/         # WorkspaceService (CRUD + JSON)
└── event/                 # Spring Events
```

## Производительность

- Обработка до 50,000 сообщений/сек
- Латентность детекции < 100ms
- Потребление RAM: 2-4 GB
- Рекомендуется VPS с хорошим каналом

## Лицензия

MIT

## Документация

- [Техническое задание](TECHNICAL_SPECIFICATION.md)
- [План работ](WORK_PLAN.md)
