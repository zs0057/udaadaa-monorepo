# 2026-08-06 Phase 3 3-2 구현 기록: 메시지 저장 API + STOMP 실시간 전달

> 관련 계획: [phase-03-chat-notification.md](../phases/phase-03-chat-notification.md) (CHT-01, CHT-03, CHT-04)
> 선행 작업: [3-1 구현 기록](2026-08-06-phase-03-3-1-implementation.md)

## 무엇을 만들었나

1. `spring_app` DB Role에 `messages` INSERT 권한 추가 (운영 DB 적용·검증 완료).
   `udaadaa_server/scripts/db-admin/phase-03-spring-app-messages-insert-grant.sql`
2. `POST /api/v1/chat/rooms/{roomId}/messages` — 텍스트/이미지 메시지 저장 API.
   요청 본문의 `clientMessageId`가 이미 저장된 값이면 새로 만들지 않고 기존 메시지를 그대로 반환한다(재전송 안전).
3. 저장 커밋 이후에만 발행되는 `ChatMessageCreated` 이벤트 → STOMP(`/topic/rooms/{roomId}`)로 같은 방 참가자에게 실시간 전달.
4. STOMP 인증·인가: CONNECT 프레임에서 JWT 검증, SUBSCRIBE마다 참가자 여부 재확인.

## 핵심 코드

**멱등 저장** (`SpringDataMessageRepository.insertIfAbsent`) — insert-or-ignore 후 재조회하는 패턴으로, Moderation 모듈의 `insertIfAbsent`와 동일한 관용구다.

```sql
insert into public.messages (id, room_id, user_id, content, type, image_path, client_message_id)
values (gen_random_uuid(), :roomId, :userId, :content, cast(:type as "MessageType"), :imagePath, :clientMessageId)
on conflict (room_id, client_message_id) where client_message_id is not null do nothing
```

이 `on conflict` 대상은 3-1에서 만든 부분 유니크 인덱스(`messages_room_client_message_id_key`, `client_message_id is not null` 조건)와 정확히 일치해야 동작한다. `sequence`는 여기서 지정하지 않는다 — 3-1에서 만든 `assign_message_sequence` 트리거가 insert 시점에 자동으로 채번한다.

**애플리케이션 레이어** (`ChatApplicationService.sendMessage`) — 참가자 확인 → 타입 허용 여부 확인 → 저장 → 이벤트 발행 순서:

```java
requireParticipant(roomId, senderId);
if (!CREATABLE_MESSAGE_TYPES.contains(type)) {
    throw new InvalidMessageTypeException();
}
MessageSummary saved = chatRepository.saveMessage(roomId, senderId, clientMessageId, type, content, imagePath);
eventPublisher.publishEvent(new ChatMessageCreated(...));
```

`CREATABLE_MESSAGE_TYPES = {"textMessage", "imageMessage"}` — `missionMessage`(미션 인증, Phase 5의 `mission_complete` RPC 소관)와 `infoMessage`(시스템 메시지)는 이 API로 만들 수 없다.

**STOMP 인증** (`ChatChannelInterceptor`) — HTTP 핸드셰이크는 `SecurityConfig`에서 `permitAll`, 대신 STOMP 프레임 단위로 직접 인증·인가한다:

```java
if (StompCommand.CONNECT.equals(accessor.getCommand())) {
    // Authorization: Bearer <JWT> 헤더를 JwtDecoder로 검증 → accessor.setUser(StompPrincipal)
} else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
    // destination이 /topic/rooms/{roomId}면 매번 참가자 여부 재확인
    // 존재하지 않는 방과 비참가자를 구분하지 않음 (REST API의 ROOM_NOT_FOUND 정책과 동일)
}
```

**브로드캐스트** (`ChatMessageBroadcaster`) — 저장 트랜잭션이 롤백되면 아무것도 보내지 않도록 `AFTER_COMMIT`에서만 동작:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
void onChatMessageCreated(ChatMessageCreated event) {
    messagingTemplate.convertAndSend("/topic/rooms/" + event.roomId().value(), ...);
}
```

## 꼭 알아야 할 것

- **Flutter는 아직 이 API를 호출하지 않는다.** 저장·실시간 전달 경로가 모두 갖춰졌지만, 클라이언트 전환(Supabase 직접 insert/Realtime 구독 → REST POST/STOMP 구독)은 3-2 범위에 포함되지 않았다. 다음 단계에서 진행할지, 3-3까지 묶어서 한 번에 전환할지 결정이 필요하다.
- **Gradle 의존성 잠금 주의**: `build.gradle.kts`에 `spring-boot-starter-websocket`을 새로 추가했는데, 이 프로젝트는 `dependencyLocking { lockAllConfigurations() }`를 쓰고 있다. **로컬에서 `./gradlew test`를 돌리기 전에 반드시 `./gradlew dependencies --write-locks`를 먼저 실행**해야 한다. 안 하면 잠금 파일에 없는 의존성이라며 빌드가 바로 실패한다.
- **STOMP 연결 자체는 자동 테스트로 검증되지 않았다.** `ChatIntegrationTests`는 `POST` 저장 API(멱등성 포함)만 MockMvc로 검증했고, 실제 WebSocket 핸드셰이크·CONNECT 인증·SUBSCRIBE 인가·브로드캐스트 수신은 커버하지 않는다. 계획 문서 §7에도 적어뒀듯, 3-2 완료 시점엔 실기기 전수 테스트는 계속 미루더라도 **로컬/스테이징에서 한 번은 실제로 붙여보는 걸 권장**한다(일반 CRUD와 다르게 소켓 연결 성립 여부는 통합 테스트만으로 완전히 보장되지 않기 때문).
- 여전히 나는 이 세션에서 Java 21을 실행할 수 없다(사전 요약에 기록된 제약). 이번 3-1+3-2 전체 `chat` 모듈 코드는 로컬에서 `./gradlew test`로 직접 확인해야 한다.
- `messages` 테이블은 여전히 `BYPASSRLS` Role로 접근하므로, 참가자 확인은 전부 애플리케이션 코드(`requireParticipant`, `ChatChannelInterceptor`)가 책임진다 — DB가 대신 막아주지 않는다.
