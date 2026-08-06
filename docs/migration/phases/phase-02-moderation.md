# Phase 2: Moderation Migration

> 상태: 구현 중 (Spring 코드·Flutter 전환 완료, `./gradlew test` 통과 확인 완료 — 실기기 테스트만 남음)
> 시작일: 2026-08-06
> 진행 기록: [2026-08-06 Phase 2 구현 기록](../progress/2026-08-06-phase-02-moderation-implementation.md)

## 1. 목적

기존 `blocked_users` 테이블 기반 차단 기능의 소유권을 Spring Moderation 모듈로 옮기고, Chat·Social이 앞으로 이 테이블을 직접 조회하지 않고 Moderation의 공개 기능을 쓰도록 준비한다.

```text
Flutter 차단 생성·조회
→ Spring Moderation API
→ 기존 Supabase PostgreSQL blocked_users
```

Chat·Notification(Phase 3) 자체의 Spring 전환은 이번 단계 범위가 아니다. Phase 2는 Moderation 모듈과 공개 API를 먼저 만들고 Flutter의 차단 생성·조회만 전환한다. 지금 Supabase Edge Function(`post-initial-chat-data`, `message-push`)이 `blocked_users`를 직접 조회하는 부분은 Chat이 Spring으로 넘어가는 Phase 3에서 함께 정리한다.

## 2. 확인된 현재 상태 (코드 조사 결과)

### 스키마

`supabase/migrations/20250114053515_initial_blocked_users_schema.sql`

| 항목 | 확인 결과 |
|---|---|
| PK | `(user_id, block_user_id)` 복합키 |
| FK | `user_id`, `block_user_id` 모두 `profiles.id` 참조, `ON DELETE CASCADE` |
| RLS | 활성화. SELECT·INSERT·DELETE 모두 `auth.uid() = user_id` 조건 (본인이 만든 차단 행만 보고 지울 수 있음) |
| UPDATE 정책 | 없음 (차단은 생성·삭제만 존재, 수정 없음) |
| 컬럼 | `user_id`(차단한 사람), `block_user_id`(차단당한 사람), `created_at` |

### Flutter 사용처

| 파일 | 역할 |
|---|---|
| `lib/cubit/chat_cubit.dart` | `fetchBlockedUsers()` — 내가 차단한 목록 조회. `blockUser(userId)` — `upsert`로 차단 생성 (클라이언트에서 직접 insert). 채팅 메시지 목록·참가자 정렬·실시간 수신 메시지 필터링에 `blockedUsers` 리스트를 사용 |
| `lib/view/chat/chat_view.dart` | `blockedUsers`를 구독해 참가자 리스트에서 차단 표시 |
| `lib/view/chat/profile_view.dart` | 차단 버튼 UI는 있으나 실제 호출 코드가 주석 처리되어 있음(미사용, 죽은 코드) |

**차단 해제(unblock) UI는 존재하지 않는다.** DB에는 DELETE 정책이 있지만 Flutter 어디에서도 호출하지 않는다.

### Edge Function 사용처

| 파일 | 역할 |
|---|---|
| `supabase/functions/post-initial-chat-data/index.ts` | `fetchBlockedUsers(userId)` — 채팅 진입 시 내가 차단한 목록을 한 번에 내려줌 (`blocked_user_ids`) |
| `supabase/functions/message-push/index.ts` | 메시지 Push 발송 전 `block_user_id = message.user_id`로 조회 — **반대 방향**, 즉 "메시지 보낸 사람을 차단한 사람 목록"을 구해 그들에게는 Push를 보내지 않음 |

### 발견한 위험

- 현재 로직은 방향이 두 갈래로 흩어져 있다: Flutter는 "내가 차단한 사람"만 걸러내고, Push 로직은 "상대가 나를 차단했는지"만 확인한다. 상호작용 차단이 실제로는 **양방향**으로 확인돼야 하는데 지금은 기능마다 한쪽만 확인하고 있어, 이번에 Moderation 공개 API를 설계할 때 양방향 조회를 기본으로 제공해야 한다.
- 차단 해제 기능이 없어 사용자가 실수로 차단하면 되돌릴 방법이 앱 안에 없다(운영 문의로만 처리 추정). Phase 2에서 API는 만들되 UI 노출 여부는 별도 확인이 필요하다.

