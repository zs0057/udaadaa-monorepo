package com.udaadaa.record.infrastructure;

import com.udaadaa.record.domain.CalorieEstimate;
import com.udaadaa.record.domain.CalorieEstimator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * REC-01: 기존에 이미 배포돼 있던 칼로리 추정 마이크로서비스(POST {baseUrl}/estimateCal)를
 * 서버-서버로 대리 호출한다. 이 서비스는 Phase 5에서 새로 만드는 게 아니라 기존에 Flutter가
 * dotenv API_URL로 직접 부르던 것과 완전히 동일한 엔드포인트다.
 */
@Component
class CalorieEstimateClient implements CalorieEstimator {

    private static final Logger log = LoggerFactory.getLogger(CalorieEstimateClient.class);

    private final CalorieApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    CalorieEstimateClient(CalorieApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public CalorieEstimate estimate(String base64Image, String description) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("CALORIE_API_URL(app.record.calorie-api.base-url)이 설정되지 않았습니다.");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "selectedImage", base64Image,
                    "description", description == null ? "" : description
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.baseUrl() + "/estimateCal"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(22))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "칼로리 추정 서비스 호출 실패: " + response.statusCode() + " " + response.body()
                );
            }

            JsonNode json = objectMapper.readTree(response.body());
            return new CalorieEstimate(
                    json.get("total_calories").asInt(),
                    json.get("items").asText(),
                    json.get("ai_text").asText()
            );
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("칼로리 추정 요청 처리 중 오류", e);
            throw new IllegalStateException("칼로리 추정 서비스 호출 중 오류가 발생했습니다.", e);
        }
    }
}
