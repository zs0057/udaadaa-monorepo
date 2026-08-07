package com.udaadaa.record.domain;

/**
 * 기존 Flutter Calorie 모델(total_calories/items/ai_text)과 1:1 대응.
 */
public record CalorieEstimate(int totalCalories, String items, String aiText) {
}
