package com.udaadaa.moderation.infrastructure;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataBlockRepository extends JpaRepository<BlockedUserJpaEntity, BlockedUserId> {

    List<BlockedUserJpaEntity> findAllByUserId(UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into public.blocked_users (user_id, block_user_id)
            values (:userId, :blockUserId)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("blockUserId") UUID blockUserId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            delete from public.blocked_users
            where user_id = :userId and block_user_id = :blockUserId
            """, nativeQuery = true)
    void deleteByUserIdAndBlockUserId(@Param("userId") UUID userId, @Param("blockUserId") UUID blockUserId);

    @Query(value = """
            select block_user_id as id from public.blocked_users
            where user_id = :memberId and block_user_id in (:targetIds)
            union
            select user_id as id from public.blocked_users
            where block_user_id = :memberId and user_id in (:targetIds)
            """, nativeQuery = true)
    List<UUID> findBlockedEitherDirection(
            @Param("memberId") UUID memberId,
            @Param("targetIds") Set<UUID> targetIds
    );
}
