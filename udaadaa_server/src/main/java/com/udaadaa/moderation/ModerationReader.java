package com.udaadaa.moderation;

import com.udaadaa.member.MemberId;
import java.util.Map;
import java.util.Set;

/**
 * 다른 모듈(Chat, Social 등)이 상호작용 허용 여부를 확인할 때 사용하는 공개 조회 기능.
 *
 * <p>차단은 한쪽 방향으로만 만들어지지만, 상호작용 가능 여부는 양방향으로 판단한다.
 * 즉 내가 상대를 차단했거나 상대가 나를 차단했으면 상호작용 불가로 본다.
 */
public interface ModerationReader {

    Map<MemberId, Boolean> canInteractWith(MemberId memberId, Set<MemberId> targetIds);
}
