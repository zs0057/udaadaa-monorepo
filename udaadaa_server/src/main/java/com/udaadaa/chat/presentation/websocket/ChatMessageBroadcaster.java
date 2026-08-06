package com.udaadaa.chat.presentation.websocket;

import com.udaadaa.chat.ChatMessageCreated;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ChatMessageCreated를 구독해 같은 방을 구독 중인 클라이언트에게 STOMP로 전달한다.
 *
 * <p>AFTER_COMMIT에서만 동작하므로, 메시지 저장 트랜잭션이 롤백되면 아무것도 보내지 않는다.
 * (로드맵 §7 "저장 커밋 이후 내부 이벤트 → STOMP 전달" 원칙)
 */
@Component
class ChatMessageBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    ChatMessageBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChatMessageCreated(ChatMessageCreated event) {
        messagingTemplate.convertAndSend(
                "/topic/rooms/" + event.roomId().value(),
                ChatMessageBroadcastPayload.from(event)
        );
    }
}
