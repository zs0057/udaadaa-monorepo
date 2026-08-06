package com.udaadaa.chat;

import com.udaadaa.member.MemberId;
import java.util.UUID;

/**
 * 메시지 저장 트랜잭션이 커밋된 뒤 발행되는 내부 이벤트.
 *
 * <p>Chat 내부의 STOMP 브로드캐스트뿐 아니라 3-4에서 Notification 모듈도 이 이벤트를 구독할
 * 예정이므로, 내부 전용 타입(MessageSummary 등)을 담지 않고 원시 값·공개 ID 타입만 담는다.
 */
public record ChatMessageCreated(
        UUID messageId,
        RoomId roomId,
        MemberId senderId,
        String type,
        String content,
        String imagePath,
        long sequence
) {
}
