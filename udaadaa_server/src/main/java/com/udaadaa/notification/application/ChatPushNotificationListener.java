package com.udaadaa.notification.application;

import com.udaadaa.chat.ChatMessageCreated;
import com.udaadaa.member.MemberId;
import com.udaadaa.member.MemberReader;
import com.udaadaa.member.MemberSummary;
import com.udaadaa.moderation.ModerationReader;
import com.udaadaa.notification.domain.FcmSender;
import com.udaadaa.notification.domain.NotificationRepository;
import com.udaadaa.notification.domain.PushRecipient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ChatMessageCreated를 구독해 같은 방의 다른 참가자에게 FCM Push를 보낸다.
 *
 * <p>기존 message-push Edge Function/DB 트리거를 대체한다. AFTER_COMMIT + {@code @Async}로 동작해
 * Push 발송이 느리거나 실패해도 메시지 저장·STOMP 전달 응답에는 영향을 주지 않는다
 * (로드맵 §9 "STOMP 장애 시에도 저장 API는 유지" 원칙을 Push에도 그대로 적용).
 */
@Component
class ChatPushNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ChatPushNotificationListener.class);

    private final NotificationRepository notificationRepository;
    private final ModerationReader moderationReader;
    private final MemberReader memberReader;
    private final FcmSender fcmSender;

    ChatPushNotificationListener(
            NotificationRepository notificationRepository,
            ModerationReader moderationReader,
            MemberReader memberReader,
            FcmSender fcmSender
    ) {
        this.notificationRepository = notificationRepository;
        this.moderationReader = moderationReader;
        this.memberReader = memberReader;
        this.fcmSender = fcmSender;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChatMessageCreated(ChatMessageCreated event) {
        try {
            handle(event);
        } catch (Exception e) {
            // Push 실패가 메시지 저장/STOMP 전달에 영향을 주면 안 된다 — 로그만 남기고 삼킨다.
            log.warn("Failed to send chat push notification for message {}", event.messageId(), e);
        }
    }

    private void handle(ChatMessageCreated event) {
        List<PushRecipient> recipients = notificationRepository.findPushableRecipients(event.roomId(), event.senderId());
        if (recipients.isEmpty()) {
            return;
        }

        Set<MemberId> candidateIds = recipients.stream().map(PushRecipient::memberId).collect(Collectors.toSet());
        Map<MemberId, Boolean> interactable = moderationReader.canInteractWith(event.senderId(), candidateIds);

        List<String> tokens = recipients.stream()
                .filter(recipient -> interactable.getOrDefault(recipient.memberId(), true))
                .map(PushRecipient::fcmToken)
                .toList();
        if (tokens.isEmpty()) {
            return;
        }

        String roomName = notificationRepository.findRoomName(event.roomId()).orElse("");
        String senderNickname = memberReader.findById(event.senderId())
                .map(MemberSummary::nickname)
                .orElse("");
        String body = "textMessage".equals(event.type()) ? event.content() : "사진";

        fcmSender.sendToAll(tokens, roomName, body, Map.of(
                "roomId", event.roomId().value().toString(),
                "userNickname", senderNickname,
                "content", body == null ? "" : body,
                "type", "message",
                "click_action", "FLUTTER_NOTIFICATION_CLICK"
        ));
    }
}
