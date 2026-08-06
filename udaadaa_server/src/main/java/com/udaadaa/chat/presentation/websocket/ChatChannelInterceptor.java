package com.udaadaa.chat.presentation.websocket;

import com.udaadaa.chat.RoomId;
import com.udaadaa.chat.application.ChatApplicationService;
import com.udaadaa.member.MemberId;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * STOMP 프레임 단위 인증·인가.
 *
 * <p>WebSocket 핸드셰이크(HTTP Upgrade) 자체는 Security 설정에서 permitAll로 열어뒀다.
 * 대신 CONNECT 프레임의 Authorization 헤더에서 Supabase JWT를 검증해 세션에 사용자를 붙이고,
 * SUBSCRIBE 프레임마다 그 방의 참가자인지 매번 확인한다(BYPASSRLS Role이라 애플리케이션이 직접 막아야 함).
 */
@Component
class ChatChannelInterceptor implements ChannelInterceptor {

    private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";

    private final JwtDecoder jwtDecoder;
    private final ChatApplicationService chatApplicationService;

    ChatChannelInterceptor(JwtDecoder jwtDecoder, ChatApplicationService chatApplicationService) {
        this.jwtDecoder = jwtDecoder;
        this.chatApplicationService = chatApplicationService;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing Authorization header on STOMP CONNECT");
        }
        try {
            Jwt jwt = jwtDecoder.decode(authHeader.substring("Bearer ".length()));
            UUID memberId = UUID.fromString(jwt.getSubject());
            accessor.setUser(new StompPrincipal(memberId));
        } catch (JwtException | IllegalArgumentException e) {
            throw new AccessDeniedException("Invalid JWT on STOMP CONNECT", e);
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(ROOM_TOPIC_PREFIX)) {
            // 이 모듈이 다루지 않는 destination은 그대로 통과시킨다.
            return;
        }
        if (!(accessor.getUser() instanceof StompPrincipal principal)) {
            throw new AccessDeniedException("STOMP session is not authenticated");
        }

        UUID roomId;
        try {
            roomId = UUID.fromString(destination.substring(ROOM_TOPIC_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Invalid room topic destination");
        }

        boolean participant = chatApplicationService.isParticipant(
                MemberId.from(principal.memberId()),
                RoomId.from(roomId)
        );
        if (!participant) {
            // 존재하지 않는 방과 비참가자를 구분하지 않는다(REST API의 ROOM_NOT_FOUND 정책과 동일한 이유).
            throw new AccessDeniedException("Not a participant of this room");
        }
    }
}
