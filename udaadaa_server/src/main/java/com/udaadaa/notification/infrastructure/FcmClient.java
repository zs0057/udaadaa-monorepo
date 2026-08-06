package com.udaadaa.notification.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.udaadaa.notification.domain.FcmSender;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 기존 message-push Edge Function이 하던 것과 같은 방식(Google 서비스 계정 JWT bearer로
 * OAuth2 access token을 받아 FCM HTTP v1 API 호출)을 Java로 재구현했다.
 *
 * <p>이 자격 증명(client_email/private_key)은 Supabase service_role 키와는 완전히 별개의
 * Google Cloud 서비스 계정이다 — 지금 진행 중인 service_role 키 유출 정리와는 무관하다.
 */
@Component
class FcmClient implements FcmSender {

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(5);

    private final FcmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile String cachedAccessToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    FcmClient(FcmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void sendToAll(List<String> tokens, String title, String body, Map<String, String> data) {
        if (!properties.enabled() || !properties.isConfigured()) {
            log.debug("FCM push skipped (disabled or credentials not configured), {} recipient(s)", tokens.size());
            return;
        }

        String accessToken;
        try {
            accessToken = accessToken();
        } catch (Exception e) {
            log.warn("Failed to obtain FCM access token, skipping push to {} recipient(s)", tokens.size(), e);
            return;
        }

        for (String token : tokens) {
            try {
                send(token, title, body, data, accessToken);
            } catch (Exception e) {
                // 토큰 하나가 실패해도(만료된 기기 토큰 등) 나머지는 계속 보낸다.
                log.warn("Failed to send FCM push to a device token", e);
            }
        }
    }

    private void send(String token, String title, String body, Map<String, String> data, String accessToken)
            throws Exception {
        Map<String, Object> message = Map.of(
                "message", Map.of(
                        "token", token,
                        "notification", Map.of("title", title, "body", body),
                        "data", data
                )
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://fcm.googleapis.com/v1/projects/%s/messages:send".formatted(properties.projectId())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(message)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("FCM send failed with status {}: {}", response.statusCode(), response.body());
        }
    }

    private synchronized String accessToken() throws Exception {
        if (cachedAccessToken != null && Instant.now().isBefore(cachedTokenExpiry.minus(TOKEN_SAFETY_MARGIN))) {
            return cachedAccessToken;
        }

        String assertion = signedJwtAssertion();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Failed to obtain Google OAuth2 token: " + response.statusCode() + " " + response.body()
            );
        }

        JsonNode json = objectMapper.readTree(response.body());
        String token = json.get("access_token").asText();
        long expiresInSeconds = json.path("expires_in").asLong(3600);

        cachedAccessToken = token;
        cachedTokenExpiry = Instant.now().plusSeconds(expiresInSeconds);
        return token;
    }

    private String signedJwtAssertion() throws Exception {
        RSAPrivateKey privateKey = parsePrivateKey(properties.privateKey());
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.clientEmail())
                .subject(properties.clientEmail())
                .audience(TOKEN_ENDPOINT)
                .claim("scope", FCM_SCOPE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        signedJwt.sign(new RSASSASigner(privateKey));
        return signedJwt.serialize();
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            String normalized = pem
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(normalized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid FCM service account private key", e);
        }
    }
}
