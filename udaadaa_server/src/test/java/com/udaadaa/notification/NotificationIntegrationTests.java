package com.udaadaa.notification;

import com.udaadaa.AbstractIntegrationTest;
import com.udaadaa.chat.RoomId;
import com.udaadaa.member.MemberId;
import com.udaadaa.notification.domain.NotificationRepository;
import com.udaadaa.notification.domain.PushRecipient;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class NotificationIntegrationTests extends AbstractIntegrationTest {

    private static final UUID SENDER = UUID.fromString("4fa5a560-d4d2-41f3-b218-c84ac2a2f847");
    private static final UUID ROOM_PUSH_ON = UUID.fromString("a6c4cda5-a044-4f44-a75c-434d2592551d");
    private static final UUID ROOM_PUSH_OFF = UUID.fromString("b6c4cda5-a044-4f44-a75c-434d2592551d");
    private static final UUID GLOBAL_PUSH_OFF = UUID.fromString("c6c4cda5-a044-4f44-a75c-434d2592551d");
    private static final UUID NO_TOKEN = UUID.fromString("d6c4cda5-a044-4f44-a75c-434d2592551d");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void prepareTables() {
        jdbcTemplate.execute("""
                create table if not exists public.profiles (
                    id uuid primary key,
                    created_at timestamp with time zone not null default now(),
                    nickname text not null unique,
                    push_option boolean not null default true,
                    fcm_token text,
                    height numeric,
                    weight numeric
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists public.rooms (
                    id uuid primary key,
                    created_at timestamp with time zone not null default now(),
                    room_name text not null unique,
                    start_day date,
                    end_day date
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists public.room_participants (
                    created_at timestamp with time zone not null default now(),
                    user_id uuid not null,
                    room_id uuid not null,
                    push_option boolean not null default true,
                    last_read_sequence bigint not null default 0,
                    primary key (user_id, room_id)
                )
                """);
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("delete from public.room_participants");
        jdbcTemplate.update("delete from public.rooms");
        jdbcTemplate.update("delete from public.profiles");

        jdbcTemplate.update(
                "insert into public.profiles (id, nickname, push_option, fcm_token) values "
                        + "(?, ?, true, 'sender-token'), (?, ?, true, 'room-on-token'), "
                        + "(?, ?, false, 'room-off-token'), (?, ?, true, null), (?, ?, true, 'global-off-token')",
                SENDER, "발신자",
                ROOM_PUSH_ON, "방알림켜짐",
                ROOM_PUSH_OFF, "방알림꺼짐",
                NO_TOKEN, "토큰없음",
                GLOBAL_PUSH_OFF, "전역알림꺼짐"
        );
        // GLOBAL_PUSH_OFF는 profiles.push_option을 false로 별도 갱신(위 insert는 room_participants 값이라 헷갈리지 않게 분리)
        jdbcTemplate.update("update public.profiles set push_option = false where id = ?", GLOBAL_PUSH_OFF);

        jdbcTemplate.update("insert into public.rooms (id, room_name) values (?, ?)", ROOM_ID, "테스트 방");

        jdbcTemplate.update(
                "insert into public.room_participants (user_id, room_id, push_option) values "
                        + "(?, ?, true), (?, ?, true), (?, ?, false), (?, ?, true), (?, ?, true)",
                SENDER, ROOM_ID,
                ROOM_PUSH_ON, ROOM_ID,
                ROOM_PUSH_OFF, ROOM_ID,
                NO_TOKEN, ROOM_ID,
                GLOBAL_PUSH_OFF, ROOM_ID
        );
    }

    @Test
    void returnsOnlyRecipientsWithPushEnabledAndToken() {
        List<PushRecipient> recipients = notificationRepository.findPushableRecipients(
                RoomId.from(ROOM_ID), MemberId.from(SENDER)
        );

        Assertions.assertThat(recipients)
                .extracting(PushRecipient::memberId)
                .containsExactly(MemberId.from(ROOM_PUSH_ON));
    }

    @Test
    void returnsRoomName() {
        Assertions.assertThat(notificationRepository.findRoomName(RoomId.from(ROOM_ID)))
                .contains("테스트 방");
    }

    @Test
    void returnsEmptyRoomNameForNonExistentRoom() {
        Assertions.assertThat(notificationRepository.findRoomName(RoomId.from(UUID.randomUUID())))
                .isEmpty();
    }
}
