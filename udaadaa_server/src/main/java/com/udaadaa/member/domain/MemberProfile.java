package com.udaadaa.member.domain;

import com.udaadaa.member.MemberId;
import com.udaadaa.member.MemberStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record MemberProfile(
        MemberId id,
        String nickname,
        Instant createdAt,
        BigDecimal height,
        BigDecimal weight,
        MemberStatus status
) {
}
