package com.udaadaa.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.jwt")
public record SupabaseJwtProperties(
        Mode mode,
        String issuer,
        String audience,
        String hmacSecret,
        String jwkSetUri
) {
    public SupabaseJwtProperties {
        mode = mode == null ? Mode.HMAC : mode;
        audience = audience == null || audience.isBlank() ? "authenticated" : audience;
    }

    public enum Mode {
        HMAC,
        JWKS
    }
}
