package com.udaadaa.moderation.domain;

import com.udaadaa.member.MemberId;
import java.util.List;
import java.util.Set;

public interface BlockRepository {

    List<BlockRelation> findAllByBlocker(MemberId blockerId);

    void block(MemberId blockerId, MemberId blockedId);

    void unblock(MemberId blockerId, MemberId blockedId);

    /**
     * memberId 기준으로 targetIds 중 한쪽이라도 상대를 차단한 관계가 있는 대상 ID를 반환한다
     * (내가 차단했거나 상대가 나를 차단한 경우 모두 포함, 양방향).
     */
    Set<MemberId> findBlockedEitherDirection(MemberId memberId, Set<MemberId> targetIds);
}
