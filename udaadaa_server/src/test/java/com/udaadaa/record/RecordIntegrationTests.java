package com.udaadaa.record;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.udaadaa.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class RecordIntegrationTests extends AbstractIntegrationTest {

    private static final UUID USER_A = UUID.fromString("4fa5a560-d4d2-41f3-b218-c84ac2a2f847");
    private static final UUID ROOM_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    void prepareTables() {
        jdbcTemplate.execute("""
                do $$
                begin
                    if not exists (select 1 from pg_type where typname = 'FeedType') then
                        create type "FeedType" as enum ('breakfast', 'lunch', 'dinner', 'snack', 'exercise', 'weight');
                    end if;
                    if not exists (select 1 from pg_type where typname = 'MessageType') then
                        create type "MessageType" as enum ('infoMessage', 'textMessage', 'imageMessage', 'missionMessage');
                    end if;
                end $$
                """);
        // 다른 모듈 테스트와 같은 컨테이너를 공유하므로(스프링 컨텍스트 캐싱) 컬럼 정의를 그대로 맞춘다.
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
        jdbcTemplate.execute("""
                create table if not exists public.feed (
                    id uuid primary key,
                    user_id uuid not null,
                    created_at timestamp with time zone not null default now(),
                    review text not null default '',
                    type "FeedType" not null,
                    image_path text not null default '',
                    visibility boolean not null default true,
                    calorie bigint,
                    is_challenge boolean not null default false
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists public.weight (
                    id uuid primary key,
                    created_at timestamp with time zone not null default now(),
                    weight double precision not null,
                    date date not null,
                    user_id uuid not null,
                    image_path text not null default ''
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists public.report (
                    id uuid primary key default gen_random_uuid(),
                    user_id uuid not null,
                    date date not null,
                    created_at timestamp with time zone not null default now(),
                    breakfast bigint,
                    lunch bigint,
                    dinner bigint,
                    snack bigint,
                    exercise bigint,
                    weight double precision,
                    unique (user_id, date)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists public.record_mission_commits (
                    client_request_id uuid primary key,
                    user_id uuid not null,
                    room_id uuid,
                    feed_id uuid,
                    weight_id uuid,
                    created_at timestamp with time zone not null default now()
                )
                """);
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("delete from public.record_mission_commits");
        jdbcTemplate.update("delete from public.report");
        jdbcTemplate.update("delete from public.feed");
        jdbcTemplate.update("delete from public.weight");
        jdbcTemplate.update("delete from public.messages");
        jdbcTemplate.update("delete from public.room_participants");
        jdbcTemplate.update("delete from public.rooms");
        jdbcTemplate.update("delete from public.profiles");
        jdbcTemplate.update("insert into public.profiles (id, nickname) values (?, ?)", USER_A, "사용자 A");
        jdbcTemplate.update("insert into public.rooms (id, room_name) values (?, ?)", ROOM_1, "방 1");
        jdbcTemplate.update(
                "insert into public.room_participants (user_id, room_id) values (?, ?)", USER_A, ROOM_1
        );
    }

    @Test
    void commitsMealMissionAtomically() throws Exception {
        Map<String, Object> body = Map.of(
                "clientRequestId", UUID.randomUUID().toString(),
                "roomId", ROOM_1.toString(),
                "type", "breakfast",
                "review", "맛있게 먹었다",
                "messageContent", "#아침 맛있게 먹었다",
                "feedImagePath", "feed/a.jpg",
                "messageImagePath", "msg/a.jpg",
                "calorie", 450
        );

        mockMvc.perform(post("/api/v1/records/missions")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedId").isNotEmpty())
                .andExpect(jsonPath("$.weightId").doesNotExist());

        Long feedCount = jdbcTemplate.queryForObject(
                "select count(*) from public.feed where user_id = ? and type = cast('breakfast' as \"FeedType\")",
                Long.class, USER_A
        );
        org.assertj.core.api.Assertions.assertThat(feedCount).isEqualTo(1);

        Long reportBreakfast = jdbcTemplate.queryForObject(
                "select breakfast from public.report where user_id = ? and date = ?",
                Long.class, USER_A, LocalDate.now(KST)
        );
        org.assertj.core.api.Assertions.assertThat(reportBreakfast).isEqualTo(450L);

        Long missionMessages = jdbcTemplate.queryForObject(
                "select count(*) from public.messages where room_id = ? and type = cast('missionMessage' as \"MessageType\")",
                Long.class, ROOM_1
        );
        Long textMessages = jdbcTemplate.queryForObject(
                "select count(*) from public.messages where room_id = ? and type = cast('textMessage' as \"MessageType\")",
                Long.class, ROOM_1
        );
        org.assertj.core.api.Assertions.assertThat(missionMessages).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(textMessages).isEqualTo(1); // review textMessage
    }

    @Test
    void commitsWeightMissionWithoutReviewMessage() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("clientRequestId", UUID.randomUUID().toString());
        body.put("roomId", ROOM_1.toString());
        body.put("type", "weight");
        body.put("review", "오늘 체중");
        body.put("messageContent", "#체중 오늘 체중");
        body.put("feedImagePath", "feed/w.jpg");
        body.put("messageImagePath", "msg/w.jpg");
        body.put("weight", 65.5);

        mockMvc.perform(post("/api/v1/records/missions")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightId").isNotEmpty())
                .andExpect(jsonPath("$.feedId").doesNotExist());

        Double reportWeight = jdbcTemplate.queryForObject(
                "select weight from public.report where user_id = ? and date = ?",
                Double.class, USER_A, LocalDate.now(KST)
        );
        org.assertj.core.api.Assertions.assertThat(reportWeight).isEqualTo(65.5);

        // weight 타입은 review가 있어도 textMessage를 따로 만들지 않는다(기존 mission_complete와 동일).
        Long textMessages = jdbcTemplate.queryForObject(
                "select count(*) from public.messages where room_id = ? and type = cast('textMessage' as \"MessageType\")",
                Long.class, ROOM_1
        );
        org.assertj.core.api.Assertions.assertThat(textMessages).isEqualTo(0);
    }

    @Test
    void retryingSameClientRequestIdDoesNotDuplicate() throws Exception {
        String clientRequestId = UUID.randomUUID().toString();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("clientRequestId", clientRequestId);
        body.put("roomId", ROOM_1.toString());
        body.put("type", "lunch");
        body.put("review", "");
        body.put("messageContent", "#점심 맛점");
        body.put("feedImagePath", "feed/l.jpg");
        body.put("messageImagePath", "msg/l.jpg");
        body.put("calorie", 600);

        String requestBody = objectMapper.writeValueAsString(body);

        mockMvc.perform(post("/api/v1/records/missions")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/records/missions")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        Long feedCount = jdbcTemplate.queryForObject(
                "select count(*) from public.feed where user_id = ?", Long.class, USER_A
        );
        Long missionMessages = jdbcTemplate.queryForObject(
                "select count(*) from public.messages where room_id = ? and type = cast('missionMessage' as \"MessageType\")",
                Long.class, ROOM_1
        );
        org.assertj.core.api.Assertions.assertThat(feedCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(missionMessages).isEqualTo(1);
    }

    @Test
    void getReportReturnsSnapshotForDate() throws Exception {
        LocalDate today = LocalDate.now(KST);
        jdbcTemplate.update(
                "insert into public.report (user_id, date, breakfast, exercise) values (?, ?, ?, ?)",
                USER_A, today, 300L, 20L
        );

        mockMvc.perform(get("/api/v1/records/reports").param("date", today.toString())
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakfast").value(300))
                .andExpect(jsonPath("$.exercise").value(20))
                .andExpect(jsonPath("$.lunch").doesNotExist());
    }

    @Test
    void deletingMyFeedDecrementsReportAndRemovesRow() throws Exception {
        LocalDate today = LocalDate.now(KST);
        jdbcTemplate.update(
                "insert into public.report (user_id, date, breakfast) values (?, ?, ?)", USER_A, today, 450L
        );
        UUID feedId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into public.feed (id, user_id, type, calorie) values (?, ?, cast('breakfast' as \"FeedType\"), ?)",
                feedId, USER_A, 450L
        );

        mockMvc.perform(delete("/api/v1/records/feed/" + feedId)
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isNoContent());

        Long remaining = jdbcTemplate.queryForObject(
                "select count(*) from public.feed where id = ?", Long.class, feedId
        );
        Long reportBreakfast = jdbcTemplate.queryForObject(
                "select breakfast from public.report where user_id = ? and date = ?",
                Long.class, USER_A, today
        );
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(reportBreakfast).isEqualTo(0L);
    }

    @Test
    void deletingMyFeedFailsWhenReportWouldGoNegative() throws Exception {
        LocalDate today = LocalDate.now(KST);
        jdbcTemplate.update(
                "insert into public.report (user_id, date, breakfast) values (?, ?, ?)", USER_A, today, 100L
        );
        UUID feedId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into public.feed (id, user_id, type, calorie) values (?, ?, cast('breakfast' as \"FeedType\"), ?)",
                feedId, USER_A, 450L
        );

        mockMvc.perform(delete("/api/v1/records/feed/" + feedId)
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_ADJUSTMENT_FAILED"));

        Long remaining = jdbcTemplate.queryForObject(
                "select count(*) from public.feed where id = ?", Long.class, feedId
        );
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualTo(1);
    }

    @Test
    void deletingUnknownFeedReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/records/feed/" + UUID.randomUUID())
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
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
