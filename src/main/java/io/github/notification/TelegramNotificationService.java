package io.github.notification;

import io.github.config.TelegramProperties;
import io.github.telegram.TelegramClient;
import it.tdlight.jni.TdApi;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
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
        repost(List.of(message));
    }

    /** Reposts original Telegram messages as one batch, preserving albums and source attribution. */
    public void repost(List<TdApi.Message> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        var sortedMessages = messages.stream()
                .map(message -> Objects.requireNonNull(message, "message must not be null"))
                .sorted(Comparator.comparingLong(message -> message.id))
                .toList();
        long sourceChatId = sortedMessages.getFirst().chatId;
        if (sortedMessages.stream().anyMatch(message -> message.chatId != sourceChatId)) {
            throw new IllegalArgumentException("messages must belong to the same chat");
        }
        long[] messageIds = sortedMessages.stream().mapToLong(message -> message.id).distinct().toArray();
        telegramClient.forwardMessages(notificationChannelId, sourceChatId, messageIds).join();
    }
}
