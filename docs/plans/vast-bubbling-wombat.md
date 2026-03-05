# План: Исправление Binance Spot/Futures коннекторов

## Контекст

Текущие коннекторы Binance имеют ряд критических проблем, обнаруженных при анализе кода и логов:

1. **Futures: бесконечный цикл gap → refetch → gap** — в логах видно десятки подряд gap-ошибок для одних и тех же символов (TRUTHUSDT, CGPTUSDT, SUSHIUSDT и др.). Причина: при gap detection запускается virtual thread для refetch через 1 сек, но за эту секунду приходят новые WS-события, которые тоже обнаруживают gap и запускают ещё один refetch. В итоге несколько параллельных refetch на один символ.

2. **Futures: WebSocket failure (Connection reset)** — через ~20 сек после старта WS обрывается с `java.net.SocketException: Connection reset`. Вероятно связано с нагрузкой от массовых gap re-init.

3. **Spot: gap detection алгоритм неполный** — проверяет `U > lastUpdateId + 1`, но по документации Binance первый валидный event после snapshot должен иметь `U <= lastUpdateId+1 AND u >= lastUpdateId+1`. Текущий код это не проверяет полностью.

4. **Ping/pong: не используется** — `getPingMessage()` возвращает null для обоих коннекторов, но Binance сервер шлёт ping каждые 20 сек (Spot) / 3 мин (Futures) и ожидает pong в ответ. OkHttp отвечает на ping автоматически на уровне фреймов, но текущий код отправляет свой application-level ping из AbstractWebSocketConnector, что создаёт лишнюю нагрузку.

5. **Нет 24-часового reconnect** — Binance обрывает WS-соединения через 24 часа. Текущий код не планирует превентивный reconnect.

6. **publishOrderBook вызывается на КАЖДЫЙ depth update** — при ~100мс интервале и 500+ символов это огромная нагрузка. Каждый вызов: snapshot LocalOrderBook → фильтрация → создание OrderBook → Spring Event → AnomalyDetector + DensityTracker.

7. **Snapshot глубина 1000** — для Spot это стоит 100 weight (лимит 6000/мин), можно использовать limit=5000 (вес 250) для более полного стакана при той же скорости (1 req/sec).

## Что будет сделано

### 1. Исправить gap detection и snapshot initialization

**BinanceSpotConnector:**
- Исправить алгоритм буфер-фильтрации: первый event после snapshot должен удовлетворять `U <= lastUpdateId+1 AND u >= lastUpdateId+1`
- При gap detection НЕ запускать отдельный virtual thread, а ставить символ в очередь для последовательного refetch

**BinanceFuturesConnector:**
- При gap detection добавить защиту от повторного refetch (if already initializing — just buffer)
- Использовать `pu` chain правильно: каждый event.pu должен == previous event.u

**Общее для обоих:**
- Добавить `ConcurrentHashMap<String, AtomicBoolean> refetchInProgress` для защиты от параллельных refetch
- При gap detection: buffer events + поставить в очередь, а не запускать thread

### 2. Убрать application-level ping, довериться OkHttp

OkHttp автоматически отвечает на WebSocket ping frames (RFC 6455). Binance шлёт именно ping frames, не application messages. Поэтому:
- Оставить `getPingMessage()` = null для Binance (уже так)
- НО убрать stale detection на основе 90 сек — заменить на более мягкий порог (5 мин), т.к. OkHttp pong работает автоматически
- Добавить 24-часовой таймер: за 5 мин до истечения 24ч — инициировать graceful reconnect

### 3. Добавить 24-часовой reconnect

- Сохранять `connectionStartTime` при onOpen()
- В ping task проверять: если прошло > 23ч 55мин — закрыть WS и переподключиться
- При reconnect: НЕ сбрасывать localBooks, а после подключения refetch snapshot для всех символов

### 4. Оптимизировать частоту publishOrderBook

Сейчас publishOrderBook вызывается на каждый depth event (~10 раз/сек на символ). Это создаёт:
- ~5000 OrderBook объектов/сек (500 символов × 10)
- ~5000 Spring Events/сек
- ~5000 вызовов AnomalyDetector + DensityTracker

**Решение: throttle publishOrderBook до 1 раз в 2 секунды на символ.**
- Добавить `Map<String, Instant> lastPublishTime` в каждый коннектор
- Применять delta к LocalOrderBook всегда (чтобы стакан был актуальным)
- Но publishOrderBook только если прошло >= 2 сек с последней публикации для этого символа
- Первая публикация (после snapshot) — без throttle

### 5. Увеличить глубину snapshot

- Spot: limit=5000 (вес 250, можно ~24 req/min при лимите 6000/min → ставим 1 req/2sec = 30 req/min, безопасно)
- Futures: limit=1000 (вес 20, оставить — 1000 уровней достаточно для 10% distance)

Собственно, при limit=5000 для Spot мы получим максимальную глубину Binance. Это обеспечит полноту стакана в пределах 10%.

### 6. Улучшить логирование

