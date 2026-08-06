package com.udaadaa.chat.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "blocked_messages", schema = "public")
@IdClass(BlockedMessageId.class)
class BlockedMessageJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    protected BlockedMessageJpaEntity() {
    }
}
