package io.github.notification;

import io.github.config.TelegramProperties;
import io.github.telegram.TelegramClient;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TelegramNotificationServiceTest {

    private static final long NOTIFICATION_CHANNEL_ID = 1111111111111111111L;
    private static final long SOURCE_CHAT_ID = 2222222222222222222L;
    private static final long MESSAGE_ID = 333L;

    @Mock
    private TelegramClient telegramClient;
    private final TelegramProperties properties = new TelegramProperties("+71111111111", 1, "hash", Path.of("target/test"), NOTIFICATION_CHANNEL_ID);

    @Test
    void forwardsTheOriginalMessageToTheConfiguredChannel() {
        TelegramNotificationService telegramNotificationService =
                new TelegramNotificationService(telegramClient, properties);

        var message = new TdApi.Message();
        message.chatId = SOURCE_CHAT_ID;
        message.id = MESSAGE_ID;
        telegramNotificationService.repost(message);
        verify(telegramClient).forwardMessage(NOTIFICATION_CHANNEL_ID, SOURCE_CHAT_ID, MESSAGE_ID);
    }
}