## 3. 범위

### 포함

- 차단 생성·해제 API (Spring)
- 내가 차단한 목록 조회 API
- 다른 모듈이 쓸 양방향 상호작용 허용 여부 판단 API (`canInteract(a, b)` 또는 다건 조회)
- Flutter의 차단 생성·조회를 Spring API로 전환 (`chat_cubit.dart`의 `fetchBlockedUsers`, `blockUser`)
- `blocked_users` 데이터 소유권을 Moderation으로 명시

### 제외

- Chat 메시지 필터링·정렬 로직 자체의 Spring 이전 (Phase 3)
- Edge Function(`post-initial-chat-data`, `message-push`)의 실제 대체 (Phase 3에서 Chat과 함께)
- 특정 메시지 숨김, 특정 피드 숨김 (각각 Chat, Social 소유로 유지)
- 차단 해제 UI 신규 노출 여부 결정 (API만 만들고 노출은 보류)

## 4. 결정 기록 (제안 — 승인 필요)

| ID | 결정 항목 | 제안 |
|---|---|---|
| MOD-01 | 테이블 | 기존 `blocked_users`를 그대로 사용한다. 새 테이블·컬럼을 만들지 않는다 |
| MOD-02 | 조회 방향 | Moderation 공개 API는 "A가 차단한 목록"과 "A와 B의 상호작용 가능 여부(양방향)"를 모두 제공한다. 후자는 `user_id=A,block_user_id=B` OR `user_id=B,block_user_id=A` 존재 여부로 판단한다 |
| MOD-03 | 차단 해제 | API(`DELETE`)는 구현하되, 이번 Phase에서 Flutter UI에 새로 노출하지는 않는다(기존에 없던 기능 추가는 범위 밖으로 판단, 필요 시 별도 요청) |
| MOD-04 | 권한 | 본인이 만든 차단 행만 조회·삭제 가능. 요청 body의 ID가 아니라 JWT subject 기준 |
| MOD-05 | Edge Function 전환 시점 | `post-initial-chat-data`·`message-push`는 Phase 2에서 건드리지 않는다. Chat이 Spring으로 전환되는 Phase 3에서 함께 정리한다 |

## 5. 목표 API 초안

| Method | Path | 역할 | 인증 |
|---|---|---|---|
| `POST` | `/api/v1/moderation/blocks` | 차단 생성 (body: `blockedMemberId`) | 필수 |
| `DELETE` | `/api/v1/moderation/blocks/{blockedMemberId}` | 차단 해제 | 필수 |
| `GET` | `/api/v1/moderation/blocks` | 내가 차단한 목록 조회 | 필수 |
| `GET` | `/api/v1/moderation/interaction-status?targetIds=...` | 여러 상대와의 상호작용 가능 여부 일괄 조회(양방향) — Chat·Social이 나중에 사용 | 필수 |

오류 후보:

| HTTP | Code | 상황 |
|---:|---|---|
| `400` | `INVALID_REQUEST` | 자기 자신 차단 등 잘못된 요청 |
| `401` | `UNAUTHORIZED` | JWT 없음·오류 |
| `404` | `MEMBER_NOT_FOUND` | 차단 대상 Member가 없음 |
| `500` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

## 6. 목표 모듈 구조

```text
moderation/
├─ presentation/     REST Controller와 요청·응답
├─ application/      차단 생성·해제·조회·상호작용 판단 Use Case
├─ domain/           BlockRelation과 규칙
└─ infrastructure/   기존 blocked_users JPA 연결
```

