package com.udaadaa.record.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.record.calorie-api")
public record CalorieApiProperties(String baseUrl) {

    boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
