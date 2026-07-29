package com.udaadaa.member;

public record MemberSummary(
        MemberId id,
        String nickname,
        MemberStatus status
) {
}
