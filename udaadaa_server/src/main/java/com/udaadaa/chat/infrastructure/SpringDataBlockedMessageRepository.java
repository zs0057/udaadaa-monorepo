package com.udaadaa.chat.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataBlockedMessageRepository extends JpaRepository<BlockedMessageJpaEntity, BlockedMessageId> {

    @Query("select b.messageId from BlockedMessageJpaEntity b where b.userId = :userId and b.messageId in :messageIds")
    List<UUID> findHiddenMessageIds(@Param("userId") UUID userId, @Param("messageIds") Collection<UUID> messageIds);

    /**
     * 이미 숨긴 메시지면 아무 것도 하지 않는다(멱등).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into public.blocked_messages (user_id, message_id, room_id)
            values (:userId, :messageId, :roomId)
            on conflict (user_id, message_id) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(
            @Param("userId") UUID userId,
            @Param("messageId") UUID messageId,
            @Param("roomId") UUID roomId
    );
}
