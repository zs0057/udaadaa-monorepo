package com.udaadaa.notification.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notification.fcm")
public record FcmProperties(
        boolean enabled,
        String projectId,
        String clientEmail,
        String privateKey
) {
    public FcmProperties {
        projectId = (projectId == null || projectId.isBlank()) ? "udaadaa" : projectId;
    }

    /**
     * 자격 증명이 아직 설정되지 않은 환경(예: 이 전환 초기 단계)에서는
     * enabled=true여도 조용히 아무 것도 보내지 않도록 하기 위한 확인.
     */
    boolean isConfigured() {
        return clientEmail != null && !clientEmail.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
