package com.udaadaa.chat.domain;

import com.udaadaa.chat.RoomId;
import com.udaadaa.member.MemberId;
import java.util.List;
import java.util.Optional;
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
     * roomId가 실제로 존재하는지 확인한다(참가 시도 시 존재하지 않는 방과 구분하기 위함).
     */
    boolean roomExists(RoomId roomId);

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

    /**
     * roomId에 messageId가 실제로 속하는지까지 확인해 메시지를 조회한다
     * (반응·삭제·숨김 API가 잘못된 방/메시지 조합을 막는 데 공용으로 쓴다).
     */
    Optional<MessageSummary> findMessageInRoom(RoomId roomId, UUID messageId);

    /**
     * memberId를 roomId의 참가자로 추가한다. 이미 참가 중이면 아무 것도 하지 않고 false를 반환한다.
     */
    boolean addParticipantIfAbsent(RoomId roomId, MemberId memberId);

    /**
     * memberId를 roomId에서 내보낸다(멱등 — 원래 참가자가 아니었어도 에러 없음).
     */
    void removeParticipant(RoomId roomId, MemberId memberId);

    /**
     * lastReadSequence가 기존 값보다 클 때만 갱신한다(뒤로 가는 값은 무시, 원자적 처리).
     */
    void updateReadPositionIfGreater(RoomId roomId, MemberId memberId, long lastReadSequence);

    /**
     * 메시지에 반응(이모지)을 추가한다. 기존 운영 데이터와 동일하게 중복 반응도 허용한다
     * (chat_reactions는 PK가 서로게이트 id뿐이라 원래도 막혀 있지 않았다).
     */
    UUID addReaction(RoomId roomId, UUID messageId, MemberId memberId, String content);

    /**
     * 본인이 남긴 반응만 삭제한다(멱등 — 없거나 남의 것이면 아무 것도 하지 않음).
     */
    void removeReaction(UUID reactionId, MemberId memberId);

    /**
     * 본인이 보낸 메시지를 소프트 삭제한다. 실제로 삭제 처리된 경우에만 true.
     */
    boolean markMessageDeletedByOwner(RoomId roomId, UUID messageId, MemberId senderId);

    /**
     * 내 화면에서만 이 메시지를 숨긴다(멱등, Moderation의 사용자 차단과는 별개 기능).
     */
    void hideMessage(RoomId roomId, UUID messageId, MemberId memberId);

    /**
     * 방 참가자 전원의 읽음 위치를 반환한다(메시지별 "안읽음 N명" 표시를 클라이언트가
     * 계산할 수 있도록 — 이 모듈은 카운트 자체를 계산해주지 않고 원본 위치만 내려준다).
     */
    List<ReadPosition> findReadPositions(RoomId roomId);
}
