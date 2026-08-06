package com.udaadaa.notification.domain;

import com.udaadaa.chat.RoomId;
import com.udaadaa.member.MemberId;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository {

    /**
     * roomId에서 senderId를 제외하고, 방별·전역 알림 설정이 모두 켜져 있으며
     * fcm_token이 등록된 참가자만 반환한다(Moderation 차단 필터링은 여기서 하지 않는다 —
     * 호출하는 쪽이 ModerationReader로 한 번 더 걸러야 한다).
     */
    List<PushRecipient> findPushableRecipients(RoomId roomId, MemberId senderId);

    Optional<String> findRoomName(RoomId roomId);
}
