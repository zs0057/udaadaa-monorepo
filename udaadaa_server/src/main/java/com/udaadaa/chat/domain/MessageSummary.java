package com.udaadaa.chat.domain;

import com.udaadaa.chat.RoomId;
import com.udaadaa.member.MemberId;
import java.time.Instant;
import java.util.UUID;

public record MessageSummary(
        UUID id,
        RoomId roomId,
        MemberId senderId,
        String type,
        String content,
        String imagePath,
        long sequence,
        Instant createdAt,
        boolean isDeleted
) {
}
