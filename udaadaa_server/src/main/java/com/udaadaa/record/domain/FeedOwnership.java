package com.udaadaa.record.domain;

import com.udaadaa.member.MemberId;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 내 피드 삭제(REC-07) 시 소유권 확인과 report 차감에 필요한 최소 정보.
 * kstDay는 (created_at at time zone 'Asia/Seoul')::date — 이 피드가 어느 report.date 행에
 * 더해졌었는지를 나타낸다(생성 당시와 동일한 타임존 규칙으로 역산해야 정확히 상쇄된다).
 */
public record FeedOwnership(UUID id, MemberId userId, FeedType type, Long calorie, LocalDate kstDay) {
}
