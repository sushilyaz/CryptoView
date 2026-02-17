# План: Починка коннекторов, предзагрузка volume15min, улучшение логирования

## Часть 1: Починка сломанных бирж

### 1.1 Binance — "Payload too long" (Spot + Futures)

**Причина:** `buildSubscribeMessage()` объединяет depth + trade стримы в одно сообщение. Батч 200 символов × 2 = 400 params. Binance закрывает соединение при слишком большом payload.

**Факты из документации:**
- Макс 1024 стримов на одно подключение
- Макс 10 входящих сообщений/сек (Futures), 5/сек (Spot)
- Подключение живёт 24 часа

**Исправление:**
- Разделить подписку на depth и trade в **два отдельных сообщения**
- Уменьшить размер батча с 200 до 100 символов
- Переопределить `subscribe()` в обоих Binance-коннекторах чтобы отправлять 2 сообщения с паузой 100ms

**Файлы:**
- `src/main/java/com/cryptoview/exchange/binance/BinanceFuturesConnector.java`
- `src/main/java/com/cryptoview/exchange/binance/BinanceSpotConnector.java`

### 1.2 Gate — "timeout" при получении символов

**Причина:** REST-запрос к Gate API без retry-логики. Один таймаут = коннектор мёртв.

**Исправление:**
- Добавить метод `executeWithRetry(Request, maxRetries, retryDelayMs)` в `AbstractWebSocketConnector`
- Применить в `fetchAllSymbols()` обоих Gate-коннекторов (3 попытки, 5сек между ними)
- Применить во всех остальных коннекторах для устойчивости

**Файлы:**
- `src/main/java/com/cryptoview/exchange/common/AbstractWebSocketConnector.java` — добавить `executeWithRetry()`
- `src/main/java/com/cryptoview/exchange/gate/GateSpotConnector.java`
- `src/main/java/com/cryptoview/exchange/gate/GateFuturesConnector.java`
- Все остальные коннекторы — обновить `fetchAllSymbols()`

### 1.3 MEXC — "Found 0 USDT trading pairs"

**Причина:** Код проверяет `"ENABLED".equals(status)`, но MEXC API v3 возвращает `"1"` для активных пар.

