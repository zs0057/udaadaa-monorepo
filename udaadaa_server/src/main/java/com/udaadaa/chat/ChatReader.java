package com.udaadaa.chat;

import com.udaadaa.member.MemberId;
import java.util.UUID;

/**
 * Record 등 다른 모듈이 자신의 트랜잭션 안에서 채팅 메시지를 만들어야 할 때 쓰는 공개 기능
 * (Phase 5 REC-04). {@code ChallengeReader}와 대칭되는 역할 — 이번엔 Chat이 호출당하는 쪽이다.
 *
 * <p>스스로 새 트랜잭션을 열지 않는다(기본 {@code @Transactional} 전파는 REQUIRED) —
 * 호출부(Record의 미션 커밋 트랜잭션)에 참여해, feed/weight/report 기록과 메시지 생성이
 * 한쪽만 성공하는 상태를 막는다.
 */
public interface ChatReader {

    /**
     * missionMessage 1개를 저장하고, reviewText가 비어있지 않으면 이어서 textMessage 1개를
     * 더 저장한다. 기존 mission_complete DB 함수가 하던 것과 동일한 조합이다.
     *
     * <p>기존 함수는 두 메시지의 순서를 보장하려고 두 번째 메시지에 {@code now() + 1ms}를
     * 줬지만, Phase 3-1에서 추가된 {@code sequence}(DB 트리거로 자동 채번)가 이미 삽입 순서를
     * 보장하므로 그 트릭은 재현하지 않는다.
     *
     * <p>REST로는 노출되지 않는다 — {@code missionMessage}는
     * {@code ChatApplicationService.CREATABLE_MESSAGE_TYPES} 밖의 타입이라 일반
     * sendMessage API로는 만들 수 없고, 오직 이 내부 경로로만 만들어진다.
     */
    void recordMissionMessages(
            MemberId senderId,
            UUID roomId,
            String missionMessageContent,
            String missionMessageImagePath,
            String reviewText
    );
}
