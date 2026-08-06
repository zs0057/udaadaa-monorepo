package com.udaadaa.chat.infrastructure;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class BlockedMessageId implements Serializable {

    private UUID userId;
    private UUID messageId;

    protected BlockedMessageId() {
    }

    BlockedMessageId(UUID userId, UUID messageId) {
        this.userId = userId;
        this.messageId = messageId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockedMessageId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, messageId);
    }
}
