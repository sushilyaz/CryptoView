package com.cryptoview.service.telegram;

import com.cryptoview.config.CryptoViewProperties;
import com.cryptoview.event.AlertEvent;
import com.cryptoview.model.domain.Alert;
import com.cryptoview.model.enums.AlertType;
import com.cryptoview.model.enums.Side;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramService {

    private final CryptoViewProperties properties;
    private final OkHttpClient httpClient;

    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot%s/sendMessage";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("HH:mm:ss")
            .withZone(ZoneId.of("UTC"));
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getNumberInstance(Locale.US);

    private static final int MAX_QUEUE_SIZE = 500;
    private static final long SEND_INTERVAL_MS = 50; // ~20 msg/sec — безопасно для Telegram API
    private static final int MAX_RETRY = 3;

    private final LinkedBlockingQueue<TelegramMessage> messageQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private volatile boolean running = true;
    private Thread senderThread;

    private record TelegramMessage(String chatId, String text) {}

    @PostConstruct
    public void init() {
        if (properties.getTelegram().isEnabled() && properties.getTelegram().getBotToken() != null) {
            log.info("Telegram notifications enabled");
            senderThread = new Thread(this::senderLoop, "telegram-sender");
            senderThread.setDaemon(true);
            senderThread.start();
        } else {
            log.warn("Telegram notifications disabled or not configured");
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (senderThread != null) {
            senderThread.interrupt();
        }
    }

    @EventListener
    @Async
    public void onAlert(AlertEvent event) {
        if (!properties.getTelegram().isEnabled()) {
            return;
        }

        Alert alert = event.getAlert();
        String message = formatAlertMessage(alert);

        for (String chatId : properties.getTelegram().getChatIds()) {
            if (!messageQueue.offer(new TelegramMessage(chatId, message))) {
                log.warn("Telegram message queue full, dropping alert for {}", alert.symbol());
            }
        }
    }

    private void senderLoop() {
        log.info("Telegram sender thread started");
        while (running) {
            try {
                TelegramMessage msg = messageQueue.poll(1, TimeUnit.SECONDS);
                if (msg != null) {
                    sendWithRetry(msg.chatId(), msg.text());
                    Thread.sleep(SEND_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Telegram sender thread stopped, {} messages in queue", messageQueue.size());
    }

    private String formatAlertMessage(Alert alert) {
        StringBuilder sb = new StringBuilder();

        // Эмодзи в зависимости от стороны
        String emoji = alert.side() == Side.BID ? "🟢" : "🔴";

        sb.append(emoji).append(" *АНОМАЛЬНАЯ ПЛОТНОСТЬ*\n\n");

        // Символ и биржа
        sb.append("📊 *").append(formatSymbol(alert.symbol())).append("* | ");
        sb.append(alert.exchange().getDisplayName()).append(" ");
        sb.append(alert.marketType().getDisplayName()).append("\n");

        // Тип алерта
        sb.append("📈 Тип: ").append(formatAlertType(alert.alertType())).append("\n\n");

        // Объём
        sb.append("💰 Объём: *$").append(formatCurrency(alert.volumeUsd())).append("*\n");

        // Цена и сторона
        sb.append("📍 Цена: ").append(formatPrice(alert.price()));
        sb.append(" (").append(alert.side().getDisplayName()).append(")\n");

        // Расстояние от рынка
        String distanceSign = alert.distancePercent().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        sb.append("📏 От рынка: ").append(distanceSign);
        sb.append(alert.distancePercent().setScale(2, RoundingMode.HALF_UP)).append("%\n\n");

        // Объём за 15 минут
        if (alert.volume15min() != null && alert.volume15min().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("📊 Объём 15м: $").append(formatCurrency(alert.volume15min())).append("\n");
        }

        // Время
        sb.append("⏱ Время: ").append(TIME_FORMATTER.format(alert.timestamp())).append(" UTC\n");

        // Комментарий
        if (alert.comment() != null && !alert.comment().isBlank()) {
            sb.append("\n💬 _").append(escapeMarkdown(alert.comment())).append("_");
        }

        return sb.toString();
    }

    private String formatSymbol(String symbol) {
        // BTCUSDT -> BTC/USDT
        if (symbol.endsWith("USDT")) {
            return symbol.replace("USDT", "/USDT");
        } else if (symbol.endsWith("USDC")) {
            return symbol.replace("USDC", "/USDC");
        }
        return symbol;
    }

    private String formatAlertType(AlertType type) {
        return switch (type) {
            case VOLUME_BASED -> "По объёму 15м";
            case STATISTICAL -> "Статистический";
        };
    }

    private String formatCurrency(BigDecimal value) {
        return CURRENCY_FORMAT.format(value.setScale(0, RoundingMode.HALF_UP));
    }

    private String formatPrice(BigDecimal price) {
        if (price.compareTo(new BigDecimal("1")) < 0) {
            return price.toPlainString();
        } else if (price.compareTo(new BigDecimal("100")) < 0) {
            return price.setScale(4, RoundingMode.HALF_UP).toPlainString();
        } else {
            return price.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
    }

    private String escapeMarkdown(String text) {
        return text
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }

    private void sendWithRetry(String chatId, String text) {
        String url = String.format(TELEGRAM_API_URL, properties.getTelegram().getBotToken());

        String json = String.format(
                "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"Markdown\",\"disable_web_page_preview\":true}",
                chatId,
                escapeJson(text)
        );

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .build();

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return;
                }
                if (response.code() == 429) {
                    // Rate limited — read retry_after from response
                    long retryAfterMs = 1000L * attempt;
                    String body = response.body() != null ? response.body().string() : "";
                    try {
                        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                        if (node.has("parameters") && node.get("parameters").has("retry_after")) {
                            retryAfterMs = node.get("parameters").get("retry_after").asLong() * 1000L;
                        }
                    } catch (Exception ignored) {}
                    log.warn("Telegram rate limited (429), retry in {}ms (attempt {}/{})", retryAfterMs, attempt, MAX_RETRY);
                    Thread.sleep(retryAfterMs);
                    continue;
                }
                log.error("Failed to send Telegram message: {} - {}",
                        response.code(),
                        response.body() != null ? response.body().string() : "no body");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                log.error("Error sending Telegram message (attempt {}/{})", attempt, MAX_RETRY, e);
                if (attempt < MAX_RETRY) {
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
