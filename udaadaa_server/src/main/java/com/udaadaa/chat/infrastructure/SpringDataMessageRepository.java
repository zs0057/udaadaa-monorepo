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

    Optional<MessageJpaEntity> findByIdAndRoomId(UUID id, UUID roomId);

    /**
     * 채팅방 이미지 갤러리(CHT-이미지 목록)용 — imageMessage 타입만, 최신순.
     * type 컬럼이 native enum("MessageType")이라 파생 쿼리 대신 명시적 cast가 필요하다
     * (insertIfAbsent와 같은 이유).
     */
    @Query(value = """
            select * from public.messages
            where room_id = :roomId and type = cast('imageMessage' as "MessageType")
            order by sequence desc
            limit :limit
            """, nativeQuery = true)
    List<MessageJpaEntity> findRecentImageMessages(@Param("roomId") UUID roomId, @Param("limit") int limit);

    /**
     * 보낸 사람 본인만, 아직 삭제되지 않은 메시지만 소프트 삭제한다.
     * 영향받은 행이 0이면 없거나/다른 방이거나/본인 메시지가 아니거나/이미 삭제된 것.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update public.messages
            set is_deleted = true
            where id = :messageId and room_id = :roomId and user_id = :senderId and coalesce(is_deleted, false) = false
            """, nativeQuery = true)
    int markDeletedByOwner(
            @Param("messageId") UUID messageId,
            @Param("roomId") UUID roomId,
            @Param("senderId") UUID senderId
    );

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
