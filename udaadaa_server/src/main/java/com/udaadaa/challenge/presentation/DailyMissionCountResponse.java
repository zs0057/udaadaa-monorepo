package com.udaadaa.challenge.presentation;

import com.udaadaa.challenge.application.DailyMissionCount;
import java.time.LocalDate;

public record DailyMissionCountResponse(LocalDate date, int feedCount, int weightCount) {

    public static DailyMissionCountResponse from(DailyMissionCount count) {
        return new DailyMissionCountResponse(count.date(), count.feedCount(), count.weightCount());
    }
}
