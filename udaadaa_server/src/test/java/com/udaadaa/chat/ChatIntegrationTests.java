package com.udaadaa.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.udaadaa.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ChatIntegrationTests extends AbstractIntegrationTest {

    private static final UUID USER_A = UUID.fromString("4fa5a560-d4d2-41f3-b218-c84ac2a2f847");
    private static final UUID USER_B = UUID.fromString("a6c4cda5-a044-4f44-a75c-434d2592551d");
    private static final UUID ROOM_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void prepareTables() {
        jdbcTemplate.execute("""
                do $$
                begin
                    if not exists (select 1 from pg_type where typname = 'MessageType') then
                        create type "MessageType" as enum ('infoMessage', 'textMessage', 'imageMessage', 'missionMessage');
                    end if;
                end $$
                """);
        jdbcTemplate.execute("""
                create table if not exists public.profiles (
                    id uuid primary key,
                    created_at timestamp with time zone not null default now(),
                    nickname text not null unique
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
                    primary key (user_id, room_id)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists public.messages (
                    id uuid primary key,
                    room_id uuid not null,
                    user_id uuid not null,
                    content text,
                    type "MessageType" not null,
                    image_path text,
                    created_at timestamp with time zone not null default now(),
                    is_deleted boolean default false,
                    sequence bigint,
                    client_message_id uuid
                )
                """);
        jdbcTemplate.execute("""
                create unique index if not exists messages_room_client_message_id_key
                    on public.messages (room_id, client_message_id) where client_message_id is not null
                """);
        // 운영 마이그레이션(20260806120000)과 동일한 sequence 자동 채번 트리거.
        // 3-2 저장 경로가 트리거에 의존하므로 테스트 DB에도 그대로 재현해야 한다.
        jdbcTemplate.execute("""
                create table if not exists public.room_message_sequences (
                    room_id uuid primary key references public.rooms(id) on delete cascade,
                    last_sequence bigint not null default 0
                )
                """);
        jdbcTemplate.execute("""
                create or replace function public.assign_message_sequence()
                returns trigger
                language plpgsql
                security definer
                set search_path = public
                as $$
                declare
                    next_seq bigint;
                begin
                    if new.sequence is not null then
                        return new;
                    end if;

                    insert into public.room_message_sequences (room_id, last_sequence)
                    values (new.room_id, 1)
                    on conflict (room_id) do update
                        set last_sequence = public.room_message_sequences.last_sequence + 1
                    returning last_sequence into next_seq;

                    new.sequence := next_seq;
                    return new;
                end;
                $$
                """);
        jdbcTemplate.execute("drop trigger if exists messages_assign_sequence on public.messages");
        jdbcTemplate.execute("""
                create trigger messages_assign_sequence
                    before insert on public.messages
                    for each row
                    execute function public.assign_message_sequence()
                """);
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("delete from public.messages");
        jdbcTemplate.update("delete from public.room_participants");
        jdbcTemplate.update("delete from public.rooms");
        jdbcTemplate.update("delete from public.profiles");
        jdbcTemplate.update(
                "insert into public.profiles (id, nickname) values (?, ?), (?, ?)",
                USER_A, "사용자 A", USER_B, "사용자 B"
        );
        jdbcTemplate.update(
                "insert into public.rooms (id, room_name) values (?, ?), (?, ?)",
                ROOM_1, "방 1", ROOM_2, "방 2"
        );
        // USER_A는 두 방 모두 참가, USER_B는 ROOM_2만 참가
        jdbcTemplate.update(
                "insert into public.room_participants (user_id, room_id) values (?, ?), (?, ?), (?, ?)",
                USER_A, ROOM_1, USER_A, ROOM_2, USER_B, ROOM_2
        );
    }

    @Test
    void listsOnlyRoomsIAmParticipantIn() throws Exception {
        mockMvc.perform(get("/api/v1/chat/rooms")
                        .header("Authorization", bearerToken(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ROOM_2.toString()));
    }

    @Test
    void returnsRoomsWithLastMessage() throws Exception {
        insertMessage(ROOM_1, USER_A, "첫 메시지", 1);
        insertMessage(ROOM_1, USER_A, "두번째 메시지", 2);

        mockMvc.perform(get("/api/v1/chat/rooms")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + ROOM_1 + "')].lastMessage.content").value("두번째 메시지"));
    }

    @Test
    void returnsMessagesAfterSequenceInAscendingOrder() throws Exception {
        insertMessage(ROOM_1, USER_A, "1", 1);
        insertMessage(ROOM_1, USER_A, "2", 2);
        insertMessage(ROOM_1, USER_A, "3", 3);

        mockMvc.perform(get("/api/v1/chat/rooms/" + ROOM_1 + "/messages")
                        .param("after", "1")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("2"))
                .andExpect(jsonPath("$[1].content").value("3"));
    }

    @Test
    void rejectsMessageAccessForNonParticipant() throws Exception {
        insertMessage(ROOM_1, USER_A, "비밀 메시지", 1);

        mockMvc.perform(get("/api/v1/chat/rooms/" + ROOM_1 + "/messages")
                        .header("Authorization", bearerToken(USER_B)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void rejectsMessageAccessForNonExistentRoomWithSameError() throws Exception {
        mockMvc.perform(get("/api/v1/chat/rooms/" + UUID.randomUUID() + "/messages")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void sendsMessageAndAssignsSequence() throws Exception {
        mockMvc.perform(post("/api/v1/chat/rooms/" + ROOM_1 + "/messages")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "clientMessageId": "%s",
                                    "type": "textMessage",
                                    "content": "안녕하세요"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("안녕하세요"))
                .andExpect(jsonPath("$.sequence").value(1));
    }

    @Test
    void replayingSameClientMessageIdDoesNotDuplicate() throws Exception {
        UUID clientMessageId = UUID.randomUUID();
        String body = """
                {
                    "clientMessageId": "%s",
                    "type": "textMessage",
                    "content": "중복 방지 확인"
                }
                """.formatted(clientMessageId);

        mockMvc.perform(post("/api/v1/chat/rooms/" + ROOM_1 + "/messages")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(1));

        // 같은 clientMessageId로 재전송해도 새 메시지가 생기지 않고 기존 메시지를 그대로 반환해야 한다.
        mockMvc.perform(post("/api/v1/chat/rooms/" + ROOM_1 + "/messages")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(1));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from public.messages where room_id = ? and client_message_id = ?",
                Integer.class, ROOM_1, clientMessageId
        );
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsSendFromNonParticipant() throws Exception {
        mockMvc.perform(post("/api/v1/chat/rooms/" + ROOM_1 + "/messages")
                        .header("Authorization", bearerToken(USER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "clientMessageId": "%s",
                                    "type": "textMessage",
                                    "content": "권한 없는 방"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void rejectsDisallowedMessageType() throws Exception {
        mockMvc.perform(post("/api/v1/chat/rooms/" + ROOM_1 + "/messages")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "clientMessageId": "%s",
                                    "type": "missionMessage",
                                    "content": "이 API로는 만들 수 없음"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsContentTypeMismatch() throws Exception {
        // textMessage인데 content가 비어 있으면 SendMessageRequest의 @AssertTrue 검증에서 걸러진다.
        mockMvc.perform(post("/api/v1/chat/rooms/" + ROOM_1 + "/messages")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "clientMessageId": "%s",
                                    "type": "textMessage"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private void insertMessage(UUID roomId, UUID userId, String content, long sequence) {
        jdbcTemplate.update(
                "insert into public.messages (id, room_id, user_id, content, type, sequence) values "
                        + "(?, ?, ?, ?, cast(? as \"MessageType\"), ?)",
                UUID.randomUUID(), roomId, userId, content, "textMessage", sequence
        );
    }

    private String bearerToken(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JWT_ISSUER)
                .subject(userId.toString())
                .audience(List.of(JWT_AUDIENCE))
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .claim("role", "authenticated")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(JWT_SECRET.getBytes(StandardCharsets.UTF_8))
        );
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
