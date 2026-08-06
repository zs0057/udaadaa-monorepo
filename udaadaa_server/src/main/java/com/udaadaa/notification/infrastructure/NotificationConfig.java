package com.udaadaa.notification.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * CHT-01 이후 이 프로젝트에 처음 등장하는 @Async 사용처.
 * FCM 발송(외부 네트워크 호출)이 메시지 저장 API의 응답 시간에 영향을 주지 않도록
 * ChatPushNotificationListener를 별도 스레드에서 실행하기 위해 필요하다.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(FcmProperties.class)
class NotificationConfig {
}
