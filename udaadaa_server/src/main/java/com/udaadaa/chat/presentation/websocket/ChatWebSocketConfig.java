package com.udaadaa.chat.presentation.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * CHT-01: STOMP 기반 실시간 전달.
 *
 * <p>Flutter는 텍스트/이미지 메시지를 계속 REST(`POST /api/v1/chat/rooms/{roomId}/messages`)로
 * 보내고, 서버는 저장이 커밋된 뒤에만 이 브로커를 통해 같은 방 참가자에게 브로드캐스트한다.
 * 즉 클라이언트가 STOMP로 SEND하는 경로는 없고 SUBSCRIBE만 사용한다.
 */
@Configuration
@EnableWebSocketMessageBroker
class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final ChatChannelInterceptor chatChannelInterceptor;

    ChatWebSocketConfig(ChatChannelInterceptor chatChannelInterceptor) {
        this.chatChannelInterceptor = chatChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(chatChannelInterceptor);
    }
}
