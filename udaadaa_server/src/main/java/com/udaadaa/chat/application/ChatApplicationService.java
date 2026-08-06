package com.udaadaa.chat.application;

import com.udaadaa.chat.ChatMessageCreated;
import com.udaadaa.chat.RoomId;
import com.udaadaa.chat.domain.ChatRepository;
import com.udaadaa.chat.domain.MessageSummary;
import com.udaadaa.chat.domain.ReadPosition;
import com.udaadaa.chat.domain.RoomSummary;
import com.udaadaa.member.MemberId;
import com.udaadaa.moderation.ModerationReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final ModerationReader moderationReader;
    private final ApplicationEventPublisher eventPublisher;

    ChatApplicationService(
            ChatRepository chatRepository,
            ModerationReader moderationReader,
            ApplicationEventPublisher eventPublisher
    ) {
        this.chatRepository = chatRepository;
        this.moderationReader = moderationReader;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> getRooms(MemberId memberId) {
        // 방 목록의 마지막 메시지 미리보기는 기존 Edge Function과 동일하게 차단 필터링을 하지 않는다
        // (차단 여부와 무관하게 방의 실제 최신 메시지를 보여주는 게 기존 동작).
        return chatRepository.findRoomSummariesForMember(memberId);
    }

    @Transactional(readOnly = true)
    public List<MessageSummary> getMessages(MemberId memberId, RoomId roomId, long afterSequence, int limit) {
        requireParticipant(roomId, memberId);
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_MESSAGE_PAGE_SIZE);
        List<MessageSummary> messages = chatRepository.findMessagesAfter(roomId, afterSequence, boundedLimit);
        return filterBlockedAndHidden(memberId, messages);
    }

    /**
     * 차단한(또는 차단당한) 상대의 메시지와, 내가 개인적으로 숨긴 메시지를 걸러낸다.
     * 기존 post-initial-chat-data Edge Function이 하던 필터링을 그대로 재현한다.
     */
    private List<MessageSummary> filterBlockedAndHidden(MemberId memberId, List<MessageSummary> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        Set<MemberId> senderIds = messages.stream().map(MessageSummary::senderId).collect(Collectors.toSet());
        Map<MemberId, Boolean> interactable = moderationReader.canInteractWith(memberId, senderIds);

        Set<UUID> messageIds = messages.stream().map(MessageSummary::id).collect(Collectors.toSet());
        Set<UUID> hiddenIds = chatRepository.findHiddenMessageIds(memberId, messageIds);

        return messages.stream()
                .filter(message -> interactable.getOrDefault(message.senderId(), true))
                .filter(message -> !hiddenIds.contains(message.id()))
                .toList();
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

    @Transactional
    public void joinRoom(MemberId memberId, RoomId roomId) {
        if (!chatRepository.roomExists(roomId)) {
            throw new RoomNotFoundException();
        }
        boolean joined = chatRepository.addParticipantIfAbsent(roomId, memberId);
        if (!joined) {
            throw new AlreadyParticipantException();
        }
    }

    @Transactional
    public void leaveRoom(MemberId memberId, RoomId roomId) {
        // 원래 참가자가 아니었어도 에러 없이 성공 처리한다(DELETE 멱등 원칙).
        chatRepository.removeParticipant(roomId, memberId);
    }

    @Transactional
    public void updateReadPosition(MemberId memberId, RoomId roomId, long lastReadSequence) {
        requireParticipant(roomId, memberId);
        chatRepository.updateReadPositionIfGreater(roomId, memberId, lastReadSequence);
    }

    @Transactional(readOnly = true)
    public List<ReadPosition> getReadPositions(MemberId memberId, RoomId roomId) {
        requireParticipant(roomId, memberId);
        return chatRepository.findReadPositions(roomId);
    }

    @Transactional
    public UUID addReaction(MemberId memberId, RoomId roomId, UUID messageId, String content) {
        requireParticipant(roomId, memberId);
        requireMessageInRoom(roomId, messageId);
        return chatRepository.addReaction(roomId, messageId, memberId, content);
    }

    @Transactional
    public void removeReaction(MemberId memberId, RoomId roomId, UUID reactionId) {
        requireParticipant(roomId, memberId);
        // 없거나 남의 반응이면 조용히 무시한다(DELETE 멱등 원칙, 정보 비노출).
        chatRepository.removeReaction(reactionId, memberId);
    }

    @Transactional
    public void deleteMessage(MemberId memberId, RoomId roomId, UUID messageId) {
        requireParticipant(roomId, memberId);
        boolean deleted = chatRepository.markMessageDeletedByOwner(roomId, messageId, memberId);
        if (!deleted) {
            throw new MessageNotFoundException();
        }
    }

    @Transactional
    public void hideMessage(MemberId memberId, RoomId roomId, UUID messageId) {
        requireParticipant(roomId, memberId);
        requireMessageInRoom(roomId, messageId);
        chatRepository.hideMessage(roomId, messageId, memberId);
    }

    /**
     * CHT-06: Spring이 참가자 여부만 확인하고 업로드 가능한 경로를 발급한다.
     * 실제 업로드는 지금처럼 Flutter가 자신의 Supabase 세션으로 Storage에 직접 하고,
     * (Storage RLS의 is_room_participant(folder[1]) 정책이 여전히 실제 방어선이다),
     * 업로드 후 이 경로를 imagePath로 하는 sendMessage 호출로 메시지를 만든다.
     *
     * 미완료(승인만 되고 끝내 메시지로 이어지지 않은) 업로드 정리는 이번 범위에 넣지 않았다
     * — 지금 앱도 동일한 갭이 있어 새로운 회귀가 아니고, 스토리지 정리는 별도 배치 작업으로
     * 다루는 게 낫다고 판단했다.
     */
    @Transactional(readOnly = true)
    public String approveImageUpload(MemberId memberId, RoomId roomId) {
        requireParticipant(roomId, memberId);
        return "%s/%s.jpg".formatted(roomId.value(), UUID.randomUUID());
    }

    private void requireMessageInRoom(RoomId roomId, UUID messageId) {
        chatRepository.findMessageInRoom(roomId, messageId).orElseThrow(MessageNotFoundException::new);
    }

    private void requireParticipant(RoomId roomId, MemberId memberId) {
        if (!chatRepository.isParticipant(roomId, memberId)) {
            throw new RoomNotFoundException();
        }
    }
}
