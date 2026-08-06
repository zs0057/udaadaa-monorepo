package com.udaadaa.chat.infrastructure;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class RoomParticipantId implements Serializable {

    private UUID userId;
    private UUID roomId;

    protected RoomParticipantId() {
    }

    RoomParticipantId(UUID userId, UUID roomId) {
        this.userId = userId;
        this.roomId = roomId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoomParticipantId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(roomId, that.roomId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, roomId);
    }
}
