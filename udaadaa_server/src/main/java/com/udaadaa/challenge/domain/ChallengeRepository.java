package com.udaadaa.challenge.domain;

import com.udaadaa.member.MemberId;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ChallengeRepository {

    /**
     * end_day가 오늘 이후(오늘 포함)인 참여 중 가장 최근에 시작한 것을 반환한다.
     * 기존 데이터에는 유니크 제약이 없어 이론상 여러 개가 있을 수 있으므로 start_day
     * 내림차순으로 하나를 확정한다.
     */
    Optional<ChallengeParticipation> findCurrentByMemberId(MemberId memberId, LocalDate today);

    /**
     * (user_id, room_id) 조합이 이미 있으면 아무 것도 하지 않는다(CHA-03 멱등 처리).
     */
    void insertForRoomIfAbsent(MemberId memberId, UUID roomId, LocalDate startDay, LocalDate endDay);

    /**
     * 일반(방 없는) 참여를 새로 만든다. 호출 전에 이미 진행 중인 참여가 없는지 확인해야 한다.
     */
    void insertGeneral(MemberId memberId, LocalDate startDay, LocalDate endDay);

    /**
     * end_day가 오늘보다 이전인(종료된) 참여를 오래된 순으로 반환한다.
     */
    List<ChallengeParticipation> findFinishedByMemberId(MemberId memberId, LocalDate today);

    void markSuccess(UUID challengeId);

    /**
     * startDay~asOf(포함, KST 달력 기준) 날짜별 feed 등록 건수(운동 제외)를 반환한다.
     * CHA-04: feed는 아직 Spring으로 넘어오지 않은 Record(Phase 5) 소유 테이블이라 읽기 전용으로
     * 직접 조회한다.
     */
    Map<LocalDate, Long> countFeedByDay(MemberId memberId, LocalDate startDay, LocalDate asOf);

    /**
     * startDay~asOf(포함, KST 달력 기준) 날짜별 체중 기록 건수를 반환한다. CHA-04 참고.
     */
    Map<LocalDate, Long> countWeightByDay(MemberId memberId, LocalDate startDay, LocalDate asOf);
}
