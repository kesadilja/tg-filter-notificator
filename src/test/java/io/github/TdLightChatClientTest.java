package io.github;


import org.junit.jupiter.api.Test;

public class TdLightChatClientTest {

    @Test
    public void getChats() throws Exception {
        try (var tdLightChatClient = new TdLightChatClient(
                "381621500402", "C:/tg_test", TdLightChatClient.dialogAuthentication())) {
            var result = tdLightChatClient.getChats(10);
            System.out.println(result.stream().map(chat -> chat.title).toList());
        }
    }
}
