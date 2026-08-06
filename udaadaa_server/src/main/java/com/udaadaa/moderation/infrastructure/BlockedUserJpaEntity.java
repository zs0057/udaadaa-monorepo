package com.udaadaa.moderation.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "blocked_users", schema = "public")
@IdClass(BlockedUserId.class)
class BlockedUserJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "block_user_id")
    private UUID blockUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BlockedUserJpaEntity() {
    }

    UUID userId() {
        return userId;
    }

    UUID blockUserId() {
        return blockUserId;
    }

    Instant createdAt() {
        return createdAt;
    }
}
