package com.udaadaa.chat.domain;

import com.udaadaa.chat.RoomId;
import com.udaadaa.member.MemberId;
import java.util.List;

public interface ChatRepository {

    /**
     * memberId가 참가 중인 방 목록을 마지막 메시지와 함께 반환한다.
     */
    List<RoomSummary> findRoomSummariesForMember(MemberId memberId);

    /**
     * memberId가 roomId의 참가자인지 확인한다. Spring DB Role은 RLS를 우회하므로
     * (BYPASSRLS) 이 확인을 애플리케이션에서 반드시 직접 수행해야 한다.
     */
    boolean isParticipant(RoomId roomId, MemberId memberId);

    /**
     * afterSequence보다 큰 순번의 메시지를 오름차순으로 최대 limit개 반환한다.
     */
    List<MessageSummary> findMessagesAfter(RoomId roomId, long afterSequence, int limit);
}
