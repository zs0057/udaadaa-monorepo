package com.udaadaa.record.domain;

import com.udaadaa.member.MemberId;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface RecordRepository {

    /**
     * clientRequestId로 이미 처리된 커밋이 있는지 확인한다(REC-04 멱등 처리).
     * 있으면 그때 만들어진 feed_id/weight_id를 그대로 돌려준다 — 재시도에도 feed/weight/report/
     * 메시지를 다시 만들지 않기 위함이다.
     */
    Optional<MissionCommitResult> findExistingCommit(UUID clientRequestId);

    /**
     * weight가 아닌 타입의 미션 기록을 feed에 저장한다. is_challenge는 기존 mission_complete
     * DB 함수와 동일하게 항상 true로 고정한다(호출부가 넘기는 값이 아니다 — 기존 RPC도
     * is_challenge 파라미터 자체를 받지 않았다).
     */
    UUID insertFeed(MemberId memberId, FeedType type, String review, String imagePath, Long calorie);

    UUID insertWeight(MemberId memberId, double weightValue, LocalDate date, String imagePath);

    /**
     * report를 (user_id, date) 기준으로 원자적으로 갱신한다(REC-05 — 기존 Flutter
     * updateReport()의 select-then-upsert 비원자적 패턴을 DB 단일 upsert로 대체).
     * meal(breakfast/lunch/dinner/snack)과 exercise는 기존 값에 더하고, weight는 그날의
     * 마지막 값으로 덮어쓴다 — 기존 클라이언트 로직과 동일한 의미.
     */
    void applyReportDelta(MemberId memberId, LocalDate date, FeedType type, Long calorieOrExerciseDelta, Double weightAbsolute);

    void saveCommit(UUID clientRequestId, MemberId memberId, UUID roomId, UUID feedId, UUID weightId);

    Optional<ReportSnapshot> findReport(MemberId memberId, LocalDate date);

    Optional<FeedOwnership> findFeedOwnedBy(MemberId memberId, UUID feedId);

    /**
     * report의 해당 컬럼에서 calorie만큼 뺀다. 결과가 음수가 되면 아무 것도 하지 않고
     * false를 반환한다(기존 deleteMyFeed의 "Negative report data" 방어와 동일한 취지).
     * report 행 자체가 없어도 false.
     */
    boolean decrementReportIfSufficient(MemberId memberId, LocalDate date, FeedType type, Long calorie);

    void deleteFeed(UUID feedId);
}
