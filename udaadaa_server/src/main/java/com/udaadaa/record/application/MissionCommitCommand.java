package com.udaadaa.record.application;

import com.udaadaa.record.domain.FeedType;
import java.util.UUID;

/**
 * feedImagePath/messageImagePath는 Flutter가 이미 각각 FeedImages/ImageMessages 버킷에
 * 업로드를 마친 뒤 넘기는 경로다(REC-01에서 결정한 대로 업로드 자체는 지금처럼 클라이언트가
 * 직접 한다 — Spring은 DB 기록만 원자적으로 처리한다). messageContent는 Flutter가 이미
 * "#아침 ..." 형태로 해시태그를 붙여 만든 채팅 메시지 본문이고, review는 별도 textMessage로
 * 이어붙일 리뷰 원문이다.
 */
public record MissionCommitCommand(
        UUID clientRequestId,
        UUID roomId,
        FeedType type,
        String review,
        String messageContent,
        String feedImagePath,
        String messageImagePath,
        Long calorie,
        Double weight,
        Long exerciseTime
) {
}
