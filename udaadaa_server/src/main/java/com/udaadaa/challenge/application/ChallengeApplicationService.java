package com.udaadaa.challenge.application;

import com.udaadaa.challenge.ChallengeReader;
import com.udaadaa.challenge.domain.ChallengeParticipation;
import com.udaadaa.challenge.domain.ChallengeRepository;
import com.udaadaa.member.MemberId;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChallengeApplicationService implements ChallengeReader {

    // CHA-05(2026-08-07 확정): 챌린지는 기본 2주(14일)다.
    static final int GENERAL_CHALLENGE_DAYS = 14;
    // CHA-05: 14일 챌린지에서 마지막 날을 남기고 13일 연속 성공을 요구한다(기존 규칙 그대로).
    private static final int SUCCESS_STREAK_THRESHOLD = 13;
    // CHA-06: 모든 "하루" 경계는 DateTime(y, m, d, -9) 트릭 대신 KST로 명시적으로 계산한다.
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MIN_FEED_COUNT = 2;
    private static final int MIN_WEIGHT_COUNT = 1;

    private final ChallengeRepository challengeRepository;

    ChallengeApplicationService(ChallengeRepository challengeRepository) {
        this.challengeRepository = challengeRepository;
    }

    @Transactional
    public ChallengeStatus getMyStatus(MemberId memberId) {
        LocalDate today = LocalDate.now(KST);
        Optional<ChallengeParticipation> current = challengeRepository.findCurrentByMemberId(memberId, today);
        if (current.isEmpty()) {
            return ChallengeStatus.notParticipating();
        }
        ChallengeParticipation participation = current.get();

        Map<LocalDate, Long> feedCounts = challengeRepository.countFeedByDay(memberId, participation.startDay(), today);
        Map<LocalDate, Long> weightCounts = challengeRepository.countWeightByDay(memberId, participation.startDay(), today);
        Map<LocalDate, Boolean> dailyCompletion = computeDailyCompletion(participation.startDay(), today, feedCounts, weightCounts);

        int completedDays = (int) dailyCompletion.values().stream().filter(Boolean::booleanValue).count();
        int consecutiveDays = countConsecutiveDaysBeforeToday(dailyCompletion, today, participation.startDay());
        boolean todayCompleted = Boolean.TRUE.equals(dailyCompletion.get(today));

        boolean success = participation.success();
        if (!success
                && consecutiveDays >= SUCCESS_STREAK_THRESHOLD
                && today.isEqual(participation.endDay())
                && todayCompleted) {
            // 기존 ChallengeCubit.updateMission()과 동일한 조건 — 조회 시점에 성공 조건이
            // 처음 충족되면 그 자리에서 반영한다(별도 배치 없이 조회-계산-갱신을 한 트랜잭션에서).
            challengeRepository.markSuccess(participation.id());
            success = true;
        }

        return new ChallengeStatus(
                true,
                participation.startDay(),
                participation.endDay(),
                completedDays,
                consecutiveDays,
                todayCompleted,
                success,
                feedCounts.getOrDefault(today, 0L).intValue(),
                weightCounts.getOrDefault(today, 0L).intValue(),
                toDailyMissionCounts(participation.startDay(), today, feedCounts, weightCounts)
        );
    }

    private List<DailyMissionCount> toDailyMissionCounts(
            LocalDate startDay, LocalDate asOf, Map<LocalDate, Long> feedCounts, Map<LocalDate, Long> weightCounts
    ) {
        List<DailyMissionCount> counts = new java.util.ArrayList<>();
        for (LocalDate date = startDay; !date.isAfter(asOf); date = date.plusDays(1)) {
            counts.add(new DailyMissionCount(
                    date,
                    feedCounts.getOrDefault(date, 0L).intValue(),
                    weightCounts.getOrDefault(date, 0L).intValue()
            ));
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public List<ChallengeParticipation> getHistory(MemberId memberId) {
        return challengeRepository.findFinishedByMemberId(memberId, LocalDate.now(KST));
    }

    @Transactional
    public void enterGeneral(MemberId memberId) {
        LocalDate today = LocalDate.now(KST);
        if (challengeRepository.findCurrentByMemberId(memberId, today).isPresent()) {
            throw new AlreadyChallengingException();
        }
        challengeRepository.insertGeneral(memberId, today, today.plusDays(GENERAL_CHALLENGE_DAYS - 1));
    }

    @Override
    @Transactional
    public void enterForRoom(MemberId memberId, UUID roomId, LocalDate startDay, LocalDate endDay) {
        challengeRepository.insertForRoomIfAbsent(memberId, roomId, startDay, endDay);
    }

    private Map<LocalDate, Boolean> computeDailyCompletion(
            LocalDate startDay, LocalDate asOf, Map<LocalDate, Long> feedCounts, Map<LocalDate, Long> weightCounts
    ) {
        Map<LocalDate, Boolean> result = new LinkedHashMap<>();
        for (LocalDate date = startDay; !date.isAfter(asOf); date = date.plusDays(1)) {
            boolean completed = feedCounts.getOrDefault(date, 0L) >= MIN_FEED_COUNT
                    && weightCounts.getOrDefault(date, 0L) >= MIN_WEIGHT_COUNT;
            result.put(date, completed);
        }
        return result;
    }

    /**
     * 오늘을 제외하고 어제부터 거슬러 올라가며 연속 성공한 날 수를 센다 — 기존
     * ChallengeCubit.getConsecutiveChallengeDays와 동일한 정책이다. 오늘은 아직 끝나지
     * 않았을 수 있어 연속일수 계산에서 제외한다.
     */
    private int countConsecutiveDaysBeforeToday(Map<LocalDate, Boolean> dailyCompletion, LocalDate today, LocalDate startDay) {
        int consecutive = 0;
        for (LocalDate date = today.minusDays(1); !date.isBefore(startDay); date = date.minusDays(1)) {
            if (!Boolean.TRUE.equals(dailyCompletion.get(date))) {
                break;
            }
            consecutive++;
        }
        return consecutive;
    }
}
