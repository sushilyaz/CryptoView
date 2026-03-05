# План исправления проблем Lighter, Aster, фронтенда и реконнекта

## Контекст

После добавления Lighter и Aster коннекторов и тестового запуска обнаружены проблемы:
- Lighter Spot/Futures не подключаются (HTTP 400, timeout)
- Aster Spot — 1388 gaps за сессию
- Binance Futures — не переподключается после обрыва
- Фронтенд — Lighter и Aster не отображаются в SettingsModal

## Проблемы и исправления

### 1. Lighter: HTTP 400 на WS handshake
**Причина:** Документация Lighter указывает, что для read-only доступа нужен параметр `?readonly=true`.
**Файл:** `src/main/java/com/cryptoview/exchange/lighter/AbstractLighterConnector.java:24`
**Исправление:** Изменить URL на `wss://mainnet.zklighter.elliot.ai/stream?readonly=true`

### 2. Lighter Spot: рынков не существует
**Причина:** REST API `/orderBookDetails` возвращает ТОЛЬКО `order_book_details` с `market_type: "perp"`. Spot рынков на Lighter нет.
**Файлы:**
- `src/main/resources/application.yml` — отключить `lighter.spot.enabled: false`
- `frontend/src/utils/constants.ts` — убрать LIGHTER из `EXCHANGES_WITH_SPOT`
- Можно оставить `LighterSpotConnector.java` как есть (не подключится если disabled)

### 3. Lighter Futures: REST timeout при загрузке символов
**Причина:** `fetchAllSymbols()` вызывается ДО `connectAndWait()`. Если REST вызов таймаутит — пустой список символов, WS даже не подключается. Нужно увеличить таймаут или изменить порядок.
**Файл:** `AbstractLighterConnector.java:57-79`
**Исправление:** REST запрос с увеличенным таймаутом (10 сек вместо 5). Если symbols пусты — retry с задержкой.

### 4. Aster Spot: 1388 gaps — НЕПРАВИЛЬНЫЙ АЛГОРИТМ GAP DETECTION
**Причина:** Aster Spot WS depth event содержит поле `pu` (previous update id), как Binance **Futures**. Текущий AsterSpotConnector использует Binance **Spot** алгоритм (проверка `U > lastUpdateId + 1`), но Aster Spot отдаёт `pu` и нуждается в Futures-style gap detection (`pu != lastUpdateId`).
**RAW MSG доказательство:** `{"pu":3992571879, "U":3992571882, "u":3992571890, "b":[...], "a":[...]}`
**Файл:** `AsterSpotConnector.java`
**Исправление:** Переписать `AsterSpotConnector` на использование Futures-style алгоритма:
- Gap detection: `pu != book.getLastUpdateId()` вместо `U > book.getLastUpdateId() + 1`
- Bridging event: `U <= snapshotId AND u >= snapshotId` вместо `U <= snapshotId+1 AND u >= snapshotId+1`
- Добавить `pendingBridging` map (как в AsterFuturesConnector)
По сути, AsterSpotConnector должен стать копией AsterFuturesConnector с другими URL/MarketType.

### 5. Aster Futures: 3 символа exceeded max retries
**Не критично** — 3 из 100+ символов. Это нормальное поведение при нестабильности отдельных инструментов. Можно увеличить MAX_GAP_RETRIES или оставить как есть.

### 6. Binance Futures: не переподключается после обрыва
**Причина:** `scheduleReconnect()` имеет лимит `MAX_RECONNECT_ATTEMPTS = 10`. После 10 попыток коннектор сдаётся навсегда. Также при обрыве во время `fetchSnapshotsForAll` поток snapshot завершается, и при реконнекте `onResubscribed` должен перезапустить, но `initLatch` может не быть reset.
**Файл:** `AbstractWebSocketConnector.java:239-242`
**Исправление:** Добавить periodic reconnect check — если connected=false и reconnect не scheduled, попробовать снова. Также сбросить `reconnectAttempts` после успешного реконнекта (уже делается на строке 109).
**Вариант:** Убрать лимит MAX_RECONNECT_ATTEMPTS или сделать его очень большим, добавить periodic check каждые 5 мин.

### 7. Фронтенд: Lighter и Aster не в SettingsModal
**Причина:** Hardcoded `ALL_EXCHANGES` на строке 22 не содержит LIGHTER и ASTER.
**Файл:** `frontend/src/components/workspace/SettingsModal.tsx:22`
**Исправление:** Заменить на динамический список из constants:
```typescript
const ALL_EXCHANGES: Exchange[] = [...new Set([...EXCHANGES_WITH_SPOT, ...EXCHANGES_WITH_FUTURES])]
```

### 8. Bitget Spot: периодические EOFException (5 раз за 25 мин)
**Не критично** — Bitget закрывает соединение, AbstractWebSocketConnector автоматически переподключается. Это нормальное поведение для Bitget.

### 9. OKX Futures: DISCONNECTED 25+ мин после EOFException
**Та же проблема что и #6** — достигнут MAX_RECONNECT_ATTEMPTS. Исправление reconnect logic решит и эту проблему.

## Порядок реализации

1. **Lighter WS URL fix** — добавить `?readonly=true` → `AbstractLighterConnector.java`
2. **Lighter Spot disable** — `application.yml`, `constants.ts`
3. **Lighter REST timeout** — увеличить таймаут в `AbstractLighterConnector.java`
4. **Aster Spot gap detection fix** — переписать на Futures-style алгоритм (pu chain + bridging)
5. **Reconnect logic fix** — убрать жёсткий лимит в `AbstractWebSocketConnector.java`, добавить periodic reconnect check
6. **SettingsModal fix** — динамический `ALL_EXCHANGES`

## Файлы для изменения

- `src/main/java/com/cryptoview/exchange/lighter/AbstractLighterConnector.java`
- `src/main/java/com/cryptoview/exchange/common/AbstractWebSocketConnector.java`
- `src/main/java/com/cryptoview/exchange/aster/AsterSpotConnector.java` (возможно)
- `src/main/resources/application.yml`
- `frontend/src/components/workspace/SettingsModal.tsx`
- `frontend/src/utils/constants.ts`

## Верификация

1. `./gradlew compileJava` — должен пройти
2. `cd frontend && npm run build` — должен пройти
3. Запуск `./gradlew bootRun` — проверить в логах:
   - LIGHTER:FUTURES подключается без HTTP 400
   - LIGHTER:SPOT не запускается (disabled)
   - Binance/OKX переподключаются после обрывов (без лимита)
   - ASTER:SPOT — количество gaps снижено
4. Фронтенд: Lighter и Aster видны в SettingsModal
