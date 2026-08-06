package com.udaadaa.moderation.infrastructure;

import com.udaadaa.member.MemberId;
import com.udaadaa.moderation.domain.BlockRelation;
import com.udaadaa.moderation.domain.BlockRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
class JpaBlockRepository implements BlockRepository {

    private final SpringDataBlockRepository repository;

    JpaBlockRepository(SpringDataBlockRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<BlockRelation> findAllByBlocker(MemberId blockerId) {
        return repository.findAllByUserId(blockerId.value()).stream()
                .map(entity -> new BlockRelation(
                        MemberId.from(entity.userId()),
                        MemberId.from(entity.blockUserId()),
                        entity.createdAt()
                ))
                .toList();
    }

    @Override
    public void block(MemberId blockerId, MemberId blockedId) {
        repository.insertIfAbsent(blockerId.value(), blockedId.value());
    }

    @Override
    public void unblock(MemberId blockerId, MemberId blockedId) {
        repository.deleteByUserIdAndBlockUserId(blockerId.value(), blockedId.value());
    }

    @Override
    public Set<MemberId> findBlockedEitherDirection(MemberId memberId, Set<MemberId> targetIds) {
        if (targetIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> targetUuids = targetIds.stream().map(MemberId::value).collect(Collectors.toSet());
        return repository.findBlockedEitherDirection(memberId.value(), targetUuids).stream()
                .map(MemberId::from)
                .collect(Collectors.toSet());
    }
}
