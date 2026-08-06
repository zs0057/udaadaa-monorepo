package com.udaadaa.moderation.domain;

import com.udaadaa.member.MemberId;
import java.time.Instant;

public record BlockRelation(
        MemberId blockerId,
        MemberId blockedId,
        Instant createdAt
) {
}
