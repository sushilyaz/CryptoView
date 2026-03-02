# CryptoView — План работ

## Фаза 1: Backend + Telegram

### Этап 1: Инициализация проекта ✅
- [x] Создание Gradle проекта (Java 21, Spring Boot 4.x)
- [x] Структура пакетов
- [x] Базовые зависимости
- [x] Конфигурационные файлы

### Этап 2: Доменные модели ✅
- [x] Enum: Exchange, MarketType, AlertType, Side
- [x] Model: OrderBookLevel, OrderBook
- [x] Model: Alert, Density, Trade
- [x] Model: ExchangeConfig, SymbolConfig, EffectiveConfig

### Этап 3: Конфигурационная система ✅
- [x] Загрузка application.yml
- [x] Загрузка symbols-config.json
- [x] ConfigService с иерархией настроек
- [x] Экспорт/импорт настроек

### Этап 4: Базовая инфраструктура WebSocket ✅
- [x] Абстрактный WebSocket коннектор
- [x] Обработка реконнектов
- [x] Ping/Pong heartbeat
- [x] Логирование соединений

### Этап 5: Коннекторы бирж ✅
- [x] Binance Spot
- [x] Binance Futures
- [x] Bybit Spot
- [x] Bybit Futures
- [x] OKX Spot
- [x] OKX Futures
- [x] Bitget Spot
- [x] Bitget Futures
- [x] Gate Spot
- [x] Gate Futures
- [x] MEXC Spot (без Futures)
- [x] Hyperliquid (Futures/Perpetual)
- [x] Lighter Spot

### Этап 6: OrderBook Manager ✅
- [x] Хранение стаканов в памяти (ConcurrentHashMap)
- [x] Обновление стаканов (snapshot + delta)
- [x] Фильтрация по дистанции от цены (10%)
- [x] Очистка устаревших данных

### Этап 7: Volume Tracker ✅
- [x] Подписка на trade streams
- [x] Скользящее окно 15 минут
- [x] Агрегация объёмов по символам
- [x] Периодическая очистка старых данных

### Этап 8: Anomaly Detector ✅
- [x] Volume-based детекция
- [x] Statistical детекция (Z-score + IQR)
- [x] Фильтрация по настройкам (minDensityUsd и др.)
- [x] Дедупликация алертов

### Этап 9: Alert Service ✅
- [x] Формирование алертов
- [x] Cooldown механизм
- [x] Event-driven архитектура (Spring Events)

### Этап 10: Telegram Bot ✅
- [x] Интеграция с Telegram Bot API
- [x] Форматирование сообщений (Markdown)
- [x] Отправка алертов
- [x] Обработка ошибок отправки

### Этап 11: Улучшения и исправления ✅
- [x] File-based logging (logback-spring.xml, rolling 50MB/30d)
- [x] Исправление коннекторов (Binance /stream, OKX books5, Bitget books15)
- [x] Отключение Gate (таймауты)
- [x] Telegram rate limiter (очередь 500 msg, ~20 msg/sec, retry 429)
- [x] Улучшенная дедупликация (по символу, max 4 алерта/символ)
- [x] Убран REST seed volume — переход на real-time volume
- [x] VOLUME_BASED активируется per-symbol после 5 мин сбора данных
- [x] STATISTICAL работает сразу после подключения

---

## Фаза 1.5: REST API + WebSocket + Workspaces

### Этап 12: Модели для workspaces и density tracking ✅
- [x] Enum: DensitySortType (DURATION_DESC, SIZE_USD_DESC, DISTANCE_ASC)
- [x] Record: ExchangeMarketKey (exchange + marketType, toStorageKey/fromStorageKey)
- [x] Model: Workspace (enabledMarkets, blacklist, overrides, comments, sortType)
- [x] Record: TrackedDensity (lifetime: firstSeenAt, lastSeenAt, durationSeconds)
- [x] DTO: DensityResponse, WorkspaceRequest

### Этап 13: Density Lifetime Tracking ✅
- [x] DensityTracker — слушает OrderBookUpdateEvent, ведёт ConcurrentHashMap
- [x] Отслеживание появления/исчезновения плотностей (>$50K)
- [x] Cleanup стейлов каждые 10 сек (порог 2 мин)
- [x] DensityFilterService — shared логика фильтрации по workspace

