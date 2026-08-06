package com.udaadaa.chat.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMessageRepository extends JpaRepository<MessageJpaEntity, UUID> {

    Optional<MessageJpaEntity> findFirstByRoomIdOrderBySequenceDesc(UUID roomId);

    List<MessageJpaEntity> findByRoomIdAndSequenceGreaterThanOrderBySequenceAsc(
            UUID roomId,
            long afterSequence,
            Pageable pageable
    );
}
