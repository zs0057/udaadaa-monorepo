package com.udaadaa.chat.domain;

import com.udaadaa.chat.RoomId;
import com.udaadaa.member.MemberId;
import java.util.List;
import java.util.UUID;

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

    /**
     * 메시지를 저장한다. 같은 (roomId, clientMessageId) 조합이 이미 있으면 새로 만들지 않고
     * 기존 메시지를 그대로 반환한다(재전송 멱등 처리, CHT-03).
     */
    MessageSummary saveMessage(
            RoomId roomId,
            MemberId senderId,
            UUID clientMessageId,
            String type,
            String content,
            String imagePath
    );
}
