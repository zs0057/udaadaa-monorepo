package com.udaadaa.chat.infrastructure;

import com.udaadaa.chat.RoomId;
import com.udaadaa.chat.domain.ChatRepository;
import com.udaadaa.chat.domain.MessageSummary;
import com.udaadaa.chat.domain.RoomSummary;
import com.udaadaa.member.MemberId;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
class JpaChatRepository implements ChatRepository {

    private final SpringDataRoomRepository roomRepository;
    private final SpringDataRoomParticipantRepository roomParticipantRepository;
    private final SpringDataMessageRepository messageRepository;

    JpaChatRepository(
            SpringDataRoomRepository roomRepository,
            SpringDataRoomParticipantRepository roomParticipantRepository,
            SpringDataMessageRepository messageRepository
    ) {
        this.roomRepository = roomRepository;
        this.roomParticipantRepository = roomParticipantRepository;
        this.messageRepository = messageRepository;
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
                                .orElse(null)
                ))
                .toList();
    }

    @Override
    public boolean isParticipant(RoomId roomId, MemberId memberId) {
        return roomParticipantRepository.existsByRoomIdAndUserId(roomId.value(), memberId.value());
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
