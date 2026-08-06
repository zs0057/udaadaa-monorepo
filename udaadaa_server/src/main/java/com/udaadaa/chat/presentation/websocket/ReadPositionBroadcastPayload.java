package com.udaadaa.chat.presentation.websocket;

import com.udaadaa.chat.ReadPositionUpdated;
import java.util.UUID;

record ReadPositionBroadcastPayload(
        String eventType,
        UUID roomId,
        UUID memberId,
        long lastReadSequence
) {

    static ReadPositionBroadcastPayload from(ReadPositionUpdated event) {
        return new ReadPositionBroadcastPayload(
                "readPosition",
                event.roomId().value(),
                event.memberId().value(),
                event.lastReadSequence()
        );
    }
}
