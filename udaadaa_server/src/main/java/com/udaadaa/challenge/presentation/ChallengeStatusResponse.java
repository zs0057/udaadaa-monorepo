package com.udaadaa.challenge.presentation;

import com.udaadaa.challenge.application.ChallengeStatus;
import java.time.LocalDate;
import java.util.List;

public record ChallengeStatusResponse(
        boolean participating,
        LocalDate startDay,
        LocalDate endDay,
        int completedDays,
        int consecutiveDays,
        boolean todayCompleted,
        boolean success,
        int todayFeedCount,
        int todayWeightCount,
        List<DailyMissionCountResponse> dailyMissionCounts
) {

    public static ChallengeStatusResponse from(ChallengeStatus status) {
        return new ChallengeStatusResponse(
                status.participating(),
                status.startDay(),
                status.endDay(),
                status.completedDays(),
                status.consecutiveDays(),
                status.todayCompleted(),
                status.success(),
                status.todayFeedCount(),
                status.todayWeightCount(),
                status.dailyMissionCounts().stream().map(DailyMissionCountResponse::from).toList()
        );
    }
}
