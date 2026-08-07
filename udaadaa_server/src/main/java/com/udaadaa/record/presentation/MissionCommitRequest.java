package com.udaadaa.record.presentation;

import com.udaadaa.record.application.MissionCommitCommand;
import com.udaadaa.record.domain.FeedType;
import java.util.UUID;

public record MissionCommitRequest(
        UUID clientRequestId,
        UUID roomId,
        FeedType type,
        String review,
        String messageContent,
        String feedImagePath,
        String messageImagePath,
        Long calorie,
        Double weight,
        Long exerciseTime
) {
    MissionCommitCommand toCommand() {
        return new MissionCommitCommand(
                clientRequestId, roomId, type, review, messageContent,
                feedImagePath, messageImagePath, calorie, weight, exerciseTime
        );
    }
}