- 다른 모듈(Member 포함)에 JPA Entity와 Repository를 공개하지 않는다.
- Member의 `spring_app` DB Role 권한에 `blocked_users`에 대한 SELECT/INSERT/DELETE를 추가해야 한다(Phase 0에서 만든 Role에 권한 추가, UPDATE는 불필요).

## 7. 실행 순서

| 단계 | 작업 | 완료 증거 | 상태 |
|---|---|---|---|
| 2-A | 기존 코드·Schema·RLS 조사 | 이 문서 §2 | 완료 |
| 2-B | Moderation 규칙·API 계약 확정 | MOD-01~05 결정 승인 | 완료 |
| 2-C | `spring_app` Role에 `blocked_users` 권한 추가 | SQL 스크립트 작성·운영 DB 적용·`information_schema` 확인 | 완료 |
| 2-D | Spring 차단 생성·해제·조회 구현 | 단위·통합 테스트(`ModerationIntegrationTests`) 로컬 `./gradlew test` 통과 | 완료 |
| 2-E | Flutter 차단 생성·조회를 Spring API로 전환 | `chat_cubit.dart`의 `fetchBlockedUsers`·`blockUser` 전환 완료 | 코드 완료 |
| 2-F | 안정화와 문서 동기화 | 로그·오류 확인, 실기기 테스트는 전체 Phase 종료 후 일괄 | 문서 동기화 완료 (실기기 테스트만 남음) |

2026-08-06 위 결정(MOD-01~05)을 그대로 승인 없이 진행하기로 하고 구현까지 마쳤다(사용자 요청에 따라 계획→구현을 한 세션에서 빠르게 진행). Spring `moderation` 모듈(domain/application/infrastructure/presentation)과 Flutter 전환을 모두 작성했다. 이 세션 샌드박스에는 Java 21이 없어 Claude가 직접 빌드하지 못했으나, 사용자 로컬 터미널에서 `./gradlew test --tests "com.udaadaa.moderation.*"`를 실행해 `BUILD SUCCESSFUL`로 통과를 확인했다. 남은 것은 실기기 회귀 테스트뿐이며, 이는 Phase 1과 동일하게 전체 Phase 종료 후 일괄 진행한다. 상세: [2026-08-06 Phase 2 구현 기록](../progress/2026-08-06-phase-02-moderation-implementation.md).

## 8. 검증 기준

- JWT subject와 다른 사용자의 차단 행을 만들거나 지울 수 없다.
- 같은 상대를 중복 차단해도 오류 없이 멱등하게 처리된다(기존 `upsert` 동작과 동일하게).
- 기존 `fetchBlockedUsers` 결과와 Spring 응답이 일치한다.
- Flutter의 차단 생성 직접 `insert`가 제거된다.
- 실기기 검증은 Phase 1과 동일하게 전체 Phase 종료 후 일괄 진행한다(2026-08-06 결정 유지).

## 9. 선행 조건과 위험

- Phase 0의 `spring_app` Role에 `blocked_users` 권한이 없다 — 2-C에서 추가해야 한다.
- Edge Function이 여전히 `blocked_users`를 직접 쓰므로, Spring과 Edge Function이 같은 테이블에 동시에 쓰기를 시도하는 상황(예: Flutter가 Spring으로 차단 생성 + Edge Function이 예전 방식으로 같은 테이블 읽기)은 읽기만 겹치므로 문제 없다. 다만 향후 Phase 3에서 Edge Function을 걷어낼 때까지는 스키마를 변경하지 않는다.
- 차단 해제 API를 만들되 UI로 노출하지 않는 결정(MOD-03)은 "만들어놓고 안 쓰는 코드"가 될 수 있어, 필요 시 다음 세션에 노출 여부를 다시 확인한다.

## 10. 롤백 기준

- Spring 쓰기 전환 전에는 Flutter의 기존 직접 `upsert` 경로로 복귀할 수 있다.
- Spring 쓰기 전환 후에는 기존 스키마와 호환되는 API를 유지하며 서버를 수정한다. Flutter와 Spring의 차단 쓰기를 동시에 허용하지 않는다.
