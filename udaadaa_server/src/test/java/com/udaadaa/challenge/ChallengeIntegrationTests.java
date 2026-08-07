package com.udaadaa.challenge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.udaadaa.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ChallengeIntegrationTests extends AbstractIntegrationTest {

    private static final UUID USER_A = UUID.fromString("4fa5a560-d4d2-41f3-b218-c84ac2a2f847");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void prepareTables() {
        jdbcTemplate.execute("""
                do $$
                begin
                    if not exists (select 1 from pg_type where typname = 'FeedType') then
                        create type "FeedType" as enum ('breakfast', 'lunch', 'dinner', 'snack', 'exercise', 'weight');
                    end if;
                end $$
                """);
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
        // 다른 테스트 클래스(Chat)와 같은 컨테이너를 공유할 수 있어 컬럼을 동일하게 맞춘다.
        jdbcTemplate.execute("""
                create table if not exists public.challenge (
                    id uuid primary key,
                    created_at timestamp with time zone not null default now(),
                    start_day date not null,
                    end_day date not null,
                    user_id uuid not null,
                    is_success boolean not null default false,
                    room_id uuid
                )
                """);
        jdbcTemplate.execute("""
                create unique index if not exists challenge_user_id_room_id_key
                    on public.challenge (user_id, room_id) where room_id is not null
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
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("delete from public.challenge");
        jdbcTemplate.update("delete from public.feed");
        jdbcTemplate.update("delete from public.weight");
        jdbcTemplate.update("delete from public.profiles");
        jdbcTemplate.update("insert into public.profiles (id, nickname) values (?, ?)", USER_A, "사용자 A");
    }

    @Test
    void returnsNotParticipatingWhenNoActiveChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/challenges/me")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participating").value(false));
    }

    @Test
    void entersGeneralChallengeForFourteenDays() throws Exception {
        mockMvc.perform(post("/api/v1/challenges")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isNoContent());

        LocalDate today = LocalDate.now(KST);
        mockMvc.perform(get("/api/v1/challenges/me")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participating").value(true))
                .andExpect(jsonPath("$.startDay").value(today.toString()))
                .andExpect(jsonPath("$.endDay").value(today.plusDays(13).toString()));
    }

    @Test
    void rejectsEnteringGeneralChallengeWhenAlreadyActive() throws Exception {
        mockMvc.perform(post("/api/v1/challenges")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/challenges")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHALLENGING"));
    }

    @Test
    void computesCompletedDaysAndStreakFromFeedAndWeight() throws Exception {
        LocalDate today = LocalDate.now(KST);
        LocalDate start = today.minusDays(2);
        insertChallenge(USER_A, start, start.plusDays(13), null, false);

        // start, start+1(=어제)는 조건 충족(feed>=2 & weight>=1), 오늘은 아직 미충족.
        completeDay(start);
        completeDay(start.plusDays(1));
        insertFeed(USER_A, today, "breakfast"); // 오늘은 feed 1건뿐 — 조건 미충족(2건 필요)

        mockMvc.perform(get("/api/v1/challenges/me")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participating").value(true))
                .andExpect(jsonPath("$.completedDays").value(2))
                .andExpect(jsonPath("$.consecutiveDays").value(2))
                .andExpect(jsonPath("$.todayCompleted").value(false))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.todayFeedCount").value(1))
                .andExpect(jsonPath("$.todayWeightCount").value(0));
    }

    @Test
    void excludesExerciseFeedFromCompletionCount() throws Exception {
        LocalDate today = LocalDate.now(KST);
        insertChallenge(USER_A, today, today.plusDays(13), null, false);

        // exercise 타입 2건 + weight 1건 — feed 조건은 "exercise 제외 2건 이상"이라 미충족이어야 한다.
        insertFeed(USER_A, today, "exercise");
        insertFeed(USER_A, today, "exercise");
        insertWeight(USER_A, today);

        mockMvc.perform(get("/api/v1/challenges/me")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayCompleted").value(false));
    }

    @Test
    void marksSuccessWhenStreakThresholdMetOnLastDay() throws Exception {
        LocalDate today = LocalDate.now(KST);
        LocalDate start = today.minusDays(13); // 14일 챌린지의 마지막 날 = 오늘
        insertChallenge(USER_A, start, today, null, false);

        for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
            completeDay(date);
        }

        mockMvc.perform(get("/api/v1/challenges/me")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consecutiveDays").value(13))
                .andExpect(jsonPath("$.todayCompleted").value(true))
                .andExpect(jsonPath("$.success").value(true));

        Boolean persisted = jdbcTemplate.queryForObject(
                "select is_success from public.challenge where user_id = ?", Boolean.class, USER_A
        );
        org.assertj.core.api.Assertions.assertThat(persisted).isTrue();
    }

    @Test
    void historyReturnsOnlyFinishedChallenges() throws Exception {
        LocalDate today = LocalDate.now(KST);
        insertChallenge(USER_A, today.minusDays(30), today.minusDays(17), null, true);
        insertChallenge(USER_A, today, today.plusDays(13), null, false);

        mockMvc.perform(get("/api/v1/challenges/me/history")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].success").value(true));
    }

    private void completeDay(LocalDate day) {
        insertFeed(USER_A, day, "breakfast");
        insertFeed(USER_A, day, "lunch");
        insertWeight(USER_A, day);
    }

    private void insertChallenge(UUID userId, LocalDate start, LocalDate end, UUID roomId, boolean success) {
        jdbcTemplate.update(
                "insert into public.challenge (id, user_id, start_day, end_day, room_id, is_success) "
                        + "values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, start, end, roomId, success
        );
    }

    private void insertFeed(UUID userId, LocalDate day, String type) {
        jdbcTemplate.update(
                "insert into public.feed (id, user_id, created_at, type) values (?, ?, ?, cast(? as \"FeedType\"))",
                UUID.randomUUID(), userId, atKst(day), type
        );
    }

    private void insertWeight(UUID userId, LocalDate day) {
        jdbcTemplate.update(
                "insert into public.weight (id, user_id, created_at, weight, date) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, atKst(day), 70.0, day
        );
    }

    private OffsetDateTime atKst(LocalDate day) {
        return day.atTime(10, 0).atZone(KST).toOffsetDateTime();
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
