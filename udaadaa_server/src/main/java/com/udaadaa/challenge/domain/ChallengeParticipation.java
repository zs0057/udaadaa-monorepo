package com.udaadaa.challenge.domain;

import com.udaadaa.member.MemberId;
import java.time.LocalDate;
import java.util.UUID;

public record ChallengeParticipation(
        UUID id,
        MemberId memberId,
        LocalDate startDay,
        LocalDate endDay,
        UUID roomId,
        boolean success
) {
}
