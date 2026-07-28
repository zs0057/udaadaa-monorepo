package com.udaadaa;

import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIntegrationTest {

    protected static final String JWT_SECRET = "phase-zero-test-secret-must-be-at-least-32-bytes";
    protected static final String JWT_ISSUER = "https://test-project.supabase.co/auth/v1";
    protected static final String JWT_AUDIENCE = "authenticated";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("app.security.jwt.mode", () -> "hmac");
        registry.add("app.security.jwt.issuer", () -> JWT_ISSUER);
        registry.add("app.security.jwt.audience", () -> JWT_AUDIENCE);
        registry.add("app.security.jwt.hmac-secret", () -> JWT_SECRET);
    }
}
