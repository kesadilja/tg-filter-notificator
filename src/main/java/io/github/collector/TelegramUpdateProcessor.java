package io.github.collector;

import io.github.chat.TrackedChat;
import io.github.chat.TrackedChatService;
import io.github.filter.FilterRuleService;
import io.github.notification.TelegramNotificationService;
import io.github.telegram.TelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TelegramUpdateProcessor implements SmartLifecycle, TelegramUpdateListener {
    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateProcessor.class);
    private static final int HISTORY_PAGE_SIZE = 100;
    private static final long PHOTO_ALBUM_QUIET_PERIOD_SECONDS = 1;
    private static final String THREAD_NAME = "telegram-collector-events";

    private final TelegramClient telegramClient;
    private final TrackedChatService trackedChatService;
    private final FilterRuleService filterRuleService;
    private final TelegramNotificationService notificationService;
    private final ScheduledExecutorService eventExecutor;
    private final Map<AlbumKey, PendingPhotoAlbum> pendingPhotoAlbums = new HashMap<>();
    private final AtomicBoolean connectionReady = new AtomicBoolean();
    private volatile boolean running;

    @Autowired
    public TelegramUpdateProcessor(
            TelegramClient telegramClient,
            TrackedChatService trackedChatService,
            FilterRuleService filterRuleService,
            TelegramNotificationService notificationService) {
        this.telegramClient = telegramClient;
        this.trackedChatService = trackedChatService;
        this.filterRuleService = filterRuleService;
        this.notificationService = notificationService;
        this.eventExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(THREAD_NAME).factory());
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            telegramClient.start(this);
        } catch (RuntimeException exception) {
            running = false;
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false;
        connectionReady.set(false);
        flushPendingAlbumsBeforeShutdown();
        try {
            telegramClient.close();
        } catch (Exception exception) {
            log.error("Failed to close Telegram client", exception);
        }
        eventExecutor.shutdown();
        try {
            if (!eventExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Telegram event executor did not stop within 10 seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void onNewMessage(TdApi.UpdateNewMessage update) {
        submit("new message", () -> process(update.message));
    }

    @Override
    public void onMessageContentChanged(TdApi.UpdateMessageContent update) {
        submit("message content change", () -> {
            if (trackedChatService.findChat(update.chatId).isPresent()) {
                log.info("Telegram message content changed chatId={} messageId={} content={}",
                        update.chatId, update.messageId, describeContent(update.newContent));
            }
        });
    }

    @Override
    public void onMessageEdited(TdApi.UpdateMessageEdited update) {
        submit("message edit", () -> {
            if (trackedChatService.findChat(update.chatId).isPresent()) {
                log.info("Telegram message edited chatId={} messageId={} editDate={} replyMarkupType={}",
                        update.chatId, update.messageId, update.editDate, typeName(update.replyMarkup));
            }
        });
    }

    @Override
    public void onMessagesDeleted(TdApi.UpdateDeleteMessages update) {
        if (update.fromCache) return;
        submit("message deletion", () -> {
            if (trackedChatService.findChat(update.chatId).isPresent()) {
                log.info("Telegram messages deleted chatId={} messageIds={} permanent={}",
                        update.chatId, Arrays.toString(update.messageIds), update.isPermanent);
            }
        });
    }

    @Override
    public void onConnectionStateChanged(TdApi.UpdateConnectionState update) {
        boolean ready = update.state instanceof TdApi.ConnectionStateReady;
        if (ready && connectionReady.compareAndSet(false, true)) {
            submit("history recovery", this::recoverAllChats);
        } else if (!ready) {
            connectionReady.set(false);
        }
    }

    private void recoverAllChats() {
        var chats = trackedChatService.listChats();
        log.info("Recovering Telegram history for {} tracked chats", chats.size());
        for (var chat : chats) {
            try {
                recoverChat(chat);
            } catch (RuntimeException exception) {
                log.error("Failed to recover Telegram history for chatId={}", chat.chatId(), exception);
            }
        }
    }

    private void recoverChat(TrackedChat trackedChat) {
        if (trackedChat.lastMessageId() == null) {
            initializeChatCursor(trackedChat.chatId());
            return;
        }
        var recovered = loadMessagesAfter(trackedChat.chatId(), trackedChat.lastMessageId());
        recovered.sort(Comparator.comparingLong(message -> message.id));
        recovered.forEach(this::process);
        flushPendingPhotoAlbums(trackedChat.chatId(), null);
        if (!recovered.isEmpty()) {
            log.info("Recovered {} Telegram messages for chatId={}", recovered.size(), trackedChat.chatId());
        }
    }

    private void initializeChatCursor(long chatId) {
        var chat = telegramClient.getChat(chatId).join();
        long initialMessageId = chat.lastMessage == null ? 0 : chat.lastMessage.id;
        if (trackedChatService.initializeCursor(chatId, initialMessageId)) {
            log.info("Initialized Telegram cursor chatId={} messageId={}", chatId, initialMessageId);
        }
    }

    private List<TdApi.Message> loadMessagesAfter(long chatId, long cursor) {
        List<TdApi.Message> recovered = new ArrayList<>();
        Set<Long> seenMessageIds = new HashSet<>();
        long fromMessageId = 0;
        while (true) {
            TdApi.Messages page;
            try {
                page = telegramClient.getChatHistory(chatId, fromMessageId, 0, HISTORY_PAGE_SIZE, false).join();
            } catch (CompletionException exception) {
                throw new IllegalStateException("Telegram history request failed", exception.getCause());
            }
            if (page.messages == null || page.messages.length == 0) break;

            boolean reachedCursor = false;
            long oldestMessageId = Long.MAX_VALUE;
            for (var message : page.messages) {
                oldestMessageId = Math.min(oldestMessageId, message.id);
                if (message.id <= cursor) {
                    reachedCursor = true;
                } else if (seenMessageIds.add(message.id)) {
                    recovered.add(message);
                }
            }
            if (reachedCursor || oldestMessageId == Long.MAX_VALUE) break;
            if (fromMessageId != 0 && oldestMessageId >= fromMessageId) {
                log.warn("Telegram history pagination made no progress chatId={} fromMessageId={}",
                        chatId, fromMessageId);
                break;
            }
            fromMessageId = oldestMessageId;
        }
        return recovered;
    }

    private static String describeContent(TdApi.MessageContent content) {
        var text = messageText(content);
        if (text != null) {
            return "text:" + text;
        }
        return typeName(content);
    }

    private static String typeName(Object value) {
        return value == null ? "none" : value.getClass().getSimpleName();
    }

    private void submit(String operation, Runnable task) {
        if (!running) return;
        try {
            eventExecutor.execute(() -> runSafely(operation, task));
        } catch (RejectedExecutionException exception) {
            if (running) {
                log.error("Telegram {} was rejected by the event executor", operation, exception);
            }
        }
    }

    private void runSafely(String operation, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException exception) {
            log.error("Failed to process Telegram {}", operation, exception);
        }
    }

    private void process(TdApi.Message message) {
        if (message == null) {
            return;
        }
        var trackedChat = trackedChatService.findChat(message.chatId);
        if (trackedChat.isEmpty()) {
            return;
        }
        var albumKey = photoAlbumKey(message);
        if (flushPendingPhotoAlbums(message.chatId, albumKey)) {
            trackedChat = trackedChatService.findChat(message.chatId);
            if (trackedChat.isEmpty()) {
                return;
            }
        }
        var cursor = trackedChat.get().lastMessageId();
        if (cursor != null && message.id <= cursor) {
            return;
        }

        if (albumKey != null) {
            bufferPhotoAlbum(albumKey, message);
            return;
        }

        if (!message.isOutgoing && filterRuleService.matches(messageText(message.content))) {
            log.info("Telegram new message chatId={} messageId={} sender={} date={} content={}",
                    message.chatId, message.id, describeSender(message.senderId), message.date,
                    describeContent(message.content));
            notificationService.repost(message);
        }
        if (message.id >= 0) {
            trackedChatService.advanceCursor(message.chatId, message.id);
        }
    }

    private AlbumKey photoAlbumKey(TdApi.Message message) {
        if (!message.isOutgoing && message.mediaAlbumId != 0 && message.content instanceof TdApi.MessagePhoto) {
            return new AlbumKey(message.chatId, message.mediaAlbumId);
        }
        return null;
    }

    private void bufferPhotoAlbum(AlbumKey albumKey, TdApi.Message message) {
        var album = pendingPhotoAlbums.computeIfAbsent(albumKey, ignored -> new PendingPhotoAlbum());
        album.messages.put(message.id, message);
        if (album.flushTask != null) {
            album.flushTask.cancel(false);
        }
        album.flushTask = eventExecutor.schedule(
                () -> runSafely("photo album", () -> flushPhotoAlbum(albumKey)),
                PHOTO_ALBUM_QUIET_PERIOD_SECONDS,
                TimeUnit.SECONDS);
    }

    private boolean flushPendingPhotoAlbums(long chatId, AlbumKey except) {
        var albumKeys = pendingPhotoAlbums.keySet().stream()
                .filter(key -> key.chatId == chatId && !key.equals(except))
                .toList();
        albumKeys.forEach(this::flushPhotoAlbum);
        return !albumKeys.isEmpty();
    }

    private void flushPhotoAlbum(AlbumKey albumKey) {
        var album = pendingPhotoAlbums.remove(albumKey);
        if (album == null) {
            return;
        }
        if (album.flushTask != null) {
            album.flushTask.cancel(false);
        }
        var messages = List.copyOf(album.messages.values());
        boolean matches = messages.stream()
                .map(message -> messageText(message.content))
                .anyMatch(filterRuleService::matches);
        if (matches) {
            log.info("Telegram photo album matched chatId={} mediaAlbumId={} messageIds={}",
                    albumKey.chatId, albumKey.mediaAlbumId,
                    messages.stream().map(message -> message.id).toList());
            notificationService.repost(messages);
        }
        long lastMessageId = messages.getLast().id;
        if (lastMessageId >= 0) {
            trackedChatService.advanceCursor(albumKey.chatId, lastMessageId);
        }
    }

    private void flushPendingAlbumsBeforeShutdown() {
        try {
            eventExecutor.submit(() -> List.copyOf(pendingPhotoAlbums.keySet()).forEach(this::flushPhotoAlbum))
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException exception) {
            log.error("Failed to flush pending Telegram photo albums during shutdown", exception);
        }
    }

    private static String messageText(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessageText messageText && messageText.text != null) {
            return messageText.text.text;
        }
        if (content instanceof TdApi.MessageAnimation message) return getText(message.caption);
        if (content instanceof TdApi.MessageAudio message) return getText(message.caption);
        if (content instanceof TdApi.MessageDocument message) return getText(message.caption);
        if (content instanceof TdApi.MessagePaidMedia message) return getText(message.caption);
        if (content instanceof TdApi.MessagePhoto message) return getText(message.caption);
        if (content instanceof TdApi.MessageVideo message) return getText(message.caption);
        if (content instanceof TdApi.MessageVoiceNote message) return getText(message.caption);
        return null;
    }

    private static String getText(TdApi.FormattedText caption) {
        return Optional.ofNullable(caption).map(c -> c.text).orElse(null);
    }


    private static String describeSender(TdApi.MessageSender sender) {
        return switch (sender) {
            case TdApi.MessageSenderUser user -> "user:" + user.userId;
            case TdApi.MessageSenderChat chat -> "chat:" + chat.chatId;
            case null -> "unknown";
            default -> sender.getClass().getSimpleName();
        };
    }

    private record AlbumKey(long chatId, long mediaAlbumId) {
    }

    private static final class PendingPhotoAlbum {
        private final NavigableMap<Long, TdApi.Message> messages = new TreeMap<>();
        private ScheduledFuture<?> flushTask;
    }

}