### Этап 14: Workspace Service ✅
- [x] CRUD workspaces
- [x] JSON persistence (workspaces.json)
- [x] Default workspace при первом запуске
- [x] Активация/деактивация workspace
- [x] Blacklist, comments, min-density per symbol

### Этап 15: REST API ✅
- [x] WorkspaceController — CRUD workspaces, activate
- [x] DensityController — GET densities с фильтрацией по workspace
- [x] SymbolController — blacklist, comments, min-density per symbol
- [x] StatusController — connectedExchanges, totalSymbols, trackedDensities
- [x] WebConfig — CORS для localhost:3000

### Этап 16: WebSocket стрим для фронтенда ✅
- [x] WebSocketConfig — регистрация /ws/densities endpoint
- [x] DensityWebSocketHandler — raw WebSocket, 500мс broadcast
- [x] Per-client workspace фильтрация
- [x] Смена workspace на лету (client → server message)

### Этап 17: ConfigService дополнение ✅
- [x] Метод getGlobalMinimumDensityFloor()

### Этап 18: Документация ✅
- [x] Актуализация README.md
- [x] Актуализация TECHNICAL_SPECIFICATION.md
- [x] Актуализация WORK_PLAN.md
- [x] Актуализация CLAUDE.md

---

## Фаза 1.7: Diff-based depth (глубокие стаканы)

Рефакторинг всех коннекторов с snapshot-based (ограниченная глубина) на diff-based incremental (полная/расширенная глубина) с использованием `LocalOrderBook`.

### Этап 19: LocalOrderBook — общий класс для diff-based управления ✅
- [x] Thread-safe TreeMap с ReentrantReadWriteLock
- [x] Поддержка snapshot + delta (qty=0 → удалить уровень)
- [x] Tracking: lastUpdateId, lastSeqId
- [x] Метод getSnapshot(quantityMultiplier) для futures ctVal конвертации

### Этап 20: Binance Spot/Futures — diff-based depth ✅
- [x] Spot: `@depth@100ms` + REST snapshot `/api/v3/depth?limit=5000` (weight 250, max глубина)
- [x] Futures: `@depth@500ms` + REST snapshot `/fapi/v1/depth?limit=1000` (weight 20)
- [x] Event buffering до snapshot, gap detection по U/u (Spot) и pu-chain (Futures)
- [x] Virtual threads для snapshot fetching, rate limiting
- [x] **Исправлено:** правильный gap detection по документации Binance
- [x] **Исправлено:** refetchQueue вместо параллельных virtual threads (fix каскадного gap storm)
- [x] **Добавлено:** publish throttle 2 сек на символ (снижение нагрузки на Spring Events)

### Этап 21: Bybit Spot/Futures — orderbook.200 ✅
- [x] `orderbook.200` канал (200 уровней, snapshot + delta)
- [x] Tracking по u (updateId) и seq

### Этап 22: OKX Spot/Futures — books канал (400 уровней) ✅
- [x] `books` канал вместо `books5` (400 уровней, incremental)
- [x] seqId/prevSeqId gap detection + REST snapshot fallback
- [x] Futures: ctVal multiplier через getSnapshot(ctVal)

### Этап 23: Bitget Spot/Futures — books канал (полная глубина) ✅
- [x] `books` канал вместо `books15` (полная глубина, incremental)
- [x] seq/pseq gap detection + REST snapshot fallback
- [x] Spot: `/api/v2/spot/market/orderbook`, Futures: `/api/v2/mix/market/merge-depth`

### Этап 24: Документация ✅
- [x] Обновление CLAUDE.md, TECHNICAL_SPECIFICATION.md, WORK_PLAN.md, README.md

---

## Фаза 1.8: Исправление и оптимизация коннекторов

### Этап 25: AbstractWebSocketConnector — улучшения ✅
- [x] 24-часовой превентивный reconnect (23ч 55мин) — Binance обрывает WS через 24ч
- [x] Stale data threshold увеличен с 90 сек до 5 мин (OkHttp ping/pong работает автоматически)
- [x] Добавлен хук `onResubscribed()` для полной re-инициализации снапшотов после reconnect
- [x] Сброс `debugMessageCount` при reconnect для логирования первых сообщений нового соединения
- [x] `connectionStartTime` — трекинг времени жизни соединения

