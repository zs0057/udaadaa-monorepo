package com.udaadaa.notification.infrastructure;

import com.udaadaa.chat.RoomId;
import com.udaadaa.member.MemberId;
import com.udaadaa.notification.domain.NotificationRepository;
import com.udaadaa.notification.domain.PushRecipient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * profiles.fcm_token / room_participants.push_option은 각각 Member/Chat이 소유한 테이블이지만,
 * Chat·Moderation 모듈이 이미 그렇듯 Notification도 자기 필요에 맞는 읽기 전용 조회를
 * 직접 SQL로 한다(모듈 소유권은 쓰기 책임 기준이지, 모든 읽기가 리더 인터페이스를 거칠 필요는 없다).
 */
@Repository
class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PushRecipient> findPushableRecipients(RoomId roomId, MemberId senderId) {
        return jdbcTemplate.query(
                """
                select rp.user_id as user_id, p.fcm_token as fcm_token
                from public.room_participants rp
                join public.profiles p on p.id = rp.user_id
                where rp.room_id = ?
                  and rp.user_id <> ?
                  and rp.push_option = true
                  and p.push_option = true
                  and p.fcm_token is not null
                """,
                (rs, rowNum) -> new PushRecipient(
                        MemberId.from((UUID) rs.getObject("user_id")),
                        rs.getString("fcm_token")
                ),
                roomId.value(), senderId.value()
        );
    }

    @Override
    public Optional<String> findRoomName(RoomId roomId) {
        return jdbcTemplate.query(
                "select room_name from public.rooms where id = ?",
                (rs, rowNum) -> rs.getString("room_name"),
                roomId.value()
        ).stream().findFirst();
    }
}
