package com.udaadaa.chat.presentation.websocket;

import com.udaadaa.chat.ReadPositionUpdated;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ReadPositionUpdated(읽음 위치가 실제로 전진했을 때만 발행됨)를 구독해 같은 방 참가자에게
 * 실시간으로 전달한다. ChatMessageBroadcaster와 같은 토픽({@code /topic/rooms/{roomId}})을
 * 공유하고, {@code eventType}으로만 구분한다 — 방마다 구독을 하나만 유지하면 되도록.
 *
 * <p>AFTER_COMMIT에서만 동작한다(ChatMessageBroadcaster와 동일한 원칙).
 */
@Component
class ReadPositionBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    ReadPositionBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onReadPositionUpdated(ReadPositionUpdated event) {
        messagingTemplate.convertAndSend(
                "/topic/rooms/" + event.roomId().value(),
                ReadPositionBroadcastPayload.from(event)
        );
    }
}
