package io.github.processor;

import io.github.chat.TrackedChat;
import io.github.chat.TrackedChatService;
import io.github.filter.FilterRuleService;
import io.github.notification.TelegramNotificationService;
import io.github.telegram.TelegramClient;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
        verify(notificationService, never()).repost(
                org.mockito.ArgumentMatchers.any(TdApi.Message.class));
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

    @ParameterizedTest(name = "{0} caption")
    @MethodSource("captionContents")
    void passesEveryMediaCaptionThroughTheFilterAndRepostsOnMatch(
            String ignoredName, Supplier<TdApi.MessageContent> contentSupplier) {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        when(filterRuleService.matches("Java developer role")).thenReturn(true);
        var message = message(CHAT_ID, 101L, false);
        message.content = contentSupplier.get();
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(message));

        verify(filterRuleService, timeout(1_000)).matches("Java developer role");
        verify(notificationService, timeout(1_000)).repost(message);
        verify(trackedChatService, timeout(1_000)).advanceCursor(CHAT_ID, 101L);
    }

    @Test
    void doesNotRepostBlankCaptionsButAdvancesTheCursor() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        var photo = new TdApi.MessagePhoto();
        photo.caption = formattedText("   ");
        var message = message(CHAT_ID, 101L, false);
        message.content = photo;
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(message));

        verify(filterRuleService, timeout(1_000)).matches("   ");
        verify(notificationService, never()).repost(org.mockito.ArgumentMatchers.any(TdApi.Message.class));
        verify(trackedChatService, timeout(1_000)).advanceCursor(CHAT_ID, 101L);
    }

    @Test
    void repostsEveryPhotoInAnAlbumWhenAnyCaptionMatches() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        when(filterRuleService.matches("Java developer role")).thenReturn(true);
        var firstPhoto = albumPhoto(101L, 42L, "Java developer role");
        var secondPhoto = albumPhoto(102L, 42L, null);
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(secondPhoto));
        processor.onNewMessage(new TdApi.UpdateNewMessage(firstPhoto));
        processor.onNewMessage(new TdApi.UpdateNewMessage(firstPhoto));

        verify(notificationService, timeout(2_000)).repost(
                org.mockito.ArgumentMatchers.<List<TdApi.Message>>argThat(
                        messages -> messages.equals(List.of(firstPhoto, secondPhoto))));
        verify(trackedChatService, timeout(2_000)).advanceCursor(CHAT_ID, 102L);
    }

    @Test
    void doesNotRepostAnUnmatchedPhotoAlbumButAdvancesItsCursor() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        var firstPhoto = albumPhoto(101L, 42L, "Unrelated caption");
        var secondPhoto = albumPhoto(102L, 42L, null);
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(firstPhoto));
        processor.onNewMessage(new TdApi.UpdateNewMessage(secondPhoto));

        verify(trackedChatService, timeout(2_000)).advanceCursor(CHAT_ID, 102L);
        verify(notificationService, never()).repost(
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void keepsDifferentPhotoAlbumsSeparate() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        when(filterRuleService.matches("First match")).thenReturn(true);
        when(filterRuleService.matches("Second match")).thenReturn(true);
        var firstAlbumPhoto = albumPhoto(101L, 42L, "First match");
        var secondAlbumPhoto = albumPhoto(102L, 43L, "Second match");
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(firstAlbumPhoto));
        processor.onNewMessage(new TdApi.UpdateNewMessage(secondAlbumPhoto));

        verify(notificationService, timeout(500)).repost(
                org.mockito.ArgumentMatchers.<List<TdApi.Message>>argThat(
                        messages -> messages.size() == 1 && messages.getFirst() == firstAlbumPhoto));
        verify(trackedChatService, timeout(2_000)).advanceCursor(CHAT_ID, 102L);
        verify(notificationService).repost(
                org.mockito.ArgumentMatchers.<List<TdApi.Message>>argThat(
                        messages -> messages.size() == 1 && messages.getFirst() == secondAlbumPhoto));
    }

    @Test
    void doesNotAdvanceTheAlbumCursorWhenForwardingFails() {
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        when(filterRuleService.matches("Java developer role")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("forward failed"))
                .when(notificationService).repost(org.mockito.ArgumentMatchers.anyList());
        var photo = albumPhoto(101L, 42L, "Java developer role");
        processor.start();

        processor.onNewMessage(new TdApi.UpdateNewMessage(photo));

        verify(notificationService, timeout(2_000)).repost(
                org.mockito.ArgumentMatchers.<List<TdApi.Message>>argThat(
                        messages -> messages.size() == 1 && messages.getFirst() == photo));
        verify(trackedChatService, never()).advanceCursor(CHAT_ID, 101L);
    }

    @Test
    void flushesARecoveredPhotoAlbumWithoutWaitingForTheQuietPeriod() {
        var firstPhoto = albumPhoto(101L, 42L, "Java developer role");
        var secondPhoto = albumPhoto(102L, 42L, null);
        when(trackedChatService.listChats()).thenReturn(List.of(new TrackedChat(CHAT_ID, 100L)));
        when(trackedChatService.findChat(CHAT_ID))
                .thenReturn(Optional.of(new TrackedChat(CHAT_ID, 100L)));
        when(telegramClient.getChatHistory(CHAT_ID, 0L, 0, 100, false))
                .thenReturn(CompletableFuture.completedFuture(
                        new TdApi.Messages(2, new TdApi.Message[]{secondPhoto, firstPhoto})));
        when(telegramClient.getChatHistory(CHAT_ID, 101L, 0, 100, false))
                .thenReturn(CompletableFuture.completedFuture(messages(100L)));
        when(filterRuleService.matches("Java developer role")).thenReturn(true);
        processor.start();

        processor.onConnectionStateChanged(
                new TdApi.UpdateConnectionState(new TdApi.ConnectionStateReady()));

        verify(notificationService, timeout(500)).repost(
                org.mockito.ArgumentMatchers.<List<TdApi.Message>>argThat(
                        messages -> messages.equals(List.of(firstPhoto, secondPhoto))));
        verify(trackedChatService, timeout(500)).advanceCursor(CHAT_ID, 102L);
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

    private static TdApi.Message albumPhoto(long messageId, long mediaAlbumId, String caption) {
        var photo = new TdApi.MessagePhoto();
        photo.caption = caption == null ? null : formattedText(caption);
        var message = message(CHAT_ID, messageId, false);
        message.mediaAlbumId = mediaAlbumId;
        message.content = photo;
        return message;
    }

    private static Stream<Arguments> captionContents() {
        return Stream.of(
                captionContent("animation", TdApi.MessageAnimation::new, content -> ((TdApi.MessageAnimation) content).caption = formattedText("Java developer role")),
                captionContent("audio", TdApi.MessageAudio::new, content -> ((TdApi.MessageAudio) content).caption = formattedText("Java developer role")),
                captionContent("document", TdApi.MessageDocument::new, content -> ((TdApi.MessageDocument) content).caption = formattedText("Java developer role")),
                captionContent("paid media", TdApi.MessagePaidMedia::new, content -> ((TdApi.MessagePaidMedia) content).caption = formattedText("Java developer role")),
                captionContent("photo", TdApi.MessagePhoto::new, content -> ((TdApi.MessagePhoto) content).caption = formattedText("Java developer role")),
                captionContent("video", TdApi.MessageVideo::new, content -> ((TdApi.MessageVideo) content).caption = formattedText("Java developer role")),
                captionContent("voice note", TdApi.MessageVoiceNote::new, content -> ((TdApi.MessageVoiceNote) content).caption = formattedText("Java developer role")));
    }

    private static Arguments captionContent(
            String name,
            Supplier<TdApi.MessageContent> supplier,
            java.util.function.Consumer<TdApi.MessageContent> captionSetter) {
        return Arguments.of(name, (Supplier<TdApi.MessageContent>) () -> {
            var content = supplier.get();
            captionSetter.accept(content);
            return content;
        });
    }

    private static TdApi.FormattedText formattedText(String text) {
        return new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
    }

    private static TdApi.Messages messages(long... messageIds) {
        var messages = new TdApi.Message[messageIds.length];
        for (int index = 0; index < messageIds.length; index++) {
            messages[index] = message(CHAT_ID, messageIds[index], false);
        }
        return new TdApi.Messages(messages.length, messages);
    }
}
