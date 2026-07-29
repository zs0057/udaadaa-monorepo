package com.udaadaa.member.domain;

import com.udaadaa.member.MemberId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MemberProfileRepository {

    Optional<MemberProfile> findById(MemberId memberId);

    List<MemberProfile> findAllByIds(Set<MemberId> memberIds);

    boolean existsByNicknameExcludingMember(String nickname, MemberId memberId);

    int insertIfAbsent(MemberId memberId, String nickname);

    MemberProfile update(
            MemberId memberId,
            String nickname,
            BigDecimal height,
            BigDecimal weight
    );
}
