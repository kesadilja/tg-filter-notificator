package io.github;

import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.ClientInteraction;
import it.tdlight.client.InputParameter;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.Scanner;
import javax.swing.JOptionPane;


public final class TdLightChatClient implements AutoCloseable {
  private final SimpleTelegramClientFactory factory;
  private final SimpleTelegramClient client;

  public TdLightChatClient(String phoneNumber, String databaseDirectory) {
    this(phoneNumber, databaseDirectory, new Scanner(System.in));
  }

  /** Creates a client whose authentication prompts are read from the supplied terminal. */
  public TdLightChatClient(String phoneNumber, String databaseDirectory, Scanner terminal) {
    this(phoneNumber, databaseDirectory, (parameter, ignored) -> readFromTerminal(terminal, parameter));
  }

  /** Creates a client with a custom authentication input provider. */
  public TdLightChatClient(String phoneNumber, String databaseDirectory,
                           ClientInteraction clientInteraction) {
    if (phoneNumber == null || phoneNumber.isBlank()) {
      throw new IllegalArgumentException("phoneNumber must not be blank");
    }
    if (databaseDirectory == null || databaseDirectory.isBlank()) {
      throw new IllegalArgumentException("databaseDirectory must not be blank");
    }
    if (clientInteraction == null) {
      throw new NullPointerException("clientInteraction must not be null");
    }
    Path path = Paths.get(databaseDirectory);
    var settings = TDLibSettings.create(APIToken.example());
    settings.setDatabaseDirectoryPath(path);
    settings.setDownloadedFilesDirectoryPath(path.resolve("downloads"));

    factory = new SimpleTelegramClientFactory();
    var builder = factory.builder(settings);
    builder.setClientInteraction(clientInteraction);
    AuthenticationSupplier<?> authenticationSupplier = AuthenticationSupplier.user(phoneNumber);
    client = builder.build(authenticationSupplier);
  }

  private static CompletableFuture<String> readFromTerminal(Scanner terminal, InputParameter parameter) {
    return CompletableFuture.supplyAsync(() -> {
      System.out.print("Enter " + parameter.name().toLowerCase().replace('_', ' ') + ": ");
      System.out.flush();
      if (!terminal.hasNextLine()) {
        throw new IllegalStateException("No authentication input available on the terminal");
      }
      return terminal.nextLine().trim();
    });
  }

  public static ClientInteraction dialogAuthentication() {
    return (parameter, ignored) -> CompletableFuture.supplyAsync(() -> {
      var value = JOptionPane.showInputDialog(
          null,
          "Enter " + parameter.name().toLowerCase().replace('_', ' ') + ":",
          "Telegram authentication",
          JOptionPane.QUESTION_MESSAGE);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException("Authentication input was cancelled");
      }
      return value.trim();
    });
  }

  public List<TdApi.Chat> getChats(int limit) throws ExecutionException, InterruptedException {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return client.loadChatListMainAsync()
        .thenCompose(_ -> client.send(new TdApi.GetChats(new TdApi.ChatListMain(), limit)))
        .thenCompose(chatIds -> {
          var requests = Arrays.stream(chatIds.chatIds)
              .mapToObj(id -> client.send(new TdApi.GetChat(id)))
              .toArray(CompletableFuture[]::new);
          return CompletableFuture.allOf(requests)
              .thenApply(_ -> Arrays.stream(requests)
                  .map(request -> (TdApi.Chat) request.join())
                  .toList());
        }).get();
  }

  @Override
  public void close() throws Exception {
    // Stop this client's native/background threads before releasing the shared factory.
    client.closeAndWait();
    factory.close();
  }
}
