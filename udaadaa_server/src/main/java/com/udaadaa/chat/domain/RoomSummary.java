package com.udaadaa.chat.domain;

import com.udaadaa.chat.RoomId;
import java.time.LocalDate;

public record RoomSummary(
        RoomId id,
        String roomName,
        LocalDate startDay,
        LocalDate endDay,
        MessageSummary lastMessage,
        long myLastReadSequence
) {
}