**Источник:** [MEXC API docs](https://mexcdevelop.github.io/apidocs/spot_v3_en/) — status: `"1"` = online, `"2"` = Pause, `"3"` = offline. Поле `isSpotTradingAllowed` существует и корректно.

**Исправление:**
- Изменить фильтр: `"ENABLED".equals(status)` → `"1".equals(status)`

**Файл:**
- `src/main/java/com/cryptoview/exchange/mexc/MexcSpotConnector.java`

### 1.4 Lighter — "Host unknown"

**Причина:** DNS api.lighter.xyz не резолвится. Биржа, вероятно, сменила домен или закрылась.

**Исправление:**
- Отключить LIGHTER в `application.yml` (`enabled: false`)

**Файл:**
- `src/main/resources/application.yml`

---

## Часть 2: Предзагрузка volume15min из REST API (klines)

### Проблема
После запуска `volume15min = 0` для всех монет. Первые 15 минут VOLUME_BASED детекция некорректна — почти любой ордер проходит проверку.

### Решение
При старте каждый коннектор запрашивает kline-данные (1m свечи, 15 штук) через REST API и засеивает VolumeTracker.

### 2.1 VolumeTracker — добавить `seedVolume()`

Метод принимает суммарный объём за 15 минут и распределяет его по минутным бакетам с метками времени в прошлом. Так данные будут постепенно expire, имитируя скользящее окно.

**Файл:** `src/main/java/com/cryptoview/service/volume/VolumeTracker.java`

### 2.2 AbstractWebSocketConnector — добавить `preloadVolumes()` шаблон

Default no-op метод, переопределяемый в каждом коннекторе.

**Файл:** `src/main/java/com/cryptoview/exchange/common/AbstractWebSocketConnector.java`

### 2.3 Реализация в каждом коннекторе

Вызывается в `subscribeAll()` после `fetchAllSymbols()`, перед WebSocket-подпиской.

**REST endpoints для klines (проверены по документации):**

| Биржа | URL | Поле quoteVolume |
|---|---|---|
| Binance Futures | `GET /fapi/v1/klines?symbol={s}&interval=1m&limit=15` | index 7 |
| Binance Spot | `GET /api/v3/klines?symbol={s}&interval=1m&limit=15` | index 7 |
| Bybit | `GET /v5/market/kline?category={cat}&symbol={s}&interval=1&limit=15` | "turnover" |
| OKX | `GET /api/v5/market/candles?instId={id}&bar=1m&limit=15` | index 7 (volCcyQuote) |
| Bitget Spot | `GET /api/v2/spot/market/candles?symbol={s}&granularity=1min&limit=15` | index 6 |
| Bitget Futures | `GET /api/v2/mix/market/candles?productType=USDT-FUTURES&symbol={s}&granularity=1m&limit=15` | index 6 |
| Gate Spot | `GET /api/v4/spot/candlesticks?currency_pair={p}&interval=1m&limit=15` | index 1 |
| Gate Futures | `GET /api/v4/futures/usdt/candlesticks?contract={c}&interval=1m&limit=15` | "sum" |
| MEXC | `GET /api/v3/klines?symbol={s}&interval=1m&limit=15` | index 7 |
| Hyperliquid | `POST /info` с `{"type":"candleSnapshot",...}` | approx: avg(o,c) * v |

**Защита от rate-limit:**
- Батчи по 5 символов
- Пауза 500ms между батчами
- Ошибки per-symbol не прерывают весь процесс
- Лог прогресса: `"Pre-loaded volume for X/Y symbols"`

**Файлы:** все 12 коннекторов (кроме Lighter)

---

## Часть 3: Улучшение логирования

### 3.1 Исправить потерю stack trace

Во всех `log.error("...", e.getMessage())` заменить на `log.error("...", e)`.

**Места (13+ штук):**
- `AbstractWebSocketConnector`: onMessage error, onFailure, parseJson
- `ExchangeManager`: start connector, disconnect
- Все коннекторы: `fetchAllSymbols()` catch блоки

### 3.2 Улучшить parseJson()

При ошибке парсинга логировать первые 200 символов сообщения.

### 3.3 Добавить логирование в тихие сервисы

- **AnomalyDetector**: DEBUG при анализе ордербука, INFO при обнаружении плотности
- **VolumeTracker**: DEBUG при cleanup, INFO при seedVolume
- **OrderBookManager**: INFO при первом получении ордербука для символа

### 3.4 Startup summary

В `ExchangeManager.init()` после запуска всех коннекторов — сводка конфигурации:
```
=== CryptoView Startup Summary ===
Global: minDensityUsd=100000, cooldown=5min
BINANCE FUTURES - ENABLED
BINANCE SPOT - ENABLED
...
Symbols with custom config: 36
===================================
```

### 3.5 Улучшить периодический статус-лог

Добавить в 60-секундный `logStatus()`: количество отслеживаемых символов в VolumeTracker.

### 3.6 Уровни логирования в application.yml

Добавить гранулярные уровни для отладки:
```yaml
com.cryptoview.service.detector: INFO
com.cryptoview.service.volume: INFO
com.cryptoview.service.orderbook: INFO
```

---

## Порядок реализации

1. **Lighter disable** — config change (1 мин)
2. **MEXC fix** — одно изменение фильтра (5 мин)
3. **Binance fix** — разделение подписок + уменьшение батча (20 мин)
4. **Gate retry** — добавить `executeWithRetry` + применить (15 мин)
5. **Logging fixes** — stack traces, parseJson, тихие сервисы (30 мин)
6. **VolumeTracker.seedVolume()** (10 мин)
7. **preloadVolumes() в каждом коннекторе** (60 мин)
8. **Сборка + тест** (10 мин)

## Проверка

1. `./gradlew build -x test` — сборка без ошибок
2. `./gradlew bootRun` — запуск, проверить логи:
   - Все биржи подключились (кроме Lighter)
   - MEXC нашёл > 0 USDT пар
   - Binance не падает с "Payload too long"
   - Gate не таймаутит (или ретраит успешно)
   - Volume pre-loaded для символов
   - Startup summary видна в логах
   - Нет спама алертов первые минуты (volume уже засеян)
