# tg-collector

A Spring Boot service that follows selected Telegram chats through TDLight. It logs incoming
messages and edit/delete updates, stores one high-water mark per chat in PostgreSQL, and uses
Telegram history to recover messages missed while disconnected.

## Configuration

Set these environment variables before starting the application:

```text
TELEGRAM_PHONE_NUMBER=...
TELEGRAM_API_ID=...
TELEGRAM_API_HASH=...
TELEGRAM_DATABASE_DIRECTORY=target/tdlib-data
DATABASE_URL=jdbc:postgresql://localhost:5432/postgres
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres
DATABASE_SCHEMA=tg_collector
DATABASE_TABLE=tracked_chat
```

The first Telegram login is interactive and requests the authentication code/password from the
console. Flyway creates the `tg_collector.tracked_chat` table automatically.

Run the application with:

```shell
./mvnw spring-boot:run
```

Chats are managed through `TrackedChatServiceImpl` for now. They can also be inserted manually:

```sql
insert into tg_collector.tracked_chat (chat_id) values (-1001234567890)
on conflict (chat_id) do nothing;
```

A newly inserted chat has a null cursor. On the next initial connection/reconnection, the service
sets that cursor to the current latest message and begins collecting from that point forward.
