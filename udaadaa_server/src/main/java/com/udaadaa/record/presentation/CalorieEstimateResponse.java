package com.udaadaa.record.presentation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.udaadaa.record.domain.CalorieEstimate;

/**
 * 필드를 camelCase가 아니라 외부 칼로리 서비스 응답과 같은 snake_case(total_calories/items/
 * ai_text)로 직렬화한다 — Flutter의 Calorie.fromJson()이 이 키를 그대로 기대하기 때문에,
 * Spring이 대리 호출을 끼워 넣어도 Flutter 쪽 파싱 코드를 바꿀 필요가 없도록 했다.
 */
public record CalorieEstimateResponse(
        @JsonProperty("total_calories") int totalCalories,
        String items,
        @JsonProperty("ai_text") String aiText
) {
    public static CalorieEstimateResponse from(CalorieEstimate estimate) {
        return new CalorieEstimateResponse(estimate.totalCalories(), estimate.items(), estimate.aiText());
    }
}
