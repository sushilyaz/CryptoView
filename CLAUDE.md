# CLAUDE.md — Контекст для разработки

## О проекте

CryptoView — система мониторинга криптовалютных ордербуков для обнаружения аномальных плотностей.

## Ключевые решения

### Архитектура
- **Монолит** на Spring Boot (достаточно для текущих требований)
- **Event-driven** внутри приложения (Spring Events)
- **In-memory** хранение стаканов (ConcurrentHashMap)
- **Скользящее окно** для объёмов (15 минут)

### Технологии
- Java 21 (virtual threads для WebSocket)
- Spring Boot 3.x
- Gradle Groovy DSL
- OkHttp для WebSocket
- Jackson для JSON
- Telegram Bots API

### Биржи
- Binance, Bybit, OKX, Bitget — полный мониторинг (Spot + Futures)
- Gate — **отключена** (таймауты подключения)
- MEXC — только Spot (без Futures)
- Hyperliquid — только Futures (USDC perpetual DEX)
- Lighter — требует проверки API

## Паттерны кода

### Коннекторы бирж
Все коннекторы наследуются от `AbstractExchangeConnector`:
```java
public abstract class AbstractExchangeConnector {
    abstract void connect();
    abstract void subscribe(List<String> symbols);
    abstract void handleMessage(String message);
    // Общая логика реконнекта
}
```

### Конфигурация
Иерархия настроек: Global → Exchange → MarketType → Symbol
```java
configService.getEffectiveConfig(exchange, marketType, symbol);
```

### События
```java
// Публикация
eventPublisher.publishEvent(new DensityDetectedEvent(...));

// Обработка
@EventListener
public void handleDensity(DensityDetectedEvent event) { ... }
```

## Важные константы

- `VOLUME_WINDOW_MINUTES = 15` — окно для подсчёта объёма
- `MIN_VOLUME_TRACKING_SEC = 300` — минимум данных для VOLUME_BASED (5 мин)
- `DEFAULT_MIN_DENSITY_USD = 100_000` — минимальная плотность
- `MAX_DISTANCE_PERCENT = 10.0` — максимум от цены для анализа
- `COOLDOWN_MINUTES = 5` — пауза между алертами
- `SEND_INTERVAL_MS = 50` — интервал отправки Telegram (~20 msg/sec)

## Форматы данных

### OrderBook
```java
record OrderBook(
    String symbol,
    Exchange exchange,
    MarketType marketType,
    List<OrderBookLevel> bids,  // отсортированы по убыванию цены
    List<OrderBookLevel> asks,  // отсортированы по возрастанию цены
    BigDecimal lastPrice,
    Instant timestamp
)
```

### Alert
```java
record Alert(
    String symbol,
    Exchange exchange,
    MarketType marketType,
    AlertType type,           // VOLUME_BASED, STATISTICAL
    Side side,                // BID, ASK
    BigDecimal price,
    BigDecimal volumeUsd,
    BigDecimal distancePercent,
    BigDecimal volume15min,
    String comment,
    Instant timestamp
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
- [ ] Починить Gate (таймауты)
- [ ] Добавить метрики (Micrometer)
- [ ] Health checks для каждой биржи
- [ ] REST API для настроек (Фаза 2)
- [ ] Frontend на React (Фаза 2)

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
- Глубина до 20 уровней
- Медленная загрузка символов (много пар)

### Gate
- Отключена из-за SocketTimeoutException при подключении

## Структура пакетов

```
com.cryptoview
├── config           # @Configuration классы
├── model
│   ├── domain       # OrderBook, Alert, Density
│   ├── enums        # Exchange, MarketType, AlertType
│   └── config       # ExchangeConfig, SymbolConfig
├── exchange
│   ├── common       # AbstractConnector, interfaces
│   ├── binance
│   ├── bybit
│   └── ...
├── service
│   ├── orderbook    # OrderBookManager
│   ├── volume       # VolumeTracker
│   ├── detector     # AnomalyDetector
│   ├── alert        # AlertService
│   └── telegram     # TelegramService
└── event            # Spring Events
```
