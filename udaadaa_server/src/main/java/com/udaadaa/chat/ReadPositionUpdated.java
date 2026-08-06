package com.udaadaa.chat;

import com.udaadaa.member.MemberId;

/**
 * 참가자의 읽음 위치(lastReadSequence)가 실제로 전진했을 때만 발행되는 내부 이벤트.
 *
 * <p>{@code updateReadPositionIfGreater}가 false(변화 없음)를 반환한 호출에는 발행되지 않는다
 * — 같은 값 또는 뒤로 가는 값을 반복 전송해도 매번 STOMP 브로드캐스트가 나가지 않게 하기 위함.
 *
 * <p>{@link ChatMessageCreated}와 같은 이유로 원시 값·공개 ID 타입만 담는다(구독자가 Chat
 * 내부 타입에 의존하지 않도록).
 */
public record ReadPositionUpdated(
        RoomId roomId,
        MemberId memberId,
        long lastReadSequence
) {
}
