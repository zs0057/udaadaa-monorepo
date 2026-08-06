package com.udaadaa.chat.presentation.websocket;

import com.udaadaa.chat.ChatMessageCreated;
import java.util.UUID;

/**
 * {@code eventType}은 같은 {@code /topic/rooms/{roomId}} 토픽에 여러 종류의 이벤트를
 * 실어야 해서(3-3 이후: 읽음 위치 갱신도 추가) 클라이언트가 구분할 수 있게 붙인 판별 필드다.
 * 이 필드가 없거나 다른 값이면 클라이언트는 안전하게 "message"로 간주한다(하위 호환).
 */
record ChatMessageBroadcastPayload(
        String eventType,
        UUID id,
        UUID roomId,
        UUID senderId,
        String type,
        String content,
        String imagePath,
        long sequence
) {

    static ChatMessageBroadcastPayload from(ChatMessageCreated event) {
        return new ChatMessageBroadcastPayload(
                "message",
                event.messageId(),
                event.roomId().value(),
                event.senderId().value(),
                event.type(),
                event.content(),
                event.imagePath(),
                event.sequence()
        );
    }
}
