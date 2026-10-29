package io.github.collector;

import io.github.chat.TrackedChat;
import io.github.chat.TrackedChatService;
import io.github.config.DatabaseProperties;
import io.github.filter.FilterRule;
import io.github.filter.FilterRuleService;
import io.github.telegram.TelegramClient;
import io.github.notification.TelegramNotificationService;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
@Import({TrackedChatService.class, FilterRuleService.class})
@ExtendWith(OutputCaptureExtension.class)
class TelegramUpdateProcessorIntegrationTest {
    private static final long TRACKED_CHAT_ID = -100123L;
    private static final long UNTRACKED_CHAT_ID = -100456L;

    @MockitoBean
    private TelegramClient telegramClient;

    @MockitoBean
    private TelegramNotificationService notificationService;

    @Autowired
    private TelegramUpdateProcessor processor;

    @Autowired
    private TrackedChatService trackedChatService;

    @Autowired
    private FilterRuleService filterRuleService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DatabaseProperties databaseProperties;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("truncate table " + databaseProperties.schema() + ".filter_rule restart identity").update();
        jdbcClient.sql("truncate table " + databaseProperties.schema() + "." + databaseProperties.tableName()).update();
        trackedChatService.trackChat(TRACKED_CHAT_ID);
        trackedChatService.initializeCursor(TRACKED_CHAT_ID, 100L);
        filterRuleService.addRule("java;spring;!junior");

        when(telegramClient.getChatHistory(TRACKED_CHAT_ID, 0L, 0, 100, false))
                .thenReturn(CompletableFuture.completedFuture(messages(
                        message(TRACKED_CHAT_ID, 103L, "Spring for junior developers"),
                        message(TRACKED_CHAT_ID, 102L, "Java role"))));
        when(telegramClient.getChatHistory(TRACKED_CHAT_ID, 102L, 0, 100, false))
                .thenReturn(CompletableFuture.completedFuture(messages(
                        message(TRACKED_CHAT_ID, 102L, "Java role"),
                        message(TRACKED_CHAT_ID, 101L, "Java and Spring role"),
                        message(TRACKED_CHAT_ID, 100L, "Old Java and Spring role"))));
    }

    @AfterEach
    void tearDown() {
        processor.stop();
    }

    @Test
    void recoversTrackedChatUsingRulesAndPersistsTheCursor(CapturedOutput output) {
        assertThat(trackedChatService.listChats())
                .containsExactly(new TrackedChat(TRACKED_CHAT_ID, 100L));
        assertThat(filterRuleService.listRules())
                .extracting(FilterRule::rule)
                .containsExactly("java;spring;!junior");

        processor.onConnectionStateChanged(
                new TdApi.UpdateConnectionState(new TdApi.ConnectionStateReady()));

        awaitCursor(TRACKED_CHAT_ID, 103L);

        assertThat(output)
                .contains("messageId=101")
                .doesNotContain("messageId=102")
                .doesNotContain("messageId=103");
        assertThat(trackedChatService.listChats())
                .containsExactly(new TrackedChat(TRACKED_CHAT_ID, 103L));
        assertThat(filterRuleService.listRules())
                .extracting(FilterRule::rule)
                .containsExactly("java;spring;!junior");
        assertThat(filterRuleService.matches("Java and Spring role")).isTrue();
        assertThat(filterRuleService.matches("Spring for junior developers")).isFalse();
        assertThat(trackedChatService.findChat(UNTRACKED_CHAT_ID)).isEmpty();
    }

    private void awaitCursor(long chatId, long expectedMessageId) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (trackedChatService.findChat(chatId)
                    .map(TrackedChat::lastMessageId)
                    .filter(id -> id == expectedMessageId)
                    .isPresent()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Telegram history recovery", exception);
            }
        }
        assertThat(trackedChatService.findChat(chatId))
                .contains(new TrackedChat(chatId, expectedMessageId));
    }

    private static TdApi.Message message(long chatId, long messageId, String text) {
        var message = new TdApi.Message();
        message.chatId = chatId;
        message.id = messageId;
        message.content = new TdApi.MessageText(
                new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, null);
        return message;
    }

    private static TdApi.Messages messages(TdApi.Message... messages) {
        return new TdApi.Messages(messages.length, messages);
    }
}
