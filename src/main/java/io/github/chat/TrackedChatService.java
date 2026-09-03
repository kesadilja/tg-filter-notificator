package io.github.chat;

import java.util.List;
import java.util.Optional;

import io.github.config.DatabaseProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TrackedChatService {
  private final JdbcClient jdbcClient;
  private final String trackedChatTable;

  public TrackedChatService(JdbcClient jdbcClient, DatabaseProperties databaseProperties) {
    this.jdbcClient = jdbcClient;
    trackedChatTable = databaseProperties.schema() + "." + databaseProperties.tableName();
  }

  public boolean trackChat(long chatId) {
    requireValidChatId(chatId);
    return jdbcClient.sql("insert into " + trackedChatTable + " (chat_id) values (:chatId) "
            + "on conflict (chat_id) do nothing")
        .param("chatId", chatId)
        .update() == 1;
  }

  public boolean untrackChat(long chatId) {
    requireValidChatId(chatId);
    return jdbcClient.sql("delete from " + trackedChatTable + " where chat_id = :chatId")
        .param("chatId", chatId)
        .update() == 1;
  }

  public List<TrackedChat> listChats() {
    return jdbcClient.sql("select chat_id, last_message_id from " + trackedChatTable + " order by chat_id")
        .query(TrackedChat.class)
        .list();
  }

  public Optional<TrackedChat> findChat(long chatId) {
    requireValidChatId(chatId);
    return jdbcClient.sql("select chat_id, last_message_id from " + trackedChatTable + " where chat_id = :chatId")
        .param("chatId", chatId)
        .query(TrackedChat.class)
        .optional();
  }

  public boolean initializeCursor(long chatId, long messageId) {
    requireValidChatId(chatId);
    requireValidMessageId(messageId);
    return jdbcClient.sql("update " + trackedChatTable + " set last_message_id = :messageId "
            + "where chat_id = :chatId and last_message_id is null")
        .param("chatId", chatId)
        .param("messageId", messageId)
        .update() == 1;
  }

  public boolean advanceCursor(long chatId, long messageId) {
    requireValidChatId(chatId);
    requireValidMessageId(messageId);
    return jdbcClient.sql("update " + trackedChatTable + " "
            + "set last_message_id = greatest(coalesce(last_message_id, 0), :messageId) "
            + "where chat_id = :chatId")
        .param("chatId", chatId)
        .param("messageId", messageId)
        .update() == 1;
  }

  private static void requireValidChatId(long chatId) {
    if (chatId == 0) {
      throw new IllegalArgumentException("chatId must not be zero");
    }
  }

  private static void requireValidMessageId(long messageId) {
    if (messageId < 0) {
      throw new IllegalArgumentException("messageId must not be negative");
    }
  }
}
