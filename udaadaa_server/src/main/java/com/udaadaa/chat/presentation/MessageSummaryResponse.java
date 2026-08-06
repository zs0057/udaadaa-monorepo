package com.udaadaa.chat.presentation;

import com.udaadaa.chat.domain.MessageSummary;
import java.time.Instant;
import java.util.UUID;

record MessageSummaryResponse(
        UUID id,
        UUID roomId,
        UUID senderId,
        String type,
        String content,
        String imagePath,
        long sequence,
        Instant createdAt,
        boolean isDeleted
) {

    static MessageSummaryResponse from(MessageSummary message) {
        return new MessageSummaryResponse(
                message.id(),
                message.roomId().value(),
                message.senderId().value(),
                message.type(),
                message.content(),
                message.imagePath(),
                message.sequence(),
                message.createdAt(),
                message.isDeleted()
        );
    }
}
