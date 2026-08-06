package com.udaadaa.chat.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages", schema = "public")
class MessageJpaEntity {

    @Id
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    private String content;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "is_deleted")
    private Boolean isDeleted;

    @Column(name = "sequence")
    private Long sequence;

    @Column(name = "client_message_id")
    private UUID clientMessageId;

    protected MessageJpaEntity() {
    }

    UUID id() {
        return id;
    }

    UUID roomId() {
        return roomId;
    }

    UUID userId() {
        return userId;
    }

    String content() {
        return content;
    }

    String type() {
        return type;
    }

    String imagePath() {
        return imagePath;
    }

    Instant createdAt() {
        return createdAt;
    }

    boolean isDeleted() {
        return Boolean.TRUE.equals(isDeleted);
    }

    long sequence() {
        return sequence == null ? 0L : sequence;
    }
}
