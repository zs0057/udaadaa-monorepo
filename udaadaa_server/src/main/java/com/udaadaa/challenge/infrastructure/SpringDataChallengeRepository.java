package com.udaadaa.challenge.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataChallengeRepository extends JpaRepository<ChallengeJpaEntity, UUID> {

    /**
     * end_day가 오늘 이후(오늘 포함)인 참여 중 가장 최근에 시작한 것 하나만 반환한다.
     */
    @Query(value = """
            select * from public.challenge
            where user_id = :userId and end_day >= :today
            order by start_day desc
            limit 1
            """, nativeQuery = true)
    Optional<ChallengeJpaEntity> findCurrent(@Param("userId") UUID userId, @Param("today") LocalDate today);

    @Query(value = """
            select * from public.challenge
            where user_id = :userId and end_day < :today
            order by start_day asc
            """, nativeQuery = true)
    List<ChallengeJpaEntity> findFinished(@Param("userId") UUID userId, @Param("today") LocalDate today);

    /**
     * (user_id, room_id) 부분 유니크 인덱스(room_id is not null)를 대상으로 멱등 insert한다(CHA-03).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into public.challenge (id, user_id, start_day, end_day, room_id, is_success)
            values (gen_random_uuid(), :userId, :startDay, :endDay, :roomId, false)
            on conflict (user_id, room_id) where room_id is not null do nothing
            """, nativeQuery = true)
    int insertForRoomIfAbsent(
            @Param("userId") UUID userId,
            @Param("roomId") UUID roomId,
            @Param("startDay") LocalDate startDay,
            @Param("endDay") LocalDate endDay
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into public.challenge (id, user_id, start_day, end_day, is_success)
            values (gen_random_uuid(), :userId, :startDay, :endDay, false)
            """, nativeQuery = true)
    void insertGeneral(
            @Param("userId") UUID userId,
            @Param("startDay") LocalDate startDay,
            @Param("endDay") LocalDate endDay
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update public.challenge set is_success = true where id = :id", nativeQuery = true)
    void markSuccess(@Param("id") UUID id);
}
