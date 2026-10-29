package io.github.chat;

import io.github.config.DatabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;


@Import(TrackedChatService.class)
@EnableConfigurationProperties(DatabaseProperties.class)
@ActiveProfiles("test")
@SpringBootTest
class TrackedChatServiceTest {

    @Autowired
    private TrackedChatService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    DatabaseProperties databaseProperties;

    @BeforeEach
    void setUp() {
        var sql = String.format("truncate table %s.%s", databaseProperties.schema(), databaseProperties.tableName());
        jdbcClient.sql(sql).update();
    }

    @Test
    void tracksAndUntracksChatIdempotently() {
        assertThat(service.trackChat(-100123L)).isTrue();
        assertThat(service.trackChat(-100123L)).isFalse();
        assertThat(service.listChats()).containsExactly(new TrackedChat(-100123L, null));

        assertThat(service.untrackChat(-100123L)).isTrue();
        assertThat(service.untrackChat(-100123L)).isFalse();
        assertThat(service.listChats()).isEmpty();
    }

    @Test
    void initializesCursorOnlyOnceAndNeverMovesItBackward() {
        service.trackChat(-100123L);

        assertThat(service.initializeCursor(-100123L, 100L)).isTrue();
        assertThat(service.initializeCursor(-100123L, 200L)).isFalse();
        assertThat(service.advanceCursor(-100123L, 90L)).isTrue();
        assertThat(service.findChat(-100123L))
                .contains(new TrackedChat(-100123L, 100L));

        assertThat(service.advanceCursor(-100123L, 300L)).isTrue();
        assertThat(service.findChat(-100123L))
                .contains(new TrackedChat(-100123L, 300L));
    }

    @Test
    void reAddingAChatStartsWithANullCursor() {
        service.trackChat(42L);
        service.initializeCursor(42L, 100L);
        service.untrackChat(42L);
        service.trackChat(42L);

        assertThat(service.findChat(42L)).contains(new TrackedChat(42L, null));
    }
}
