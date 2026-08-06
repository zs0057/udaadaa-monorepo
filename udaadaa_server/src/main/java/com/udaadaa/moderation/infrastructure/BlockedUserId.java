package com.udaadaa.moderation.infrastructure;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class BlockedUserId implements Serializable {

    private UUID userId;
    private UUID blockUserId;

    protected BlockedUserId() {
    }

    BlockedUserId(UUID userId, UUID blockUserId) {
        this.userId = userId;
        this.blockUserId = blockUserId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockedUserId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(blockUserId, that.blockUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, blockUserId);
    }
}
