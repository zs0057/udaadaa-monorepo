package com.udaadaa.chat.presentation.websocket;

import com.udaadaa.chat.ChatMessageCreated;
import java.util.UUID;

record ChatMessageBroadcastPayload(
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
