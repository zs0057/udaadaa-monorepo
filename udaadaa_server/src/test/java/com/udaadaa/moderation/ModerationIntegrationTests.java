package com.udaadaa.moderation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.udaadaa.AbstractIntegrationTest;
import com.udaadaa.member.MemberId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
class ModerationIntegrationTests extends AbstractIntegrationTest {

    private static final UUID USER_A = UUID.fromString("4fa5a560-d4d2-41f3-b218-c84ac2a2f847");
    private static final UUID USER_B = UUID.fromString("a6c4cda5-a044-4f44-a75c-434d2592551d");
    private static final UUID USER_C = UUID.fromString("c1a4f6e2-6b8a-4c1a-9a2b-5f6d7e8f9a0b");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ModerationReader moderationReader;

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
                create table if not exists public.blocked_users (
                    created_at timestamp with time zone not null default now(),
                    user_id uuid not null,
                    block_user_id uuid not null,
                    primary key (user_id, block_user_id)
                )
                """);
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("delete from public.blocked_users");
        jdbcTemplate.update("delete from public.profiles");
        insertProfile(USER_A, "사용자 A");
        insertProfile(USER_B, "사용자 B");
        insertProfile(USER_C, "사용자 C");
    }

    @Test
    void blocksAnotherMemberIdempotently() throws Exception {
        mockMvc.perform(post("/api/v1/moderation/blocks")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedMemberId\":\"" + USER_B + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/moderation/blocks")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedMemberId\":\"" + USER_B + "\"}"))
                .andExpect(status().isNoContent());

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from public.blocked_users where user_id = ? and block_user_id = ?",
                Integer.class,
                USER_A,
                USER_B
        );
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsSelfBlock() throws Exception {
        mockMvc.perform(post("/api/v1/moderation/blocks")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedMemberId\":\"" + USER_A + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsBlockingNonExistentMember() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/moderation/blocks")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedMemberId\":\"" + unknown + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void unblocksIdempotently() throws Exception {
        insertBlock(USER_A, USER_B);

        mockMvc.perform(delete("/api/v1/moderation/blocks/" + USER_B)
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/moderation/blocks/" + USER_B)
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isNoContent());

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from public.blocked_users where user_id = ? and block_user_id = ?",
                Integer.class,
                USER_A,
                USER_B
        );
        org.assertj.core.api.Assertions.assertThat(count).isZero();
    }

    @Test
    void cannotUnblockAnotherMembersBlock() throws Exception {
        insertBlock(USER_A, USER_B);

        mockMvc.perform(delete("/api/v1/moderation/blocks/" + USER_A)
                        .header("Authorization", bearerToken(USER_B)));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from public.blocked_users where user_id = ? and block_user_id = ?",
                Integer.class,
                USER_A,
                USER_B
        );
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void listsOnlyMyBlockedMembers() throws Exception {
        insertBlock(USER_A, USER_B);
        insertBlock(USER_A, USER_C);
        insertBlock(USER_B, USER_C);

        mockMvc.perform(get("/api/v1/moderation/blocks")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedMemberIds.length()").value(2));
    }

    @Test
    void interactionStatusReflectsBothDirections() {
        insertBlock(USER_A, USER_B);
        insertBlock(USER_C, USER_A);

        var statuses = moderationReader.canInteractWith(
                MemberId.from(USER_A),
                Set.of(MemberId.from(USER_B), MemberId.from(USER_C))
        );

        org.assertj.core.api.Assertions.assertThat(statuses.get(MemberId.from(USER_B))).isFalse();
        org.assertj.core.api.Assertions.assertThat(statuses.get(MemberId.from(USER_C))).isFalse();
    }

    private void insertProfile(UUID id, String nickname) {
        jdbcTemplate.update(
                "insert into public.profiles (id, nickname) values (?, ?)",
                id,
                nickname
        );
    }

    private void insertBlock(UUID userId, UUID blockUserId) {
        jdbcTemplate.update(
                "insert into public.blocked_users (user_id, block_user_id) values (?, ?)",
                userId,
                blockUserId
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
