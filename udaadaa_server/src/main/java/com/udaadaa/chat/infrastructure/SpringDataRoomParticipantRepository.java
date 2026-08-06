package com.udaadaa.chat.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRoomParticipantRepository extends JpaRepository<RoomParticipantJpaEntity, RoomParticipantId> {

    boolean existsByRoomIdAndUserId(UUID roomId, UUID userId);

    List<RoomParticipantJpaEntity> findByRoomId(UUID roomId);

    Optional<RoomParticipantJpaEntity> findByRoomIdAndUserId(UUID roomId, UUID userId);

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
     * 뒤로 가는 값(또는 동일한 값)은 무시하고, 실제로 전진했을 때만 갱신한다.
     * 반환값(영향받은 행 수)으로 실제 갱신 여부를 판단해 STOMP 브로드캐스트 여부를 결정한다
     * (읽음 위치가 그대로인데 매번 이벤트를 쏘지 않기 위함).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update public.room_participants
            set last_read_sequence = :lastReadSequence
            where room_id = :roomId and user_id = :userId and last_read_sequence < :lastReadSequence
            """, nativeQuery = true)
    int updateLastReadSequenceIfGreater(
            @Param("roomId") UUID roomId,
            @Param("userId") UUID userId,
            @Param("lastReadSequence") long lastReadSequence
    );
}
