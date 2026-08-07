package com.udaadaa.challenge.application;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/v1/challenges/me 응답을 위한 계산 결과. 기존 ChallengeCubit의
 * isEntered+getCurrentChallenges+getConsecutiveChallengeDays+getTodayMission+
 * getCurrentChallengeCompletedDays 다섯 번 호출을 이 한 값으로 대체한다.
 */
public record ChallengeStatus(
        boolean participating,
        LocalDate startDay,
        LocalDate endDay,
        int completedDays,
        int consecutiveDays,
        boolean todayCompleted,
        boolean success,
        // 미션 카드가 "N/2 식단", "N/1 체중" 같은 진행률을 보여주려면 완료 여부(boolean)만으로는
        // 부족해서 오늘자 원본 건수를 그대로 내려준다(기존 ChallengeCubit._selectedMissionComplete와
        // 동등한 정보).
        int todayFeedCount,
        int todayWeightCount,
        // 캘린더에서 과거 날짜를 선택했을 때(widgets/calendar.dart의 selectDay) 매번 새로
        // 조회하지 않도록 startDay~오늘 전체의 날짜별 건수를 함께 내려준다.
        List<DailyMissionCount> dailyMissionCounts
) {

    static ChallengeStatus notParticipating() {
        return new ChallengeStatus(false, null, null, 0, 0, false, false, 0, 0, List.of());
    }
}
