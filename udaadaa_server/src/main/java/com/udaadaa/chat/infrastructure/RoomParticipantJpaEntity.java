package com.udaadaa.chat.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "room_participants", schema = "public")
@IdClass(RoomParticipantId.class)
class RoomParticipantJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "push_option", nullable = false)
    private boolean pushOption;

    protected RoomParticipantJpaEntity() {
    }

    UUID userId() {
        return userId;
    }

    UUID roomId() {
        return roomId;
    }
}
