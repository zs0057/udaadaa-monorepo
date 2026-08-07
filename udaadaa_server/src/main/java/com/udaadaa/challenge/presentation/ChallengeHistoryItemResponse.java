package com.udaadaa.challenge.presentation;

import com.udaadaa.challenge.domain.ChallengeParticipation;
import java.time.LocalDate;
import java.util.UUID;

public record ChallengeHistoryItemResponse(
        UUID id,
        LocalDate startDay,
        LocalDate endDay,
        boolean success
) {

    public static ChallengeHistoryItemResponse from(ChallengeParticipation participation) {
        return new ChallengeHistoryItemResponse(
                participation.id(),
                participation.startDay(),
                participation.endDay(),
                participation.success()
        );
    }
}
