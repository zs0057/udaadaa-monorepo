package com.udaadaa.member.presentation;

import com.udaadaa.member.MemberStatus;
import com.udaadaa.member.domain.MemberProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record MemberProfileResponse(
        UUID id,
        String nickname,
        Instant createdAt,
        BigDecimal height,
        BigDecimal weight,
        MemberStatus status
) {

    static MemberProfileResponse from(MemberProfile profile) {
        return new MemberProfileResponse(
                profile.id().value(),
                profile.nickname(),
                profile.createdAt(),
                profile.height(),
                profile.weight(),
                profile.status()
        );
    }
}
