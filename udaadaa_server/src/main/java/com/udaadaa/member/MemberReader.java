package com.udaadaa.member;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface MemberReader {

    Optional<MemberSummary> findById(MemberId memberId);

    Map<MemberId, MemberSummary> findAllByIds(Set<MemberId> memberIds);
}
