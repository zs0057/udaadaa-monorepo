package com.udaadaa.chat.presentation;

import com.udaadaa.chat.domain.RoomSummary;
import com.udaadaa.member.MemberId;
import com.udaadaa.member.MemberSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record RoomSummaryResponse(
        UUID id,
        String roomName,
        LocalDate startDay,
        LocalDate endDay,
        MessageSummaryResponse lastMessage,
        long myLastReadSequence,
        List<RoomMemberResponse> members
) {

    static RoomSummaryResponse from(RoomSummary room, Map<MemberId, MemberSummary> memberSummaries) {
        return new RoomSummaryResponse(
                room.id().value(),
                room.roomName(),
                room.startDay(),
                room.endDay(),
                room.lastMessage() == null ? null : MessageSummaryResponse.from(room.lastMessage()),
                room.myLastReadSequence(),
                room.participantIds().stream()
                        .map(memberId -> new RoomMemberResponse(
                                memberId.value(),
                                memberSummaries.containsKey(memberId)
                                        ? memberSummaries.get(memberId).nickname()
                                        : "정보 없음"
                        ))
                        .toList()
        );
    }
}
