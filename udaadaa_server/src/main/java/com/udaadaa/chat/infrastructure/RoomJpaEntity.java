package com.udaadaa.chat.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "rooms", schema = "public")
class RoomJpaEntity {

    @Id
    private UUID id;

    @Column(name = "room_name", nullable = false)
    private String roomName;

    @Column(name = "start_day")
    private LocalDate startDay;

    @Column(name = "end_day")
    private LocalDate endDay;

    protected RoomJpaEntity() {
    }

    UUID id() {
        return id;
    }

    String roomName() {
        return roomName;
    }

    LocalDate startDay() {
        return startDay;
    }

    LocalDate endDay() {
        return endDay;
    }
}
