package com.udaadaa.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.udaadaa.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthSecurityIntegrationTests extends AbstractIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("1f8b4705-3a77-4ac7-a01e-b6b970ceacdf");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsValidSupabaseJwt() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token(JWT_ISSUER, JWT_AUDIENCE, USER_ID.toString(), 300)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token(JWT_ISSUER, JWT_AUDIENCE, USER_ID.toString(), -60)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenWithInvalidSignature() throws Exception {
        String invalidSecret = "different-test-secret-must-be-at-least-32-bytes";

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer "
                                + token(JWT_ISSUER, JWT_AUDIENCE, USER_ID.toString(), 300, invalidSecret)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void internalActuatorEndpointsAreNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer "
                                + token(JWT_ISSUER, JWT_AUDIENCE, USER_ID.toString(), 300)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsWrongIssuerAudienceAndSubject() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token("https://invalid.example", JWT_AUDIENCE, USER_ID.toString(), 300)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token(JWT_ISSUER, "wrong", USER_ID.toString(), 300)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token(JWT_ISSUER, JWT_AUDIENCE, "not-a-uuid", 300)))
                .andExpect(status().isUnauthorized());
    }

    private String token(String issuer, String audience, String subject, long expiresInSeconds) {
        return token(issuer, audience, subject, expiresInSeconds, JWT_SECRET);
    }

    private String token(
            String issuer,
            String audience,
            String subject,
            long expiresInSeconds,
            String secret
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expiresInSeconds);
        Instant issuedAt = expiresInSeconds < 0 ? expiresAt.minusSeconds(60) : now.minusSeconds(5);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", "authenticated")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8))
        );
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
