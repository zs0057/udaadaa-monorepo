package com.udaadaa.record.infrastructure;

import com.udaadaa.member.MemberId;
import com.udaadaa.record.domain.FeedOwnership;
import com.udaadaa.record.domain.FeedType;
import com.udaadaa.record.domain.MissionCommitResult;
import com.udaadaa.record.domain.RecordRepository;
import com.udaadaa.record.domain.ReportSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRecordRepository implements RecordRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<MissionCommitResult> findExistingCommit(UUID clientRequestId) {
        List<MissionCommitResult> rows = jdbcTemplate.query(
                "select feed_id, weight_id from public.record_mission_commits where client_request_id = ?",
                (rs, rowNum) -> new MissionCommitResult(
                        (UUID) rs.getObject("feed_id"),
                        (UUID) rs.getObject("weight_id")
                ),
                clientRequestId
        );
        return rows.stream().findFirst();
    }

    @Override
    public UUID insertFeed(MemberId memberId, FeedType type, String review, String imagePath, Long calorie) {
        return jdbcTemplate.queryForObject(
                """
                insert into public.feed (user_id, review, type, image_path, calorie, is_challenge)
                values (?, ?, cast(? as "FeedType"), ?, ?, true)
                returning id
                """,
                UUID.class,
                memberId.value(), review, type.dbValue(), imagePath, calorie
        );
    }

    @Override
    public UUID insertWeight(MemberId memberId, double weightValue, LocalDate date, String imagePath) {
        return jdbcTemplate.queryForObject(
                """
                insert into public.weight (user_id, weight, date, image_path)
                values (?, ?, ?, ?)
                returning id
                """,
                UUID.class,
                memberId.value(), weightValue, date, imagePath == null ? "" : imagePath
        );
    }

    @Override
    public void applyReportDelta(
            MemberId memberId, LocalDate date, FeedType type, Long calorieOrExerciseDelta, Double weightAbsolute
    ) {
        if (type.isMeal()) {
            upsertAdd(memberId, date, type.name(), calorieOrExerciseDelta);
        } else if (type == FeedType.exercise) {
            upsertAdd(memberId, date, "exercise", calorieOrExerciseDelta);
        } else if (type == FeedType.weight) {
            upsertOverwrite(memberId, date, weightAbsolute);
        }
    }

    /**
     * column은 FeedType enum(breakfast/lunch/dinner/snack/exercise)에서만 나오는 고정 문자열이라
     * SQL 인젝션 위험이 없다 — 외부 입력이 직접 컬럼명이 되는 경로가 아니다.
     *
     * <p>delta가 null이면(calorie/exerciseTime을 안 보낸 경우) 0으로 취급한다 — 기존
     * Flutter updateReport()가 {@code calorie?.totalCalories ?? 0}으로 null을 0으로 처리하던
     * 것과 동일한 의미다. coalesce 없이 null을 그대로 더하면 report의 기존 값까지 NULL로
     * 덮어써버린다(SQL null 전파).
     */
    private void upsertAdd(MemberId memberId, LocalDate date, String column, Long delta) {
        jdbcTemplate.update(
                """
                insert into public.report (user_id, date, %1$s)
                values (?, ?, coalesce(?, 0))
                on conflict (user_id, date) do update
                set %1$s = coalesce(public.report.%1$s, 0) + excluded.%1$s
                """.formatted(column),
                memberId.value(), date, delta
        );
    }

    private void upsertOverwrite(MemberId memberId, LocalDate date, Double weightAbsolute) {
        jdbcTemplate.update(
                """
                insert into public.report (user_id, date, weight)
                values (?, ?, ?)
                on conflict (user_id, date) do update
                set weight = excluded.weight
                """,
                memberId.value(), date, weightAbsolute
        );
    }

    @Override
    public void saveCommit(UUID clientRequestId, MemberId memberId, UUID roomId, UUID feedId, UUID weightId) {
        jdbcTemplate.update(
                """
                insert into public.record_mission_commits (client_request_id, user_id, room_id, feed_id, weight_id)
                values (?, ?, ?, ?, ?)
                """,
                clientRequestId, memberId.value(), roomId, feedId, weightId
        );
    }

    @Override
    public Optional<ReportSnapshot> findReport(MemberId memberId, LocalDate date) {
        List<ReportSnapshot> rows = jdbcTemplate.query(
                "select date, breakfast, lunch, dinner, snack, exercise, weight " +
                        "from public.report where user_id = ? and date = ?",
                this::mapReport,
                memberId.value(), date
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<FeedOwnership> findFeedOwnedBy(MemberId memberId, UUID feedId) {
        List<FeedOwnership> rows = jdbcTemplate.query(
                """
                select id, user_id, type, calorie, (created_at at time zone 'Asia/Seoul')::date as kst_day
                from public.feed
                where id = ? and user_id = ?
                """,
                (rs, rowNum) -> new FeedOwnership(
                        (UUID) rs.getObject("id"),
                        MemberId.from((UUID) rs.getObject("user_id")),
                        FeedType.valueOf(rs.getString("type")),
                        (Long) rs.getObject("calorie"),
                        rs.getObject("kst_day", LocalDate.class)
                ),
                feedId, memberId.value()
        );
        return rows.stream().findFirst();
    }

    @Override
    public boolean decrementReportIfSufficient(MemberId memberId, LocalDate date, FeedType type, Long calorie) {
        if (!type.isMeal() || calorie == null) {
            // exercise/weight 타입 피드는 기존 deleteMyFeed도 report를 건드리지 않던 것과 동일
            // (기존 동작의 알려진 빈틈이지 이번에 새로 만든 게 아니다 — watchlist에 기록).
            return true;
        }
        String column = type.name();
        int updated = jdbcTemplate.update(
                """
                update public.report
                set %1$s = %1$s - ?
                where user_id = ? and date = ? and coalesce(%1$s, 0) >= ?
                """.formatted(column),
                calorie, memberId.value(), date, calorie
        );
        return updated > 0;
    }

    @Override
    public void deleteFeed(UUID feedId) {
        jdbcTemplate.update("delete from public.feed where id = ?", feedId);
    }

    private ReportSnapshot mapReport(ResultSet rs, int rowNum) throws SQLException {
        return new ReportSnapshot(
                rs.getObject("date", LocalDate.class),
                (Long) rs.getObject("breakfast"),
                (Long) rs.getObject("lunch"),
                (Long) rs.getObject("dinner"),
                (Long) rs.getObject("snack"),
                (Long) rs.getObject("exercise"),
                (Double) rs.getObject("weight")
        );
    }
}
