package com.udaadaa.chat.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRoomParticipantRepository extends JpaRepository<RoomParticipantJpaEntity, RoomParticipantId> {

    boolean existsByRoomIdAndUserId(UUID roomId, UUID userId);
}
