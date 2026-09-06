package io.github.collector;

import io.github.chat.TrackedChat;
import io.github.chat.TrackedChatService;
import io.github.filter.FilterRuleService;
import io.github.notification.TelegramNotificationService;
import io.github.telegram.TelegramClient;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramUpdateProcessorTest {

    private static final long CHAT_ID = -100123L;

    private TelegramClient telegramClient;
    private TrackedChatService trackedChatService;
    private FilterRuleService filterRuleService;
    private TelegramNotificationService notificationService;
    private TelegramUpdateProcessor processor;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        trackedChatService = mock(TrackedChatService.class);
        filterRuleService = mock(FilterRuleService.class);
        notificationService = mock(TelegramNotificationService.class);
        processor = new TelegramUpdateProcessor(telegramClient, trackedChatService, filterRuleService, notificationService);
    }

    @AfterEach
    void tearDown() {
        processor.stop();
    }

    @Test
    void startsOnceAndStopsTheTelegramClient() throws Exception {
        processor.start();
        processor.start();

        assertThat(processor.isRunning()).isTrue();
        assertThat(processor.getPhase()).isEqualTo(Integer.MAX_VALUE);
        verify(telegramClient).start(processor);

        processor.stop();

        assertThat(processor.isRunning()).isFalse();
        verify(telegramClient).close();
    }

    @Test
    void resetsRunningStateWhenTelegramClientCannotStart() {
        var failure = new IllegalStateException("start failed");
        org.mockito.Mockito.doThrow(failure).when(telegramClient).start(processor);

        assertThatThrownBy(processor::start).isSameAs(failure);
        assertThat(processor.isRunning()).isFalse();
    }

    @Test
    void listenerAdvancesCursorForANewTrackedMessage() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        TelegramUpdateListener listener = processor;
        processor.start();

        listener.onNewMessage(new TdApi.UpdateNewMessage(message(CHAT_ID, 101L, false)));

        verify(trackedChatService, timeout(1_000)).advanceCursor(CHAT_ID, 101L);
    }

    @Test
    void passesIncomingTextThroughTheFilterAndAdvancesCursorWhenItDoesNotMatch() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        when(filterRuleService.matches("Java developer role")).thenReturn(false);
        var message = message(CHAT_ID, 101L, false);
        message.content = new TdApi.MessageText(
                new TdApi.FormattedText("Java developer role", new TdApi.TextEntity[0]), null, null);
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(message));

        verify(filterRuleService, timeout(1_000)).matches("Java developer role");
        verify(trackedChatService).advanceCursor(CHAT_ID, 101L);
    }

    @Test
    void doesNotFilterOutgoingMessages() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        var message = message(CHAT_ID, 101L, true);
        message.content = new TdApi.MessageText(
                new TdApi.FormattedText("Java developer role", new TdApi.TextEntity[0]), null, null);
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(message));

        verify(trackedChatService, timeout(1_000)).advanceCursor(CHAT_ID, 101L);
        verify(filterRuleService, never()).matches(org.mockito.ArgumentMatchers.any());
        verify(notificationService, never()).repost(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repostsAnIncomingMessageThatMatchesTheFilter() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        var message = message(CHAT_ID, 101L, false);
        message.content = new TdApi.MessageText(
                new TdApi.FormattedText("Java developer role", new TdApi.TextEntity[0]), null, null);
        when(filterRuleService.matches("Java developer role")).thenReturn(true);
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(message));

        verify(notificationService, timeout(1_000)).repost(org.mockito.ArgumentMatchers.any(TdApi.Message.class));
        verify(trackedChatService, timeout(1_000)).advanceCursor(CHAT_ID, 101L);
    }

    @Test
    void newMessageIgnoresNullUntrackedOldAndNegativeMessages() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, null)));
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage((TdApi.Message) null));
        processor.onNewMessage(new TdApi.UpdateNewMessage(message(CHAT_ID, 101L, false)));
        processor.onNewMessage(new TdApi.UpdateNewMessage(message(CHAT_ID, 100L, false)));
        processor.onNewMessage(new TdApi.UpdateNewMessage(message(CHAT_ID, -1L, false)));

        verify(trackedChatService, timeout(1_000).times(3)).findChat(CHAT_ID);
        verify(trackedChatService, never()).advanceCursor(anyLong(), anyLong());
    }

    @Test
    void contentEditAndNonCachedDeletionCallbacksCheckTrackedChat() {
        when(trackedChatService.findChat(CHAT_ID)).thenReturn(Optional.empty());
        processor.start();

        processor.onMessageContentChanged(new TdApi.UpdateMessageContent(CHAT_ID, 1L, null));
        processor.onMessageEdited(new TdApi.UpdateMessageEdited(CHAT_ID, 2L, 123, null));
        processor.onMessagesDeleted(new TdApi.UpdateDeleteMessages(CHAT_ID, new long[]{3L}, true, false));

        verify(trackedChatService, timeout(1_000).times(3)).findChat(CHAT_ID);
    }

    @Test
    void cachedDeletionIsIgnored() {
        processor.start();

        processor.onMessagesDeleted(new TdApi.UpdateDeleteMessages(CHAT_ID, new long[]{1L}, false, true));

        verify(trackedChatService, never()).findChat(anyLong());
    }

    @Test
    void readyConnectionInitializesCursorForAChatWithoutOne() {
        var lastMessage = message(CHAT_ID, 250L, false);
        var chat = new TdApi.Chat();
        chat.lastMessage = lastMessage;
        when(trackedChatService.listChats()).thenReturn(List.of(new TrackedChat(CHAT_ID, null)));
        when(telegramClient.getChat(CHAT_ID)).thenReturn(CompletableFuture.completedFuture(chat));
        processor.start();

        processor.onConnectionStateChanged(
                new TdApi.UpdateConnectionState(new TdApi.ConnectionStateReady()));

        verify(trackedChatService, timeout(1_000)).initializeCursor(CHAT_ID, 250L);
    }

    @Test
    void readyConnectionRecoversHistoryOldestFirstAndOnlyOnce() {
        when(trackedChatService.listChats()).thenReturn(List.of(new TrackedChat(CHAT_ID, 100L)));
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        when(telegramClient.getChatHistory(CHAT_ID, 0L, 0, 100, false))
                .thenReturn(CompletableFuture.completedFuture(messages(103L, 102L)));
        when(telegramClient.getChatHistory(CHAT_ID, 102L, 0, 100, false))
                .thenReturn(CompletableFuture.completedFuture(messages(102L, 101L, 100L)));
        processor.start();

        var ready = new TdApi.UpdateConnectionState(new TdApi.ConnectionStateReady());
        processor.onConnectionStateChanged(ready);
        processor.onConnectionStateChanged(ready);

        InOrder cursorUpdates = inOrder(trackedChatService);
        cursorUpdates.verify(trackedChatService, timeout(1_000)).advanceCursor(CHAT_ID, 101L);
        cursorUpdates.verify(trackedChatService).advanceCursor(CHAT_ID, 102L);
        cursorUpdates.verify(trackedChatService).advanceCursor(CHAT_ID, 103L);
        verify(trackedChatService).listChats();
        verify(telegramClient).getChatHistory(CHAT_ID, 0L, 0, 100, false);
        verify(telegramClient).getChatHistory(CHAT_ID, 102L, 0, 100, false);
    }

    @Test
    void leavingReadyStateAllowsRecoveryAfterTheNextReadyEvent() {
        when(trackedChatService.listChats()).thenReturn(List.of());
        processor.start();

        processor.onConnectionStateChanged(
                new TdApi.UpdateConnectionState(new TdApi.ConnectionStateReady()));
        verify(trackedChatService, timeout(1_000)).listChats();
        clearInvocations(trackedChatService);

        processor.onConnectionStateChanged(
                new TdApi.UpdateConnectionState(new TdApi.ConnectionStateConnecting()));
        processor.onConnectionStateChanged(
                new TdApi.UpdateConnectionState(new TdApi.ConnectionStateReady()));

        verify(trackedChatService, timeout(1_000)).listChats();
    }

    private static TdApi.Message message(long chatId, long messageId, boolean outgoing) {
        var message = new TdApi.Message();
        message.chatId = chatId;
        message.id = messageId;
        message.isOutgoing = outgoing;
        return message;
    }

    private static TdApi.Messages messages(long... messageIds) {
        var messages = new TdApi.Message[messageIds.length];
        for (int index = 0; index < messageIds.length; index++) {
            messages[index] = message(CHAT_ID, messageIds[index], false);
        }
        return new TdApi.Messages(messages.length, messages);
    }
}
