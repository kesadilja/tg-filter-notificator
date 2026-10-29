package io.github.processor;

import it.tdlight.jni.TdApi;

public interface TelegramUpdateListener {

    void onNewMessage(TdApi.UpdateNewMessage update);

    void onMessageContentChanged(TdApi.UpdateMessageContent update);

    void onMessageEdited(TdApi.UpdateMessageEdited update);

    void onMessagesDeleted(TdApi.UpdateDeleteMessages update);

    void onConnectionStateChanged(TdApi.UpdateConnectionState update);
}
