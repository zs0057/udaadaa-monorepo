package com.udaadaa.chat.domain;

import com.udaadaa.chat.RoomId;
import com.udaadaa.member.MemberId;
import java.time.LocalDate;
import java.util.List;

/**
 * participantIds는 방 멤버 닉네임 표시를 위한 원본 ID 목록이다(닉네임 해석은
 * Member 모듈 소관이라 여기서는 하지 않는다 — ChatApplicationService가
 * MemberReader로 배치 조회해 API 응답에서 합친다).
 */
public record RoomSummary(
        RoomId id,
        String roomName,
        LocalDate startDay,
        LocalDate endDay,
        MessageSummary lastMessage,
        long myLastReadSequence,
        List<MemberId> participantIds
) {
}
