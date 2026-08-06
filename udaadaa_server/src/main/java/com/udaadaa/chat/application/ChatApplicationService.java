package com.udaadaa.chat.application;

import com.udaadaa.chat.ChatMessageCreated;
import com.udaadaa.chat.RoomId;
import com.udaadaa.chat.domain.ChatRepository;
import com.udaadaa.chat.domain.MessageSummary;
import com.udaadaa.chat.domain.RoomSummary;
import com.udaadaa.member.MemberId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatApplicationService {

    static final int MAX_MESSAGE_PAGE_SIZE = 50;

    // missionMessage는 mission_complete DB 함수(Record/Challenge 트랜잭션)가 전담한다(Phase 5 소관).
    // infoMessage는 현재 클라이언트가 직접 만들지 않는 시스템 메시지 타입이라 이 API 범위 밖이다.
    private static final Set<String> CREATABLE_MESSAGE_TYPES = Set.of("textMessage", "imageMessage");

    private final ChatRepository chatRepository;
    private final ApplicationEventPublisher eventPublisher;

    ChatApplicationService(ChatRepository chatRepository, ApplicationEventPublisher eventPublisher) {
        this.chatRepository = chatRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> getRooms(MemberId memberId) {
        return chatRepository.findRoomSummariesForMember(memberId);
    }

    @Transactional(readOnly = true)
    public List<MessageSummary> getMessages(MemberId memberId, RoomId roomId, long afterSequence, int limit) {
        requireParticipant(roomId, memberId);
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_MESSAGE_PAGE_SIZE);
        return chatRepository.findMessagesAfter(roomId, afterSequence, boundedLimit);
    }

    @Transactional
    public MessageSummary sendMessage(
            MemberId senderId,
            RoomId roomId,
            UUID clientMessageId,
            String type,
            String content,
            String imagePath
    ) {
        requireParticipant(roomId, senderId);
        if (!CREATABLE_MESSAGE_TYPES.contains(type)) {
            throw new InvalidMessageTypeException();
        }

        MessageSummary saved = chatRepository.saveMessage(roomId, senderId, clientMessageId, type, content, imagePath);

        // 트랜잭션 커밋 이후에만 STOMP 전달·Notification 발송이 일어나도록,
        // 리스너 쪽에서 @TransactionalEventListener(phase = AFTER_COMMIT)로 받는다.
        eventPublisher.publishEvent(new ChatMessageCreated(
                saved.id(),
                saved.roomId(),
                saved.senderId(),
                saved.type(),
                saved.content(),
                saved.imagePath(),
                saved.sequence()
        ));
        return saved;
    }

    /**
     * STOMP 구독 권한 검증(ChatChannelInterceptor)에서도 재사용하는 참가자 확인.
     */
    @Transactional(readOnly = true)
    public boolean isParticipant(MemberId memberId, RoomId roomId) {
        return chatRepository.isParticipant(roomId, memberId);
    }

    private void requireParticipant(RoomId roomId, MemberId memberId) {
        if (!chatRepository.isParticipant(roomId, memberId)) {
            throw new RoomNotFoundException();
        }
    }
}
