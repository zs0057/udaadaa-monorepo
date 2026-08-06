package com.udaadaa.chat.presentation;

import com.udaadaa.chat.domain.RoomSummary;
import java.time.LocalDate;
import java.util.UUID;

record RoomSummaryResponse(
        UUID id,
        String roomName,
        LocalDate startDay,
        LocalDate endDay,
        MessageSummaryResponse lastMessage
) {

    static RoomSummaryResponse from(RoomSummary room) {
        return new RoomSummaryResponse(
                room.id().value(),
                room.roomName(),
                room.startDay(),
                room.endDay(),
                room.lastMessage() == null ? null : MessageSummaryResponse.from(room.lastMessage())
        );
    }
}
