package com.udaadaa.record.presentation;

/**
 * 필드명이 selectedImage/description인 이유 — 기존 Flutter가 외부 칼로리 API로 보내던
 * 요청 바디(POST /estimateCal)와 그대로 맞추기 위함이다(REC-01: Spring은 대리 호출만 한다).
 */
public record CalorieEstimateRequest(String selectedImage, String description) {
}
