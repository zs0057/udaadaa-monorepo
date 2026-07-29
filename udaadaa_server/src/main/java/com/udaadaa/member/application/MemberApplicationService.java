package com.udaadaa.member.application;

import com.udaadaa.member.MemberId;
import com.udaadaa.member.MemberReader;
import com.udaadaa.member.MemberSummary;
import com.udaadaa.member.domain.MemberProfile;
import com.udaadaa.member.domain.MemberProfileRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberApplicationService implements MemberReader {

    private static final int MAX_INITIALIZE_ATTEMPTS = 5;

    private final MemberProfileRepository memberProfileRepository;
    private final NicknameGenerator nicknameGenerator;

    MemberApplicationService(
            MemberProfileRepository memberProfileRepository,
            NicknameGenerator nicknameGenerator
    ) {
        this.memberProfileRepository = memberProfileRepository;
        this.nicknameGenerator = nicknameGenerator;
    }

    @Transactional(readOnly = true)
    public MemberProfile get(MemberId memberId) {
        return memberProfileRepository.findById(memberId)
                .orElseThrow(MemberNotFoundException::new);
    }

    @Transactional
    public MemberProfile initialize(MemberId memberId) {
        Optional<MemberProfile> existing = memberProfileRepository.findById(memberId);
        if (existing.isPresent()) {
            return existing.get();
        }

        for (int attempt = 0; attempt < MAX_INITIALIZE_ATTEMPTS; attempt++) {
            memberProfileRepository.insertIfAbsent(memberId, nicknameGenerator.generate());
            Optional<MemberProfile> initialized = memberProfileRepository.findById(memberId);
            if (initialized.isPresent()) {
                return initialized.get();
            }
        }
        throw new IllegalStateException("Could not initialize member profile");
    }

    @Transactional
    public MemberProfile update(
            MemberId memberId,
            String nickname,
            BigDecimal height,
            BigDecimal weight
    ) {
        MemberProfile current = get(memberId);
        String normalizedNickname = nickname == null ? null : nickname.trim();
        if (normalizedNickname != null
                && !normalizedNickname.equals(current.nickname())
                && memberProfileRepository.existsByNicknameExcludingMember(normalizedNickname, memberId)) {
            throw new NicknameAlreadyExistsException();
        }
        return memberProfileRepository.update(memberId, normalizedNickname, height, weight);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberSummary> findById(MemberId memberId) {
        return memberProfileRepository.findById(memberId).map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<MemberId, MemberSummary> findAllByIds(Set<MemberId> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        Map<MemberId, MemberSummary> summaries = new LinkedHashMap<>();
        for (MemberProfile profile : memberProfileRepository.findAllByIds(memberIds)) {
            summaries.put(profile.id(), toSummary(profile));
        }
        return Map.copyOf(summaries);
    }

    private MemberSummary toSummary(MemberProfile profile) {
        return new MemberSummary(profile.id(), profile.nickname(), profile.status());
    }
}
