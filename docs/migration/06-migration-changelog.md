# 06. 마이그레이션 변경 매핑 (Before → After)

> 상태: 진행 중 — Phase가 끝날 때마다 이어서 갱신한다.
> 목적: "기존 Flutter/Supabase 코드가 무엇으로 바뀌었는지"를 한 곳에서 훑어보기 위한 요약. 전체 진행 상태표는 [05-migration-roadmap.md](05-migration-roadmap.md) §14, 각 항목의 자세한 구현 내용·트러블슈팅은 아래 표의 "상세" 링크(phases/·progress/ 문서)를 참고.

## 읽는 법

- **이전**: 마이그레이션 전 Flutter가 Supabase를 직접 호출하던 코드(파일·함수 단위).
- **이후**: 전환 후 실제로 호출하는 경로(Spring API 또는 재작성된 Flutter 로직).
- 표는 코드 스니펫이 아니라 "어디서 어디로"만 보여준다. 정확한 코드는 상세 링크의 구현 기록(진행 기록마다 "핵심 코드" 절이 있음)을 열어서 본다.

---

## Phase 1: Member

> 상세: [phase-01-member.md](phases/phase-01-member.md), [Flutter 전환 기록](progress/2026-08-06-phase-01-flutter-transition.md)

| 기능 | 이전 (Flutter → Supabase 직접) | 이후 |
|---|---|---|
| 로그인 시 프로필 조회 | `profiles.select().single()` / `.maybeSingle()` | `GET /api/v1/members/me` |
| 신규 가입(익명·소셜) 프로필 생성 | `profiles.insert()` + 닉네임 유니크 충돌(`23505`) 시 클라이언트가 재시도하는 루프(~40~50줄, `_anonymousLogin`·`makeProfile` 각각) | `POST /api/v1/members/me/initialize` 한 번 호출 — 재시도는 서버(`MemberApplicationService.initialize`, 최대 5회)가 전담 |
| 닉네임 변경 | `profiles.update()` | `PATCH /api/v1/members/me` |
| 키·몸무게 변경 | Supabase에 먼저 쓰고 실패해도 로컬 상태는 이미 갱신(낙관적 갱신) | 서버 응답 성공 시에만 로컬 `_profile` 갱신 |
| `fcm_token` / `push_option` | `profiles` 직접 upsert (변경 안 됨) | 그대로 유지 — Spring Member API 범위 밖 |
| 회원 탈퇴 | `profiles.delete()` (변경 안 됨) | 그대로 유지 — Phase 7 범위 |

부수 효과: `signInWithEmail`의 기존 널 체크 반대 오타(최초 로그인 시 크래시 가능)가 전환하면서 같이 고쳐졌다.

---

## Phase 2: Moderation

> 상세: [phase-02-moderation.md](phases/phase-02-moderation.md), [구현 기록](progress/2026-08-06-phase-02-moderation-implementation.md)

| 기능 | 이전 | 이후 |
|---|---|---|
| 내가 차단한 목록 조회 | `blocked_users.select()` (Flutter 직접) | `GET /api/v1/moderation/blocks` |
| 차단 생성 | `blocked_users.upsert()` (Flutter 직접) | `POST /api/v1/moderation/blocks` (자기 차단·존재하지 않는 회원 서버에서 검증) |
| 차단 해제 | UI·호출 자체가 없었음(기능 없음) | `DELETE /api/v1/moderation/blocks/{id}` API는 만들었으나 UI는 아직 미노출 |
| "상대가 나를 차단했는가" 판단(Push 발송 대상 제외 등) | `message-push` Edge Function이 `blocked_users`를 단방향으로 직접 조회 | `GET /api/v1/moderation/interaction-status` / 내부적으로 `ModerationReader.canInteractWith` — 양방향 조회로 통일 |

건드리지 않음: `post-initial-chat-data`·`message-push` Edge Function은 여전히 `blocked_users`를 직접 읽는다 — Chat이 넘어가는 Phase 3에서 정리.

---

## Phase 3: Chat + Notification

> 상세: [phase-03-chat-notification.md](phases/phase-03-chat-notification.md) §7 실행 순서 아래 진행 기록들, [2026-08-07 스모크 테스트 트러블슈팅](progress/2026-08-07-phase-03-chat-smoke-test-troubleshooting.md)

### 조회 (Flutter 전환 A)

| 기능 | 이전 | 이후 |
|---|---|---|
| 방 목록·참가자·마지막 메시지 조회 | `post-initial-chat-data` Edge Function (하드코딩된 `service_role` 키 사용 — 유출 위험 있었음) | `GET /api/v1/chat/rooms` |
| 메시지 목록 조회 | Edge Function + `created_at` 정렬 | `GET /api/v1/chat/rooms/{roomId}/messages?after=&limit=` — 서버가 채번하는 `sequence` 기준 정렬로 변경(생성 시각 기준의 정렬 흔들림 문제 제거) |
| 차단·숨김 필터링 | Edge Function 내부에서 처리 | 위 조회 API 안에 `ModerationReader.canInteractWith` + `blocked_messages` 필터링으로 재구현(안 하면 차단 기능이 무력화되는 회귀였음) |

### 메시지 전송·실시간 수신 (Flutter 전환 B)

| 기능 | 이전 | 이후 |
|---|---|---|
| 메시지 전송 | `messages.insert()` (Flutter → Supabase 직접) | `POST /api/v1/chat/rooms/{roomId}/messages` — `clientMessageId` 기반 멱등(재전송해도 중복 안 생김) |
| 실시간 수신 | Supabase Realtime 구독 | STOMP(`/ws/chat` 연결, `/topic/rooms/{roomId}` 구독) — Realtime은 당분간 병행 유지(CHT-04) |

