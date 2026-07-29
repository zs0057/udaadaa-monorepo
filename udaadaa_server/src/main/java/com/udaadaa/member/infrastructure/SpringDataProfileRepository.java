package com.udaadaa.member.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataProfileRepository extends JpaRepository<ProfileJpaEntity, UUID> {

    boolean existsByNicknameAndIdNot(String nickname, UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into public.profiles (id, nickname)
            values (:id, :nickname)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("nickname") String nickname);
}
