package com.udaadaa.notification.domain;

import java.util.List;
import java.util.Map;

public interface FcmSender {

    /**
     * 실패한 토큰이 있어도 예외를 던지지 않는다(호출부가 메시지 저장 흐름과 분리되어 있어야 함).
     */
    void sendToAll(List<String> tokens, String title, String body, Map<String, String> data);
}
