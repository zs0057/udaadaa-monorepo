package com.udaadaa.record.presentation;

import com.udaadaa.record.domain.MissionCommitResult;
import java.util.UUID;

public record MissionCommitResponse(UUID feedId, UUID weightId) {
    public static MissionCommitResponse from(MissionCommitResult result) {
        return new MissionCommitResponse(result.feedId(), result.weightId());
    }
}
