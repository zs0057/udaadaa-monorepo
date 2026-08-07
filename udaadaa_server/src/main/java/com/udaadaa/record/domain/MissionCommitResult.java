package com.udaadaa.record.domain;

import java.util.UUID;

/**
 * 미션 커밋 결과. weight 타입이면 feedId가 null이고 weightId가 채워지고, 그 외 타입은 반대다
 * (기존 mission_complete DB 함수가 반환하던 단일 feed_id를 두 필드로 명시적으로 나눴다).
 */
public record MissionCommitResult(UUID feedId, UUID weightId) {
}
