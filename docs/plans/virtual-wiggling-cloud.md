# Fix: Binance Futures массовые ложные GAP после snapshot initialization

## Context

### Проблема
При старте приложения BinanceFuturesConnector генерирует ~200+ ложных GAP для большинства символов. Каждый символ проходит через цикл: snapshot → immediate gap → refetch → snapshot → gap → refetch (до MAX_GAP_RETRIES=3). Binance Spot при этом работает без единого gap.

### Корневая причина
Когда REST snapshot свежее всех буферизованных WS events (все events имеют `u < lastUpdateId`), **bridging event не найден** в буфере. Код всё равно снимает флаг `initializing` и переходит в runtime mode, устанавливая `book.lastUpdateId = snapshotId`.

Первый WS event в runtime имеет `pu` = `u` предыдущего WS event для этого символа, который НЕ равен `snapshotId`. Код на строке 478 видит `pu != book.getLastUpdateId()` → ложный GAP.

**По документации Binance Futures:** первый event после snapshot ОБЯЗАН быть bridging event с `U <= lastUpdateId AND u >= lastUpdateId`. Без bridging event невозможно установить pu chain.

### Почему Spot не затронут
Spot использует gap detection через `U > lastUpdateId + 1`, что проверяет диапазон ID, а не pu chain. Это работает даже когда snapshot свежее буфера.

## Решение

**Не снимать `initializing` flag пока не получен bridging event.** Если bridging event не найден в буфере — оставить символ в режиме буферизации. `handleDepthUpdate` продолжит буферизовать events. Когда придёт bridging event — применить его и все последующие, затем перейти в runtime.

### Изменения в `BinanceFuturesConnector.java`

#### 1. `fetchAndApplySnapshot()` — не выходить из initializing без bridging event

**Текущее поведение:** После drain буфера всегда вызывает `initializing.remove(symbol)`, даже если bridging=false.

**Новое поведение:**
- Если `bridging=false` (bridging event не найден в буфере) — **НЕ снимать** `initializing`, НЕ удалять буфер
- Записать `snapshotId` в отдельную map (`pendingBridging: Map<String, Long>`) чтобы handleDepthUpdate мог использовать её для поиска bridging event в runtime
- Логировать что символ ожидает bridging event

```
После drain буфера:
  if (foundFirst) {
    // Bridging найден — нормальный путь: снять initializing, перейти в runtime
    initializing.remove(symbol);
    // drain tail events...
    eventBuffers.remove(symbol);
    publishOrderBook(symbol, book);
  } else {
    // Bridging НЕ найден — оставить initializing, не убивать буфер
    // handleDepthUpdate продолжит буферизовать events
    // Переместить снапшот в pending state
    pendingBridging.put(symbol, lastUpdateId);
    log.info("... awaiting bridging event from WS");
  }
```

#### 2. `handleDepthUpdate()` — обрабатывать pending bridging events

Добавить проверку перед стандартным runtime path:

```
if (initializing.contains(symbol)) {
    // Буферизация — существующий путь
    buffer.add(data);

    // НОВОЕ: попробовать drain буфера если есть pending snapshot
    Long snapshotId = pendingBridging.get(symbol);
    if (snapshotId != null) {
        tryDrainPendingBuffer(symbol, snapshotId);
    }
    return;
}
```

#### 3. Новый метод `tryDrainPendingBuffer(symbol, snapshotId)`

Логика аналогична текущему drain в `fetchAndApplySnapshot`:
1. Взять буфер, poll events
2. Skip events с `u < snapshotId`
3. Искать bridging event: `U <= snapshotId AND u >= snapshotId`
4. Если найден → применить bridging + все последующие (с pu chain валидацией)
5. Снять `initializing`, удалить из `pendingBridging`
6. Опубликовать orderbook

Если bridging event всё ещё не найден — ничего не делать, продолжить буферизацию.

#### 4. Timeout защита

Добавить в `pendingBridging` проверку по времени (или в cleanup scheduler):
- Если символ ожидает bridging event > 30 секунд → пере-запросить snapshot
- Это защита от edge case когда bridging event потерян/никогда не придёт

### Новые поля класса

```java
private final Map<String, Long> pendingBridging = new ConcurrentHashMap<>();
```

### Файлы для изменения

- `src/main/java/com/cryptoview/exchange/binance/BinanceFuturesConnector.java` — единственный файл

## Верификация

1. `./gradlew compileJava` — компиляция
2. `./gradlew bootRun` — запуск, наблюдение за логами:
   - Должно быть значительно меньше (или 0) Gap warnings
   - Символы с `bridging=false` должны показывать "awaiting bridging event" и затем успешную инициализацию
   - Все 512 символов должны быть initialized
3. Сравнить с текущим поведением (~200+ gap-ов)
