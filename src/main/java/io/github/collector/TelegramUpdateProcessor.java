package io.github.collector;

import io.github.chat.TrackedChat;
import io.github.chat.TrackedChatService;
import io.github.filter.FilterRuleService;
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
    private static final String THREAD_NAME = "telegram-collector-events";

    private final TelegramClient telegramClient;
    private final TrackedChatService trackedChatService;
    private final FilterRuleService filterRuleService;
    private final ExecutorService eventExecutor;
    private final AtomicBoolean connectionReady = new AtomicBoolean();
    private volatile boolean running;

    @Autowired
    public TelegramUpdateProcessor(
            TelegramClient telegramClient,
            TrackedChatService trackedChatService,
            FilterRuleService filterRuleService) {
        this.telegramClient = telegramClient;
        this.trackedChatService = trackedChatService;
        this.filterRuleService = filterRuleService;
        this.eventExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name(THREAD_NAME).factory());
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
        if (content instanceof TdApi.MessageText messageText && messageText.text != null) {
            return "text:" + messageText.text.text;
        }
        return typeName(content);
    }

    private static String typeName(Object value) {
        return value == null ? "none" : value.getClass().getSimpleName();
    }

    private void submit(String operation, Runnable task) {
        if (!running) return;
        try {
            eventExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    log.error("Failed to process Telegram {}", operation, exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            if (running) {
                log.error("Telegram {} was rejected by the event executor", operation, exception);
            }
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
        var cursor = trackedChat.get().lastMessageId();
        if (cursor != null && message.id <= cursor) {
            return;
        }

        if (!message.isOutgoing && filterRuleService.matches(messageText(message.content))) {
            log.info("Telegram new message chatId={} messageId={} sender={} date={} content={}",
                    message.chatId, message.id, describeSender(message.senderId), message.date,
                    describeContent(message.content));
        }
        if (message.id >= 0) {
            trackedChatService.advanceCursor(message.chatId, message.id);
        }
    }

    private static String messageText(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessageText messageText && messageText.text != null) {
            return messageText.text.text;
        }
        return null;
    }

    private static String describeSender(TdApi.MessageSender sender) {
        return switch (sender) {
            case TdApi.MessageSenderUser user -> "user:" + user.userId;
            case TdApi.MessageSenderChat chat -> "chat:" + chat.chatId;
            case null -> "unknown";
            default -> sender.getClass().getSimpleName();
        };
    }

}
