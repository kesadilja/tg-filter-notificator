create schema if not exists ${schema};

create table ${schema}.${table} (
    chat_id bigint primary key,
    last_message_id bigint null
);
