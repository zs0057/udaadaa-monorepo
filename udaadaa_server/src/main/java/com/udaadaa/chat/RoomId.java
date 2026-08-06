package com.udaadaa.chat;

import java.util.Objects;
import java.util.UUID;

public record RoomId(UUID value) {

    public RoomId {
        Objects.requireNonNull(value, "Room ID is required");
    }

    public static RoomId from(UUID value) {
        return new RoomId(value);
    }
}
