package com.udaadaa.chat.infrastructure;

import com.udaadaa.chat.RoomId;
import com.udaadaa.chat.domain.ChallengeRoomPeriod;
import com.udaadaa.chat.domain.ChatRepository;
import com.udaadaa.chat.domain.MessageSummary;
import com.udaadaa.chat.domain.ReadPosition;
import com.udaadaa.chat.domain.RoomSummary;
import com.udaadaa.member.MemberId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
class JpaChatRepository implements ChatRepository {

    private final SpringDataRoomRepository roomRepository;
    private final SpringDataRoomParticipantRepository roomParticipantRepository;
    private final SpringDataMessageRepository messageRepository;
    private final SpringDataChatReactionRepository chatReactionRepository;
    private final SpringDataBlockedMessageRepository blockedMessageRepository;

    JpaChatRepository(
            SpringDataRoomRepository roomRepository,
            SpringDataRoomParticipantRepository roomParticipantRepository,
            SpringDataMessageRepository messageRepository,
            SpringDataChatReactionRepository chatReactionRepository,
            SpringDataBlockedMessageRepository blockedMessageRepository
    ) {
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.messageRepository = messageRepository;
        this.chatReactionRepository = chatReactionRepository;
        this.blockedMessageRepository = blockedMessageRepository;
    }

    @Override
    public List<RoomSummary> findRoomSummariesForMember(MemberId memberId) {
        return roomRepository.findRoomsForMember(memberId.value()).stream()
                .map(room -> new RoomSummary(
                        RoomId.from(room.id()),
                        room.roomName(),
                        room.startDay(),
                        room.endDay(),
                        messageRepository.findFirstByRoomIdOrderBySequenceDesc(room.id())
                                .map(this::toMessageSummary)
                                .orElse(null),
                        roomParticipantRepository.findByRoomIdAndUserId(room.id(), memberId.value())
                                .map(RoomParticipantJpaEntity::lastReadSequence)
                                .orElse(0L),
                        roomParticipantRepository.findByRoomId(room.id()).stream()
                                .map(p -> MemberId.from(p.userId()))
                                .toList()
                ))
                .toList();
    }

    @Override
    public boolean isParticipant(RoomId roomId, MemberId memberId) {
        return roomParticipantRepository.existsByRoomIdAndUserId(roomId.value(), memberId.value());
    }

    @Override
    public boolean roomExists(RoomId roomId) {
        return roomRepository.existsById(roomId.value());
    }

    @Override
    public Optional<ChallengeRoomPeriod> findChallengeRoomPeriod(RoomId roomId) {
        return roomRepository.findById(roomId.value())
                .map(room -> new ChallengeRoomPeriod(room.startDay(), room.endDay()));
    }

    @Override
    public List<MessageSummary> findMessagesAfter(RoomId roomId, long afterSequence, int limit) {
        return messageRepository
                .findByRoomIdAndSequenceGreaterThanOrderBySequenceAsc(
                        roomId.value(),
                        afterSequence,
                        PageRequest.of(0, limit)
                )
                .stream()
                .map(this::toMessageSummary)
                .toList();
    }

    @Override
    public List<MessageSummary> findRecentImageMessages(RoomId roomId, int limit) {
        return messageRepository.findRecentImageMessages(roomId.value(), limit).stream()
                .map(this::toMessageSummary)
                .toList();
    }

    @Override
    public MessageSummary saveMessage(
            RoomId roomId,
            MemberId senderId,
            UUID clientMessageId,
            String type,
            String content,
            String imagePath
    ) {
        messageRepository.insertIfAbsent(
                roomId.value(),
                senderId.value(),
                content,
                type,
                imagePath,
                clientMessageId
        );
        return messageRepository.findByRoomIdAndClientMessageId(roomId.value(), clientMessageId)
                .map(this::toMessageSummary)
                .orElseThrow(() -> new IllegalStateException(
                        "Message insert did not fail but the row could not be found afterwards"
                ));
    }

    @Override
    public Optional<MessageSummary> findMessageInRoom(RoomId roomId, UUID messageId) {
        return messageRepository.findByIdAndRoomId(messageId, roomId.value()).map(this::toMessageSummary);
    }

    @Override
    public boolean addParticipantIfAbsent(RoomId roomId, MemberId memberId) {
        return roomParticipantRepository.insertIfAbsent(roomId.value(), memberId.value()) > 0;
    }

    @Override
    public void removeParticipant(RoomId roomId, MemberId memberId) {
        roomParticipantRepository.deleteByRoomIdAndUserId(roomId.value(), memberId.value());
    }

    @Override
    public boolean updateReadPositionIfGreater(RoomId roomId, MemberId memberId, long lastReadSequence) {
        return roomParticipantRepository.updateLastReadSequenceIfGreater(
                roomId.value(), memberId.value(), lastReadSequence) > 0;
    }

    @Override
    public UUID addReaction(RoomId roomId, UUID messageId, MemberId memberId, String content) {
        UUID id = UUID.randomUUID();
        chatReactionRepository.insert(id, roomId.value(), messageId, memberId.value(), content);
        return id;
    }

    @Override
    public void removeReaction(UUID reactionId, MemberId memberId) {
        chatReactionRepository.deleteByIdAndUserId(reactionId, memberId.value());
    }

    @Override
    public boolean markMessageDeletedByOwner(RoomId roomId, UUID messageId, MemberId senderId) {
        return messageRepository.markDeletedByOwner(messageId, roomId.value(), senderId.value()) > 0;
    }

    @Override
    public void hideMessage(RoomId roomId, UUID messageId, MemberId memberId) {
        blockedMessageRepository.insertIfAbsent(memberId.value(), messageId, roomId.value());
    }

    @Override
    public List<ReadPosition> findReadPositions(RoomId roomId) {
        return roomParticipantRepository.findByRoomId(roomId.value()).stream()
                .map(p -> new ReadPosition(MemberId.from(p.userId()), p.lastReadSequence()))
                .toList();
    }

    @Override
    public Set<UUID> findHiddenMessageIds(MemberId memberId, Set<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(blockedMessageRepository.findHiddenMessageIds(memberId.value(), messageIds));
    }

    private MessageSummary toMessageSummary(MessageJpaEntity entity) {
        return new MessageSummary(
                entity.id(),
                RoomId.from(entity.roomId()),
                MemberId.from(entity.userId()),
                entity.type(),
                entity.content(),
                entity.imagePath(),
                entity.sequence(),
                entity.createdAt(),
                entity.isDeleted()
        );
    }
}
