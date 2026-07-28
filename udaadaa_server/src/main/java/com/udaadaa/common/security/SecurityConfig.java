package com.udaadaa.common.security;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupabaseJwtProperties.class)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(SupabaseJwtProperties properties) {
        String issuer = required(properties.issuer(), "SUPABASE_JWT_ISSUER");
        NimbusJwtDecoder decoder = switch (properties.mode()) {
            case HMAC -> hmacDecoder(properties);
            case JWKS -> jwksDecoder(properties);
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new SupabaseAudienceValidator(properties.audience()),
                new SupabaseSubjectValidator()
        ));
        return decoder;
    }

    private NimbusJwtDecoder hmacDecoder(SupabaseJwtProperties properties) {
        String secret = required(properties.hmacSecret(), "SUPABASE_JWT_SECRET");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("SUPABASE_JWT_SECRET must contain at least 32 bytes");
        }
        SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                MacAlgorithm.HS256.getName()
        );
        return NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private NimbusJwtDecoder jwksDecoder(SupabaseJwtProperties properties) {
        String jwkSetUri = required(properties.jwkSetUri(), "SUPABASE_JWT_JWK_SET_URI");
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithms(algorithms -> {
                    algorithms.addAll(new LinkedHashSet<>(java.util.List.of(
                            SignatureAlgorithm.ES256,
                            SignatureAlgorithm.RS256
                    )));
                })
                .build();
    }

    private String required(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentVariable + " must be configured");
        }
        return value;
    }
}
