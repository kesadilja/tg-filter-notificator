package io.github.notification;

import io.github.config.TelegramProperties;
import io.github.telegram.TelegramClient;
import it.tdlight.jni.TdApi;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class TelegramNotificationService {
    private final TelegramClient telegramClient;
    private final long notificationChannelId;

    public TelegramNotificationService(TelegramClient telegramClient, TelegramProperties properties) {
        this.telegramClient = Objects.requireNonNull(telegramClient, "telegramClient must not be null");
        this.notificationChannelId = Objects.requireNonNull(
                properties.notificationChannelId(), "notificationChannelId must not be null");
    }

    /** Reposts the original Telegram message, preserving its source attribution. */
    public void repost(TdApi.Message message) {
        Objects.requireNonNull(message, "message must not be null");
        telegramClient.forwardMessage(notificationChannelId, message.chatId, message.id).join();
    }
}
