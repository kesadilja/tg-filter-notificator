package io.github.telegram;

import io.github.collector.TelegramUpdateListener;
import io.github.config.TelegramProperties;
import it.tdlight.client.*;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

@Component
public final class TdLightTelegramClient implements TelegramClient {
    private static final Logger log = LoggerFactory.getLogger(TdLightTelegramClient.class);

    private final TelegramProperties properties;

    private SimpleTelegramClientFactory factory;
    private SimpleTelegramClient client;

    TdLightTelegramClient(TelegramProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public synchronized void start(TelegramUpdateListener listener) {
        if (client != null) return;
        var token = new APIToken(properties.apiId(), properties.apiHash());
        var settings = TDLibSettings.create(token);
        settings.setDatabaseDirectoryPath(properties.databaseDirectory());
        settings.setDownloadedFilesDirectoryPath(properties.databaseDirectory().resolve("downloads"));
        factory = new SimpleTelegramClientFactory();
        var builder = factory.builder(settings);
        builder.setClientInteraction((parameter, _) -> readFromTerminal(parameter));
        builder.addUpdateHandler(TdApi.UpdateNewMessage.class, listener::onNewMessage);
        builder.addUpdateHandler(TdApi.UpdateMessageContent.class, listener::onMessageContentChanged);
        builder.addUpdateHandler(TdApi.UpdateMessageEdited.class, listener::onMessageEdited);
        builder.addUpdateHandler(TdApi.UpdateDeleteMessages.class, listener::onMessagesDeleted);
        builder.addUpdateHandler(TdApi.UpdateConnectionState.class, listener::onConnectionStateChanged);
        builder.addUpdateExceptionHandler(throwable -> log.error("Unhandled exception in Telegram update handler", throwable));
        builder.addDefaultExceptionHandler(throwable -> log.error("Unhandled exception from Telegram client", throwable));
        AuthenticationSupplier<?> authentication = AuthenticationSupplier.user(properties.phoneNumber());
        client = builder.build(authentication);
    }

    @Override
    public CompletableFuture<TdApi.Chat> getChat(long chatId) {
        return client.send(new TdApi.GetChat(chatId));
    }

    @Override
    public CompletableFuture<TdApi.Messages> getChatHistory(
            long chatId,
            long fromMessageId,
            int offset,
            int limit,
            boolean onlyLocal
    ) {
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (offset > 0 || offset < -99 || limit < -offset) {
            throw new IllegalArgumentException("invalid history offset/limit combination");
        }
        return client.send(new TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, onlyLocal));
    }

    @Override
    public synchronized void close() throws Exception {
        var startedClient = client;
        client = null;
        if (startedClient != null) {
            startedClient.closeAndWait();
        }
        var startedFactory = factory;
        factory = null;
        if (startedFactory != null) {
            startedFactory.close();
        }
    }

    private static CompletableFuture<String> readFromTerminal(InputParameter parameter) {
        Scanner terminal = new Scanner(System.in);
        return CompletableFuture.supplyAsync(() -> {
            System.out.print("Enter " + parameter.name() + ": ");
            System.out.flush();
            if (!terminal.hasNextLine()) {
                throw new IllegalStateException("No authentication input available on the terminal");
            }
            var value = terminal.nextLine().trim();
            if (value.isBlank()) {
                throw new IllegalStateException("Authentication input must not be blank");
            }
            return value;
        });
    }
}
