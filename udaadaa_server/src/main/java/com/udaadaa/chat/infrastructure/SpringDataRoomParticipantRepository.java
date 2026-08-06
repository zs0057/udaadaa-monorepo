package com.udaadaa.chat.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRoomParticipantRepository extends JpaRepository<RoomParticipantJpaEntity, RoomParticipantId> {

    boolean existsByRoomIdAndUserId(UUID roomId, UUID userId);

    List<RoomParticipantJpaEntity> findByRoomId(UUID roomId);

    /**
     * 이미 참가 중이면 아무 것도 하지 않는다. 반환값이 0이면 이미 참가 중이었다는 뜻.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into public.room_participants (user_id, room_id)
            values (:userId, :roomId)
            on conflict (user_id, room_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("roomId") UUID roomId, @Param("userId") UUID userId);

    void deleteByRoomIdAndUserId(UUID roomId, UUID userId);

    /**
     * 뒤로 가는 값은 무시하도록 GREATEST로 원자적으로 갱신한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update public.room_participants
            set last_read_sequence = greatest(last_read_sequence, :lastReadSequence)
            where room_id = :roomId and user_id = :userId
            """, nativeQuery = true)
    void updateLastReadSequenceIfGreater(
            @Param("roomId") UUID roomId,
            @Param("userId") UUID userId,
            @Param("lastReadSequence") long lastReadSequence
    );
}