### Этап 26: BinanceSpotConnector — полная переработка ✅
- [x] Правильный gap detection по документации Binance: `U <= lastUpdateId+1 AND u >= lastUpdateId+1`
- [x] Snapshot depth увеличен до 5000 (max Binance, weight 250) — полнота стакана в пределах 10%
- [x] `BlockingQueue<String> refetchQueue` + `refetchWorker` — последовательный refetch при gap (вместо параллельных virtual threads)
- [x] Publish throttle: `OrderBookUpdateEvent` публикуется max 1 раз в 2 сек на символ (delta всегда применяется)
- [x] `Set<String> initializing` — защита от параллельных refetch одного символа
- [x] `Map<String, AtomicInteger> gapCounts` — счётчик gap-ов для логирования
- [x] Application-level ping отключён (OkHttp отвечает на Binance ping frames автоматически)
- [x] `onResubscribed()` — полный reset + refetch всех книг при reconnect
- [x] Улучшенный `getStatusSummary()`: books, gaps, refetchQ

### Этап 27: BinanceFuturesConnector — полная переработка ✅
- [x] Gap detection по `pu`-chain: `event.pu == book.lastUpdateId`
- [x] Буфер после snapshot: первый event `U <= lastUpdateId AND u >= lastUpdateId`, затем pu-chain валидация
- [x] `refetchQueue` + `refetchWorker` — аналогично Spot
- [x] Publish throttle 2 сек на символ
- [x] Application-level ping отключён
- [x] Snapshot depth 1000 (weight 20), fetch delay 1 сек
- [x] `onResubscribed()` — полный reset + refetch

### Этап 28: Bybit Spot/Futures — ревизия
- [ ] Проверить gap detection и recovery
- [ ] Проверить publish throttle (если нужен)
- [ ] Проверить reconnect и re-инициализацию

### Этап 29: OKX Spot/Futures — ревизия
- [ ] Проверить seqId/prevSeqId gap detection
- [ ] Проверить ctVal multiplier для Futures
- [ ] Проверить reconnect и re-инициализацию

### Этап 30: Bitget Spot/Futures — ревизия
- [ ] Проверить seq/pseq gap detection
- [ ] Проверить REST snapshot fallback
- [ ] Проверить reconnect и re-инициализацию

### Этап 31: MEXC Spot — ревизия
- [ ] Проверить protobuf parsing
- [ ] Проверить connection pool

### Этап 32: Hyperliquid — ревизия
- [ ] Проверить l2Book обработку
- [ ] Проверить USDC пары

### Этап 33: Документация ✅
- [x] Обновление CLAUDE.md (алгоритмы Binance, история багов)
- [x] Обновление TECHNICAL_SPECIFICATION.md (подключение, Binance алгоритмы, константы, фазы)
- [x] Обновление WORK_PLAN.md (фаза 1.8, статус этапов)

---

## Фаза 2: Frontend

### Этап 1: Инициализация ✅
- [x] Vite 7 + React 19 + TypeScript
- [x] Tailwind CSS v4 (тёмная тема)
- [x] Zustand (state management)
- [x] Vite proxy: `/api` и `/ws` → `localhost:8080`

### Этап 2: Компоненты ✅ (каркас, не тестировался с бэкендом)
- [x] Header (WS-индикатор, workspace name, кнопки)
- [x] DensityGrid (responsive сетка карточек)
- [x] SymbolCard (карточка монеты: звёздочка, ЧС, настройки)
- [x] DensityRow (строка плотности: биржа, объём, цена, расстояние, NEW)
- [x] SettingsModal (настройки workspace: биржи, сортировка, мин. размер)
- [x] BlacklistPanel (управление ЧС)
- [x] SymbolSettingsPopup (настройки конкретной монеты)

### Этап 3: Интеграция ✅ (код написан, не тестировался)
- [x] WebSocket клиент с авторекконектом (wsClient singleton)
- [x] REST API клиент (httpClient: workspaceApi, symbolApi, densityApi, statusApi)
- [x] Zustand stores: workspaceStore, densityStore, uiStore
- [x] `npm run build` компилируется без ошибок

### Этап 4: Отладка с бэкендом
- [ ] **Первый запуск и тестирование с работающим бэкендом**
- [ ] Маппинг enabledMarkets (фронт: объекты → бэкенд: строки)
- [ ] WebSocket proxy через Vite
- [ ] Обработка 204 No Content ответов
- [ ] Иконки бирж (SVG)
- [ ] Анимации, полировка UI

