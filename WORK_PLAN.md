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

## Фаза 2: Frontend (планируется)

### Этап 1: Инициализация
- [ ] Create React App / Vite
- [ ] TypeScript конфигурация
- [ ] UI библиотека

### Этап 2: Компоненты
- [ ] Layout с боковой панелью
- [ ] Таблица плотностей (real-time, сортировка, durationSeconds)
- [ ] Управление workspaces (создание, переключение, настройки)
- [ ] Blacklist, комментарии, пороги через UI

### Этап 3: Интеграция
- [ ] WebSocket подключение к /ws/densities
- [ ] REST API для CRUD workspaces/settings
- [ ] Смена workspace на лету

### Этап 4: Дополнительно
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
```

---

## Как запустить

1. Скопируйте `.env.example` в `.env` и заполните переменные
2. Запустите: `./gradlew bootRun`
3. Приложение подключится ко всем биржам и начнёт мониторинг
4. REST API: `http://localhost:8080/api/v1/status`
5. WebSocket: `ws://localhost:8080/ws/densities`
