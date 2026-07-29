package com.udaadaa.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.udaadaa.AbstractIntegrationTest;
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
class MemberIntegrationTests extends AbstractIntegrationTest {

    private static final UUID USER_A = UUID.fromString("4fa5a560-d4d2-41f3-b218-c84ac2a2f847");
    private static final UUID USER_B = UUID.fromString("a6c4cda5-a044-4f44-a75c-434d2592551d");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemberReader memberReader;

    @BeforeAll
    void prepareProfilesTable() {
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
    }

    @BeforeEach
    void clearProfiles() {
        jdbcTemplate.update("delete from public.profiles");
    }

    @Test
    void returnsNotFoundWhenAuthenticatedUserHasNoProfile() throws Exception {
        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void initializesProfileIdempotentlyWithoutExposingNotificationFields() throws Exception {
        mockMvc.perform(post("/api/v1/members/me/initialize")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_A.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.fcmToken").doesNotExist())
                .andExpect(jsonPath("$.pushOption").doesNotExist());

        mockMvc.perform(post("/api/v1/members/me/initialize")
                        .header("Authorization", bearerToken(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_A.toString()));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from public.profiles where id = ?",
                Integer.class,
                USER_A
        );
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void updatesOnlyAuthenticatedMembersProfile() throws Exception {
        insertProfile(USER_A, "기존 사용자");
        insertProfile(USER_B, "다른 사용자");

        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"변경 사용자","height":170.5,"weight":65.2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_A.toString()))
                .andExpect(jsonPath("$.nickname").value("변경 사용자"))
                .andExpect(jsonPath("$.height").value(170.5))
                .andExpect(jsonPath("$.weight").value(65.2));

        String otherNickname = jdbcTemplate.queryForObject(
                "select nickname from public.profiles where id = ?",
                String.class,
                USER_B
        );
        org.assertj.core.api.Assertions.assertThat(otherNickname).isEqualTo("다른 사용자");
    }

    @Test
    void rejectsDuplicateNickname() throws Exception {
        insertProfile(USER_A, "사용자 A");
        insertProfile(USER_B, "중복 닉네임");

        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"중복 닉네임"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_ALREADY_EXISTS"));
    }

    @Test
    void rejectsEmptyOrOutOfRangeUpdate() throws Exception {
        insertProfile(USER_A, "사용자 A");

        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(patch("/api/v1/members/me")
                        .header("Authorization", bearerToken(USER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"height":5555}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readsMemberSummariesInOnePublicModuleContract() {
        insertProfile(USER_A, "사용자 A");
        insertProfile(USER_B, "사용자 B");

        var summaries = memberReader.findAllByIds(Set.of(MemberId.from(USER_A), MemberId.from(USER_B)));

        org.assertj.core.api.Assertions.assertThat(summaries)
                .hasSize(2)
                .containsKeys(MemberId.from(USER_A), MemberId.from(USER_B));
    }

    private void insertProfile(UUID id, String nickname) {
        jdbcTemplate.update(
                "insert into public.profiles (id, nickname) values (?, ?)",
                id,
                nickname
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
