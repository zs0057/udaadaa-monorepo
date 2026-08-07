package com.udaadaa.challenge.infrastructure;

import com.udaadaa.challenge.domain.ChallengeParticipation;
import com.udaadaa.challenge.domain.ChallengeRepository;
import com.udaadaa.member.MemberId;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JpaChallengeRepository implements ChallengeRepository {

    private final SpringDataChallengeRepository challengeRepository;
    private final JdbcTemplate jdbcTemplate;

    JpaChallengeRepository(SpringDataChallengeRepository challengeRepository, JdbcTemplate jdbcTemplate) {
        this.challengeRepository = challengeRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ChallengeParticipation> findCurrentByMemberId(MemberId memberId, LocalDate today) {
        return challengeRepository.findCurrent(memberId.value(), today).map(this::toParticipation);
    }

    @Override
    public void insertForRoomIfAbsent(MemberId memberId, UUID roomId, LocalDate startDay, LocalDate endDay) {
        challengeRepository.insertForRoomIfAbsent(memberId.value(), roomId, startDay, endDay);
    }

    @Override
    public void insertGeneral(MemberId memberId, LocalDate startDay, LocalDate endDay) {
        challengeRepository.insertGeneral(memberId.value(), startDay, endDay);
    }

    @Override
    public List<ChallengeParticipation> findFinishedByMemberId(MemberId memberId, LocalDate today) {
        return challengeRepository.findFinished(memberId.value(), today).stream()
                .map(this::toParticipation)
                .toList();
    }

    @Override
    public void markSuccess(UUID challengeId) {
        challengeRepository.markSuccess(challengeId);
    }

    /**
     * feed·weight는 Record(Phase 5 소관)가 아직 Spring으로 넘어오지 않아 여기서 읽기 전용으로
     * 직접 조회한다 — Notification이 profiles.fcm_token을 직접 읽는 것과 같은 원칙: 모듈
     * 소유권은 쓰기 책임 기준이지 모든 읽기가 리더 인터페이스를 거칠 필요는 없다. Phase 5에서
     * Record가 전환되면 이 직접 조회는 이벤트 구독으로 교체해야 한다(CHA-04).
     *
     * <p>날짜 경계는 기존 클라이언트의 DateTime(y, m, d, -9) 트릭과 동일한 의미를 KST 타임존
     * 변환으로 명시적으로 계산한다(CHA-06). created_at은 timestamptz이므로 "at time zone
     * 'Asia/Seoul'"은 그 순간의 서울 지역 시각을 나타내는 timestamp를 반환하고, 거기서 날짜만
     * 취하면 KST 달력 기준 하루가 된다.
     */
    @Override
    public Map<LocalDate, Long> countFeedByDay(MemberId memberId, LocalDate startDay, LocalDate asOf) {
        return dailyCounts(
                """
                select (created_at at time zone 'Asia/Seoul')::date as day, count(*) as cnt
                from public.feed
                where user_id = ?
                  and type <> 'exercise'
                  and (created_at at time zone 'Asia/Seoul')::date between ? and ?
                group by day
                """,
                memberId.value(), startDay, asOf
        );
    }

    @Override
    public Map<LocalDate, Long> countWeightByDay(MemberId memberId, LocalDate startDay, LocalDate asOf) {
        return dailyCounts(
                """
                select (created_at at time zone 'Asia/Seoul')::date as day, count(*) as cnt
                from public.weight
                where user_id = ?
                  and (created_at at time zone 'Asia/Seoul')::date between ? and ?
                group by day
                """,
                memberId.value(), startDay, asOf
        );
    }

    private Map<LocalDate, Long> dailyCounts(String sql, Object... args) {
        return jdbcTemplate.query(sql, (ResultSet rs) -> {
            Map<LocalDate, Long> counts = new HashMap<>();
            while (rs.next()) {
                counts.put(rs.getObject("day", LocalDate.class), rs.getLong("cnt"));
            }
            return counts;
        }, args);
    }

    private ChallengeParticipation toParticipation(ChallengeJpaEntity entity) {
        return new ChallengeParticipation(
                entity.id(),
                MemberId.from(entity.userId()),
                entity.startDay(),
                entity.endDay(),
                entity.roomId(),
                entity.success()
        );
    }
}
