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
- Binance, Bybit, OKX, Bitget, Gate — полный мониторинг
- MEXC — только Spot (без Futures)
- Hyperliquid — USDC пары, ограниченная глубина
- Lighter — требует изучения API

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
- `DEFAULT_MIN_DENSITY_USD = 100_000` — минимальная плотность
- `MAX_DISTANCE_PERCENT = 10.0` — максимум от цены для анализа
- `COOLDOWN_MINUTES = 5` — пауза между алертами

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

- [ ] Реализовать все коннекторы
- [ ] Добавить метрики (Micrometer)
- [ ] Health checks для каждой биржи
- [ ] REST API для настроек (Фаза 2)
- [ ] Frontend на React (Фаза 2)

## Известные особенности бирж

### Binance
- Depth updates приходят как diff, нужен snapshot + merge
- `@depth@100ms` для быстрых обновлений

### Bybit
- Unified API v5
- `orderbook.200` для максимальной глубины

### Hyperliquid
- Только USDC пары
- Бесплатная глубина ограничена
- Нестандартный формат сообщений

### MEXC
- Только Spot мониторим
- Глубина до 20 уровней

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
