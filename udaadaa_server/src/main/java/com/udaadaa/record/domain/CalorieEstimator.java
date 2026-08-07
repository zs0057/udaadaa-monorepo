package com.udaadaa.record.domain;

/**
 * 기존에도 이미 배포돼 있던 외부 칼로리 추정 마이크로서비스(POST /estimateCal)를 호출하는 포트.
 * 지금까지는 Flutter가 dotenv API_URL로 직접 호출했지만, REC-01에 따라 Spring이 서버-서버로
 * 대리 호출한다 — 서비스 자체를 새로 만들거나 벤더를 바꾸는 게 아니다.
 */
public interface CalorieEstimator {
    /**
     * base64Image는 Flutter의 getBase64Image()가 만들던 것과 동일한 data URI 형식
     * ("data:image/jpeg;base64,...")이다 — Spring은 압축·인코딩을 다시 하지 않고 그대로
     * 외부 서비스에 전달만 한다.
     */
    CalorieEstimate estimate(String base64Image, String description);
}
