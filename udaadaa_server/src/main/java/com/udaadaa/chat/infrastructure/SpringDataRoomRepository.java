package com.udaadaa.chat.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRoomRepository extends JpaRepository<RoomJpaEntity, UUID> {

    @Query("""
            select r from RoomJpaEntity r
            where r.id in (
                select rp.roomId from RoomParticipantJpaEntity rp where rp.userId = :memberId
            )
            """)
    List<RoomJpaEntity> findRoomsForMember(@Param("memberId") UUID memberId);
}