### Этап 5: Дополнительно
- [ ] Управление несколькими workspaces из UI
- [ ] Тестирование (integration, e2e)
- [ ] Деплой

---

## Оценка сложности по этапам

| Этап | Сложность | Статус |
|------|-----------|--------|
| 1. Инициализация | Низкая | ✅ |
| 2. Модели | Низкая | ✅ |
| 3. Конфигурация | Средняя | ✅ |
| 4. WebSocket база | Средняя | ✅ |
| 5. Коннекторы | Высокая | ✅ |
| 6. OrderBook | Средняя | ✅ |
| 7. Volume Tracker | Средняя | ✅ |
| 8. Детектор | Средняя | ✅ |
| 9. Alert Service | Низкая | ✅ |
| 10. Telegram | Низкая | ✅ |
| 11. Улучшения | Средняя | ✅ |
| 12. Модели workspace | Низкая | ✅ |
| 13. Density Tracking | Средняя | ✅ |
| 14. Workspace Service | Средняя | ✅ |
| 15. REST API | Средняя | ✅ |
| 16. WebSocket стрим | Средняя | ✅ |
| 17. ConfigService | Низкая | ✅ |
| 18. Документация | Низкая | ✅ |
| 19. LocalOrderBook | Средняя | ✅ |
| 20. Binance diff-based | Высокая | ✅ |
| 21. Bybit orderbook.200 | Средняя | ✅ |
| 22. OKX books (400) | Средняя | ✅ |
| 23. Bitget books (full) | Средняя | ✅ |
| 24. Документация | Низкая | ✅ |
| 25. AbstractWSConnector улучшения | Средняя | ✅ |
| 26. BinanceSpot переработка | Высокая | ✅ |
| 27. BinanceFutures переработка | Высокая | ✅ |
| 28. Bybit ревизия | Средняя | ⬜ |
| 29. OKX ревизия | Средняя | ⬜ |
| 30. Bitget ревизия | Средняя | ⬜ |
| 31. MEXC ревизия | Низкая | ⬜ |
| 32. Hyperliquid ревизия | Низкая | ⬜ |
| 33. Документация | Низкая | ✅ |

---

## Зависимости между этапами

```
[1. Инициализация] ✅
        │
        ▼
[2. Модели] ✅ ────────────────┐
        │                      │
        ▼                      ▼
[3. Конфигурация] ✅   [4. WebSocket база] ✅
        │                      │
        │                      ▼
        │             [5. Коннекторы] ✅
        │                      │
        ▼                      ▼
[8. Детектор] ✅ ◄──── [6. OrderBook] ✅ ◄──── [7. Volume] ✅
        │
        ▼
[9. Alert Service] ✅
        │
        ▼
[10. Telegram] ✅
        │
        ▼
[11. Улучшения] ✅
        │
        ▼
[12. Модели workspace] ✅
        │
        ├──────────────────┐
        ▼                  ▼
[13. Density Tracking] ✅  [14. Workspace Service] ✅
        │                  │
        ├──────────────────┘
        ▼
[15. REST API] ✅
        │
        ▼
[16. WebSocket стрим] ✅
        │
        ▼
[19. LocalOrderBook] ✅
        │
        ├──────────────────────────┐
        ▼                          ▼
[20. Binance diff-based] ✅  [21. Bybit orderbook.200] ✅
        │                          │
        ├──────────────────────────┘
        ▼
[22. OKX books (400)] ✅ ──── [23. Bitget books (full)] ✅
                                        │
                                        ▼
                             [25. AbstractWSConnector] ✅
                                        │
                              ┌─────────┴─────────┐
                              ▼                    ▼
                    [26. BinanceSpot] ✅  [27. BinanceFutures] ✅
                              │                    │
                              └─────────┬──────────┘
                                        ▼
                    [28-32. Ревизия остальных бирж] ⬜
```

---

## Как запустить

1. Скопируйте `.env.example` в `.env` и заполните переменные
2. Запустите: `./gradlew bootRun`
3. Приложение подключится ко всем биржам и начнёт мониторинг
4. REST API: `http://localhost:8080/api/v1/status`
5. WebSocket: `ws://localhost:8080/ws/densities`
