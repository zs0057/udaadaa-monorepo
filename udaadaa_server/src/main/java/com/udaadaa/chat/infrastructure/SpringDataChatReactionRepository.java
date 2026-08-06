package com.udaadaa.chat.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataChatReactionRepository extends JpaRepository<ChatReactionJpaEntity, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into public.chat_reactions (id, room_id, message_id, user_id, content)
            values (:id, :roomId, :messageId, :userId, :content)
            """, nativeQuery = true)
    void insert(
            @Param("id") UUID id,
            @Param("roomId") UUID roomId,
            @Param("messageId") UUID messageId,
            @Param("userId") UUID userId,
            @Param("content") String content
    );

    /**
     * 본인 것만 지운다(멱등 — 없거나 남의 것이면 0행).
     */
    long deleteByIdAndUserId(UUID id, UUID userId);
}
