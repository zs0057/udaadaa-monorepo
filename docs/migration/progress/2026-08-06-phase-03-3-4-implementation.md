# 2026-08-06 Phase 3 3-4 구현 기록: Notification 모듈 (FCM Push)

> 관련 계획: [phase-03-chat-notification.md](../phases/phase-03-chat-notification.md) §6 (목표 모듈 구조), CHT-05
> 선행 작업: [3-3 구현 기록](2026-08-06-phase-03-3-3-implementation.md)
> `feature/phase-3-4-notification` 브랜치에서 진행했다 (3-3이 merge된 `main`에서 분기).

## 조사 결과 (기존 message-push Edge Function 확인)

Supabase MCP로 운영에 배포된 `message-push` Edge Function 소스를 직접 읽어 정확한 동작을 확인했다 (저장소에는 이 함수 소스가 없다 — Edge Function 자체가 대시보드에서 만들어져서 로컬 `supabase/functions/`에 없다):

- 대상자 선정: `room_participants`에서 `room_id` 일치·발신자 제외·`push_option = true`인 사람 → `blocked_users`에서 발신자를 차단한 사람 제외(단방향 확인, `profiles.push_option`(전역 알림)은 **확인 안 함**) → `profiles.fcm_token` 조회.
- FCM 발송: Google 서비스 계정(`client_email`/`private_key` 환경변수)으로 JWT bearer 플로우를 태워 OAuth2 액세스 토큰을 받고, `https://fcm.googleapis.com/v1/projects/udaadaa/messages:send`를 토큰마다 개별 POST.
- body는 `textMessage`면 `content`, 아니면 "사진" 고정. data에 `roomId`, `userNickname`, `content`, `type`, `click_action`을 담아 보낸다.

## 무엇을 만들었나 (기존과 달라진 점 포함)

`com.udaadaa.notification` 모듈 신설 — `ChatMessageCreated` 이벤트만 구독하고, Chat의 Repository는 전혀 참조하지 않는다(계획 문서 §6 원칙 그대로).

**의도적으로 바꾼 동작 하나**: 기존 Edge Function은 방별 `push_option`만 봤는데, 이번 구현은 **전역 `profiles.push_option`도 같이 확인**한다. Member(Phase 1)가 이미 이 필드를 관리하고 있는데 기존 Push 발송 로직이 이를 무시하고 있던 걸 발견해서, 계획 문서가 원래 의도했던 "기기 토큰·전역/방별 알림 설정"에 맞게 바로잡았다.

**차단 필터링도 Moderation의 공개 API로 교체**: 기존엔 `blocked_users`를 직접 단방향으로 조회했는데, Phase 2에서 만든 `ModerationReader.canInteractWith`(양방향 차단 확인)를 그대로 재사용했다 — 계획 문서 §6이 원래 "Chat이 이걸 쓴다"고 적어뒀지만, 실제로 차단 여부가 필요한 지점은 Notification의 수신자 필터링이라 여기서 먼저 소비했다.

## 핵심 코드

**이벤트 구독은 비동기** (`ChatPushNotificationListener`) — 이 프로젝트에 처음 등장하는 `@Async` 사용처다:

```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
void onChatMessageCreated(ChatMessageCreated event) {
    try {
        handle(event);
    } catch (Exception e) {
        log.warn("Failed to send chat push notification for message {}", event.messageId(), e);
    }
}
```

FCM 호출은 네트워크 왕복이 있는 외부 연동이라, STOMP 브로드캐스트(인메모리라 동기로 충분)와 달리 메시지 저장 API의 응답 시간에 영향을 주면 안 된다고 판단해 `@Async`로 분리했다. 예외를 전부 삼켜서 Push 실패가 절대 메시지 저장·STOMP 전달에 새어나가지 않게 했다.

**대상자 조회는 SQL 한 방** (`JdbcNotificationRepository.findPushableRecipients`):

