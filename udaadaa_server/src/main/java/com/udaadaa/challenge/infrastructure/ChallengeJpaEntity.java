package com.udaadaa.challenge.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "challenge", schema = "public")
class ChallengeJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "start_day", nullable = false)
    private LocalDate startDay;

    @Column(name = "end_day", nullable = false)
    private LocalDate endDay;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "is_success", nullable = false)
    private boolean success;

    protected ChallengeJpaEntity() {
    }

    UUID id() {
        return id;
    }

    UUID userId() {
        return userId;
    }

    LocalDate startDay() {
        return startDay;
    }

    LocalDate endDay() {
        return endDay;
    }

    UUID roomId() {
        return roomId;
    }

    boolean success() {
        return success;
    }
}
