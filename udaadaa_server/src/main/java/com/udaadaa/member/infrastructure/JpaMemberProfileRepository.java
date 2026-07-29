package com.udaadaa.member.infrastructure;

import com.udaadaa.member.MemberId;
import com.udaadaa.member.MemberStatus;
import com.udaadaa.member.application.MemberNotFoundException;
import com.udaadaa.member.application.NicknameAlreadyExistsException;
import com.udaadaa.member.domain.MemberProfile;
import com.udaadaa.member.domain.MemberProfileRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaMemberProfileRepository implements MemberProfileRepository {

    private final SpringDataProfileRepository repository;

    JpaMemberProfileRepository(SpringDataProfileRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MemberProfile> findById(MemberId memberId) {
        return repository.findById(memberId.value()).map(this::toDomain);
    }

    @Override
    public List<MemberProfile> findAllByIds(Set<MemberId> memberIds) {
        Set<java.util.UUID> ids = memberIds.stream()
                .map(MemberId::value)
                .collect(Collectors.toSet());
        return repository.findAllById(ids).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean existsByNicknameExcludingMember(String nickname, MemberId memberId) {
        return repository.existsByNicknameAndIdNot(nickname, memberId.value());
    }

    @Override
    public int insertIfAbsent(MemberId memberId, String nickname) {
        return repository.insertIfAbsent(memberId.value(), nickname);
    }

    @Override
    public MemberProfile update(
            MemberId memberId,
            String nickname,
            BigDecimal height,
            BigDecimal weight
    ) {
        ProfileJpaEntity entity = repository.findById(memberId.value())
                .orElseThrow(MemberNotFoundException::new);
        entity.update(nickname, height, weight);
        try {
            return toDomain(repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw new NicknameAlreadyExistsException();
        }
    }

    private MemberProfile toDomain(ProfileJpaEntity entity) {
        return new MemberProfile(
                MemberId.from(entity.id()),
                entity.nickname(),
                entity.createdAt(),
                entity.height(),
                entity.weight(),
                MemberStatus.ACTIVE
        );
    }
}