```sql
select rp.user_id as user_id, p.fcm_token as fcm_token
from public.room_participants rp
join public.profiles p on p.id = rp.user_id
where rp.room_id = ? and rp.user_id <> ?
  and rp.push_option = true and p.push_option = true
  and p.fcm_token is not null
```

이후 애플리케이션 코드에서 `ModerationReader.canInteractWith`로 한 번 더 거른다.

**FCM 인증은 Google 서비스 계정 JWT bearer** (`FcmClient`) — 기존 Edge Function과 동일한 방식을 Java로 재구현:

```java
JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(properties.clientEmail())
        .subject(properties.clientEmail())
        .audience("https://oauth2.googleapis.com/token")
        .claim("scope", "https://www.googleapis.com/auth/firebase.messaging")
        .expirationTime(Date.from(now.plusSeconds(3600)))
        .build();
// RS256 서명 후 oauth2.googleapis.com/token에 assertion으로 교환, 액세스 토큰은 만료 5분 전까지 캐시
```

새 의존성은 추가하지 않았다 — JWT 서명은 이미 `compileClasspath`에 있던 `nimbus-jose-jwt`(Spring Security OAuth2 Resource Server가 끌고 옴)를 그대로 썼고, HTTP 호출은 JDK 내장 `java.net.http.HttpClient`를 썼다. **그래서 3-2 때와 달리 `gradle.lockfile` 갱신이 필요 없다.**

## 새 환경변수 (아직 값이 없음 — 배포 전 필수)

`application.yml`에 추가:

```yaml
app:
  notification:
    fcm:
      enabled: ${FCM_ENABLED:true}
      project-id: ${FCM_PROJECT_ID:udaadaa}
      client-email: ${FCM_CLIENT_EMAIL:}
      private-key: ${FCM_PRIVATE_KEY:}
```

`FCM_CLIENT_EMAIL`/`FCM_PRIVATE_KEY`는 기존 Edge Function이 쓰던 것과 같은 Google 서비스 계정의 `client_email`/`private_key`다(Supabase Edge Function 시크릿에 이미 등록돼 있을 것). 이 값들이 비어 있으면 `FcmClient`가 조용히 아무 것도 보내지 않는다(에러를 던지지 않음 — 로컬/스테이징에서 자격 증명 없이도 앱이 정상 동작하게 하려는 의도).

## 꼭 알아야 할 것

- **기존 `message-push` DB 트리거를 아직 안 지웠다.** Spring 경로가 실제로 동작하는지 확인되기 전까지는 그대로 둬야 한다 — 지금 지우면 실제 환경변수를 넣기 전이라 Push가 아예 안 나가게 된다. 트리거 제거(`drop trigger "message-push" on public.messages`)는 baseline 파일에 이미 준비돼 있고, **FCM 환경변수를 실제로 채우고 최소 한 번 실 기기에서 Push가 오는 걸 확인한 뒤, 같은 배포 창에서 실행**해야 한다(계획 문서 §8이 이미 경고했던 부분).
- **`FCM_CLIENT_EMAIL`/`FCM_PRIVATE_KEY`를 실제 값으로 채워야 이 경로가 살아난다.** 지금은 빈 값이라 배포해도 아무 일도 안 일어난다(안전하지만, 동시에 "작동하는지" 자체를 아직 검증 못 했다는 뜻이기도 하다).
- **실제 FCM 발송을 자동 테스트로 검증하지 않았다.** `NotificationIntegrationTests`는 대상자 필터링 SQL만 검증했고(전역/방별 알림 설정, 토큰 유무, 발신자 제외), 실제 Google OAuth2 토큰 교환이나 FCM 호출은 네트워크가 필요해 테스트에 포함하지 않았다. `FcmClient`는 자격 증명이 없으면 no-op하도록 만들어서, 이 갭이 최소한 "에러를 던지지는 않는다"는 정도는 보장한다.
- Chat 쪽 로직은 이번에 전혀 안 건드렸다 — `ChatMessageCreated` 이벤트를 구독만 했다.
- 이번에도 로컬 `./gradlew test` 확인이 필요하다. 새 의존성이 없어서 `--write-locks`는 필요 없다.
