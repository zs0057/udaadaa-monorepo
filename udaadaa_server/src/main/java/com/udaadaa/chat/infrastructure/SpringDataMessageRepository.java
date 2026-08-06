package com.udaadaa.chat.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMessageRepository extends JpaRepository<MessageJpaEntity, UUID> {

    Optional<MessageJpaEntity> findFirstByRoomIdOrderBySequenceDesc(UUID roomId);

    List<MessageJpaEntity> findByRoomIdAndSequenceGreaterThanOrderBySequenceAsc(
            UUID roomId,
            long afterSequence,
            Pageable pageable
    );

    Optional<MessageJpaEntity> findByRoomIdAndClientMessageId(UUID roomId, UUID clientMessageId);

    /**
     * client_message_id가 이미 존재하면(재전송) 아무 것도 하지 않는다(CHT-03 멱등 처리).
     * sequence는 DB 트리거(assign_message_sequence)가 자동으로 채운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into public.messages (id, room_id, user_id, content, type, image_path, client_message_id)
            values (gen_random_uuid(), :roomId, :userId, :content, cast(:type as "MessageType"), :imagePath, :clientMessageId)
            on conflict (room_id, client_message_id) where client_message_id is not null do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("roomId") UUID roomId,
            @Param("userId") UUID userId,
            @Param("content") String content,
            @Param("type") String type,
            @Param("imagePath") String imagePath,
            @Param("clientMessageId") UUID clientMessageId
    );
}
