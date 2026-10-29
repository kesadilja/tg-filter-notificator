package io.github.telegram;

import io.github.processor.TelegramUpdateListener;
import it.tdlight.jni.TdApi;

import java.util.concurrent.CompletableFuture;

public interface TelegramClient extends AutoCloseable {

    void start(TelegramUpdateListener listener);

    CompletableFuture<TdApi.Chat> getChat(long chatId);

    CompletableFuture<TdApi.Messages> getChatHistory(
            long chatId, long fromMessageId, int offset, int limit, boolean onlyLocal);

    CompletableFuture<TdApi.Messages> forwardMessages(
            long destinationChatId, long sourceChatId, long[] messageIds);

    @Override
    void close() throws Exception;
}