### 참가·나가기·읽음·반응·삭제·숨김·이미지 (Flutter 전환 C, D)

| 기능 | 이전 | 이후 |
|---|---|---|
| 방 참가 | 없던 개념(참가자 row를 Flutter가 직접 insert) | `POST /rooms/{roomId}/participants` (이미 참가 중이면 `409`) |
| 방 나가기 | 원래 없던 기능(`leaveRoom()`은 로컬 상태만 초기화, DB는 안 건드림) | `DELETE /rooms/{roomId}/participants/me` (새 기능) |
| 읽음 처리 | 메시지마다 `read_receipts` row를 upsert(방 진입 시 방 안의 메시지 수만큼 루프) | 방별 `PATCH /rooms/{roomId}/read-position`(마지막으로 읽은 `sequence` 하나만 갱신) 한 번 호출 |
| "안읽음 N명" 배지 | 메시지별 `read_receipts` row 개수를 세서 계산 | 참가자 전원의 읽음 위치(`GET /rooms/{roomId}/read-positions`)와 메시지 `sequence`를 비교해 클라이언트가 역산(`_recomputeReadReceiptsForRoom`) |
| 읽음 위치 실시간 갱신(상대가 읽으면 내 배지도 즉시 감소) | 없음(원래 계획엔 없었으나 필요해서 추가) | 백엔드 `ReadPositionUpdated` 이벤트 → STOMP 브로드캐스트(`eventType: "readPosition"`, 메시지 브로드캐스트와 같은 토픽 공유) |
| 반응(이모지) 추가 | `chat_reactions.insert()` | `POST /rooms/{roomId}/messages/{messageId}/reactions` |
| 반응 삭제 | 원래 없던 기능(UI도 없었음) | `DELETE /rooms/{roomId}/reactions/{reactionId}` (본인 것만, 새 기능) |
| 메시지 삭제 | `messages.update({is_deleted: true})`, 권한 검증은 RLS에 위임 | `DELETE /rooms/{roomId}/messages/{messageId}` — 발신자 일치까지 SQL 조건에 넣어 원자적으로 처리(Spring은 `BYPASSRLS`라 RLS가 안 막아줌) |
| 메시지 숨김(내 화면에서만) | `blocked_messages.upsert()` | `POST /rooms/{roomId}/messages/{messageId}/hide` |
| 이미지 업로드 | Flutter가 Supabase Storage에 직접 업로드(RLS로 방어) | Spring이 참가자 확인 후 업로드 가능 경로만 발급(`POST /rooms/{roomId}/image-uploads`), 실제 업로드는 여전히 Flutter → Storage 직접(Spring이 `service_role` 키를 새로 다루지 않기 위한 선택) |

### 발신자 프로필 캐시 미스 보강 (2026-08-07 스모크 테스트에서 발견)

| 기능 | 이전 | 이후 |
|---|---|---|
| 메시지 발신자 닉네임 표시 | Realtime 경로는 메시지마다 `profiles`를 직접 조회해서 항상 정확했음 | 전환 후 5개 경로(초기 로드·이미지 로드·더보기·STOMP 수신·재연결 복구)가 `room.memberMap`(현재 참가자 캐시)에만 의존해, 방을 나간 사람·방금 참가한 사람의 메시지는 ID가 그대로 보이는 회귀 발생 → `_fillMissingSenderProfiles`로 캐시 미스만 배치 조회해서 보강 |

### Notification (Flutter 전환 없음, 서버만 전환·아직 미전환 상태)

| 기능 | 이전 | 이후 |
|---|---|---|
| 메시지 Push 발송 | `messages` insert 시 DB Trigger → `message-push` Edge Function(방별 `push_option`만 확인, 전역 설정 미확인) | `com.udaadaa.notification` 모듈이 `ChatMessageCreated` 이벤트 구독 → FCM 직접 호출. 전역+방별 `push_option`을 모두 확인하도록 동작 개선, 차단 필터링도 Moderation 양방향 조회로 교체 |

**아직 안 바뀐 것**: 기존 `message-push` DB Trigger를 그대로 켜둔 상태다. Spring Push 경로가 운영에서 실제로 검증되기 전까지는 중복 발송을 피하기 위해 유지한다(전환 전환점은 [phase-03-message-push-trigger-baseline.sql](sql/phase-03-message-push-trigger-baseline.sql) 참고).

---

## Phase 4 이후

아직 계획 단계이거나 시작 전이라 매핑할 내용이 없다. Phase가 진행되면 이 문서에 같은 형식으로 이어서 추가한다.

---

## 전체 진행 상태 요약

| Phase | 상태 | 코드 위치 |
|---|---|---|
| 0. 공통 기반 | 완료 | `udaadaa_server` 전역 설정 |
| 1. Member | 완료 (Flutter 전환까지) | `com.udaadaa.member`, `lib/cubit/auth_cubit.dart` |
| 2. Moderation | 완료 (Flutter 전환까지, 실기기 테스트만 전체 종료 후 일괄) | `com.udaadaa.moderation`, `lib/cubit/chat_cubit.dart`(차단 부분) |
| 3. Chat + Notification | Flutter 전환 A~D 완료, 스모크 테스트로 회귀 4건 수정 완료. Notification은 서버 코드만 완료(기존 Trigger 병행 중) | `com.udaadaa.chat`, `com.udaadaa.notification`, `lib/cubit/chat_cubit.dart` |
| 4. Challenge | 조사 완료, 계획 문서 작성 중 | — |
| 5~8 | 예정 | — |

실기기 회귀 테스트(Task #8)는 전체 Phase 종료 후 일괄 진행하기로 결정된 상태라 이 문서의 "완료"는 코드·시뮬레이터 검증 기준이다.