- Логировать прогресс snapshot fetch: каждые 50 символов, а не только в конце
- При gap detection: логировать сколько раз gap был для символа (счётчик)
- При reconnect: логировать причину (24h timeout, stale data, connection reset)
- Добавить сводную статистику в ExchangeManager logStatus: количество initialized книг, pending snapshots, gap count
- Убрать избыточные "First orderbook received" логи после первых 50 — дальше логировать только каждый 100-й

### 7. Отключить Telegram алерты

Telegram уже отключён по дефолту (`enabled: ${TELEGRAM_ENABLED:false}`). Убедиться, что при запуске без env-переменной он не отправляет.

## Файлы для изменения

| Файл | Что меняется |
|------|-------------|
| `src/main/java/com/cryptoview/exchange/binance/BinanceSpotConnector.java` | Gap detection, snapshot depth, throttle publish, refetch queue, 24h reconnect, логи |
| `src/main/java/com/cryptoview/exchange/binance/BinanceFuturesConnector.java` | Gap detection, throttle publish, refetch queue, 24h reconnect, логи |
| `src/main/java/com/cryptoview/exchange/common/AbstractWebSocketConnector.java` | 24h reconnect timer, stale threshold, connectionStartTime, логи |
| `src/main/java/com/cryptoview/exchange/common/LocalOrderBook.java` | Без изменений — работает корректно |
| `src/main/java/com/cryptoview/service/orderbook/OrderBookManager.java` | Без изменений |
| `CLAUDE.md` | Обновить документацию по коннекторам |

## Детальный алгоритм исправленного коннектора

### Binance Spot — новый алгоритм

```
subscribeAll():
  1. fetchAllSymbols() — получить все USDT пары
  2. Ограничить до 512 символов
  3. connectAndWait()
  4. Подписаться батчами по 20 (500мс пауза)
  5. Запустить fetchSnapshotsForAll() в virtual thread

fetchSnapshotsForAll(symbols):
  Для каждого символа последовательно:
    1. fetchAndApplySnapshot(symbol)
    2. Thread.sleep(2000) — для limit=5000 (вес 250): 30 req/min, безопасно
    3. Логировать каждые 50 символов

fetchAndApplySnapshot(symbol):
  1. Поставить initializing[symbol] = true
  2. Создать event buffer
  3. GET /api/v3/depth?symbol=X&limit=5000
  4. Получить lastUpdateId
  5. applySnapshot() в LocalOrderBook
  6. Обработать буферизованные events:
     - Пропустить events с u <= lastUpdateId
     - Первый оставшийся event: проверить U <= lastUpdateId+1 И u >= lastUpdateId+1
       - Если нет → GAP, retry (снова REST запрос, БЕЗ нового virtual thread)
     - Применить оставшиеся events
  7. publishOrderBook()
  8. initializing.remove(symbol)

handleDepthUpdate(data, symbol):
  1. Если initializing[symbol] → buffer и return
  2. Если book не инициализирован → buffer и return
  3. Gap check: U > book.lastUpdateId + 1
     - Если gap: book.reset(), initializing[symbol]=true, buffer event
     - НЕ запускать virtual thread — добавить symbol в refetchQueue
  4. Skip if u <= book.lastUpdateId
  5. applyDiffEvent()
  6. Throttle: publishOrderBook только если прошло >= 2 сек

refetchWorker (фоновый thread, проверяет очередь каждые 2 сек):
  Если refetchQueue не пустой:
    symbol = refetchQueue.poll()
    fetchAndApplySnapshot(symbol)
    Thread.sleep(2000)
```

### Binance Futures — аналогичный, но с pu-chain

```
handleDepthUpdate(data, symbol):
  1. Если initializing[symbol] → buffer и return
  2. Если book не инициализирован → buffer и return
  3. Gap check: pu != book.lastUpdateId
     - Если gap: book.reset(), initializing[symbol]=true, buffer event
     - Добавить symbol в refetchQueue (НЕ virtual thread)
  4. Skip if u <= book.lastUpdateId
  5. applyDiffEvent()
  6. Throttle: publishOrderBook только если прошло >= 2 сек
```

### 24-часовой reconnect (в AbstractWebSocketConnector)

```
onOpen():
  connectionStartTime = Instant.now()

pingTask (каждые 30 сек):
  if (now - connectionStartTime > 23h 55min):
    log "24h limit approaching, reconnecting"
    gracefulReconnect()
  if (now - lastMessageTime > 5 min && subscribedSymbols не пусто):
    log "Stale connection, reconnecting"
    forceReconnect()
```

## Верификация

1. `./gradlew compileJava` — убедиться что компилируется
2. `./gradlew bootRun` — запустить и наблюдать логи:
   - Binance Spot: все 440 символов должны получить snapshot без gap-циклов
   - Binance Futures: все 512 символов без каскадных gap ошибок
   - Не должно быть Connection reset ошибок
   - Через 5 минут: проверить что orderbook updates идут стабильно
3. Проверить в логах: throttled publish ~1 раз в 2 сек на символ (а не 10 раз/сек)
4. Telegram алерты не должны отправляться (disabled по дефолту)
