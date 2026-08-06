package com.udaadaa.moderation.application;

import com.udaadaa.member.MemberId;
import com.udaadaa.member.MemberReader;
import com.udaadaa.moderation.ModerationReader;
import com.udaadaa.moderation.domain.BlockRelation;
import com.udaadaa.moderation.domain.BlockRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModerationApplicationService implements ModerationReader {

    private final BlockRepository blockRepository;
    private final MemberReader memberReader;

    ModerationApplicationService(BlockRepository blockRepository, MemberReader memberReader) {
        this.blockRepository = blockRepository;
        this.memberReader = memberReader;
    }

    @Transactional
    public void block(MemberId blockerId, MemberId blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new SelfBlockNotAllowedException();
        }
        if (memberReader.findById(blockedId).isEmpty()) {
            throw new BlockedMemberNotFoundException();
        }
        blockRepository.block(blockerId, blockedId);
    }

    @Transactional
    public void unblock(MemberId blockerId, MemberId blockedId) {
        blockRepository.unblock(blockerId, blockedId);
    }

    @Transactional(readOnly = true)
    public List<MemberId> getBlockedMembers(MemberId blockerId) {
        return blockRepository.findAllByBlocker(blockerId).stream()
                .map(BlockRelation::blockedId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<MemberId, Boolean> canInteractWith(MemberId memberId, Set<MemberId> targetIds) {
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        Set<MemberId> blockedEitherDirection = blockRepository.findBlockedEitherDirection(memberId, targetIds);
        Map<MemberId, Boolean> statuses = new LinkedHashMap<>();
        for (MemberId targetId : targetIds) {
            statuses.put(targetId, !blockedEitherDirection.contains(targetId));
        }
        return Map.copyOf(statuses);
    }
}
