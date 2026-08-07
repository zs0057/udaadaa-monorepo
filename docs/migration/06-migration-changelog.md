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

## Phase 4: Challenge

> 상세: [phase-04-challenge.md](phases/phase-04-challenge.md)

| 기능 | 이전 | 이후 |
|---|---|---|
| 방 참가 + 챌린지 참여 | `ChatCubit.joinRoom()`이 방 참가 성공 후 `enterChallengeByDay()`를 별도 호출, 실패하면 `leaveRoom()`으로 앱이 수동 롤백(진짜 트랜잭션 아님) | `ChatApplicationService.joinRoom()`이 챌린지 방이면 `ChallengeReader.enterForRoom()`을 같은 `@Transactional` 안에서 호출 — 방 참가와 챌린지 참여가 실제 DB 트랜잭션으로 묶임(CHA-02) |
| 참여 중복 방지 | 없음 — `enterChallengeByDay`에 검증이 아예 없고 `challenge`에 유니크 제약도 없었음 | `challenge`에 `room_id` 컬럼 Expand + `(user_id, room_id)` 부분 유니크 인덱스, insert는 `on conflict do nothing`으로 멱등(CHA-03) |
| 챌린지 상태 조회 | `ChallengeCubit`이 `isEntered`·`getCurrentChallenges`·`getConsecutiveChallengeDays`·`getTodayMission`·`getCurrentChallengeCompletedDays` 5개 메서드로 Supabase를 각각 조회·계산 | `GET /api/v1/challenges/me` 한 번 — 참여 여부·기간·진행일수·연속성공일수·오늘 완료 여부·최종 성공 여부·날짜별 미션 건수를 서버가 계산해 반환 |
| 종료된 챌린지 목록 | `fetchChallenge()` — Supabase 직접 조회 | `GET /api/v1/challenges/me/history` |
| 일반(장기) 챌린지 참여 | `enterChallenge()` — Supabase insert, `+6일`(7일) 하드코딩 | `POST /api/v1/challenges` — 14일 고정. 온보딩 경로가 7일로 만들던 기존 버그(13일 연속 성공 기준과 불일치)를 여기서 같이 고침 |
| 미션 진행·연속 성공 계산 | 클라이언트가 매번 `feed`/`weight`를 KST 자정 보정 트릭(`DateTime(y,m,d,-9)`)으로 날짜별 순회 조회 | `challenge` 모듈이 서버에서 계산. `feed`/`weight`는 Record(Phase 5) 소유라 임시로 읽기 전용 직접 조회(CHA-04, Phase 5에서 이벤트 구독으로 교체 예정) |
| 캘린더 날짜 선택 시 미션 건수 | `selectDay()`마다 Supabase에 새로 쿼리(`getSelectedDayMission`) | `GET /me` 응답의 `dailyMissionCounts`(챌린지 시작일~오늘 전체)를 캐시해두고 로컬에서 조회 — 탭할 때마다 네트워크 호출 없음 |
| 날짜 경계 계산 | `DateTime(y, m, d, -9)` 트릭 | 서버에서 `ZoneId.of("Asia/Seoul")`로 명시적 계산(CHA-06) |

**아직 안 바뀐 것**: `mission_complete` RPC는 여전히 `challenge`를 갱신하지 않는다(Phase 5 소관). 미션 인증 직후 진행 상태가 즉시 반영되지 않고, Flutter가 인증 후 `updateMission()`(=서버에 다시 물어보는 `refresh()`)을 호출해야 반영된다 — 이전에도 클라이언트가 재계산해야 했던 것과 근본적으로 같은 갭이다.

## Phase 5: Record + 미션 통합

| 기능 | 이전 | 이후 |
|---|---|---|
| 미션 인증(식단/운동/체중 기록) | `ChatCubit.missionComplete()`가 `supabase.rpc('mission_complete', ...)` 호출 — `feed`(또는 `weight`) insert + `messages` insert(missionMessage+textMessage)만 RPC 안에서 원자적, `report` 갱신은 RPC 성공 후 별도로 클라이언트가 처리 | `POST /api/v1/records/missions` — feed/weight 기록 + report 갱신 + Chat 메시지 저장을 Spring이 하나의 `@Transactional`로 처리. `clientRequestId`로 재시도해도 중복 생성 안 됨 |
| 일별 리포트(report) 갱신 | `FormCubit.updateReport()` — `select` 후 `+=`으로 더해서 다시 `upsert`하는 read-modify-write(경쟁 상태에 취약, RPC와 별도 트랜잭션) | `JdbcRecordRepository.applyReportDelta()` — `insert ... on conflict (user_id, date) do update set col = coalesce(report.col,0) + excluded.col` 단일 원자적 SQL. `FormCubit`은 서버가 반영한 값을 다시 읽기만 함 |
| 외부 칼로리 추정 API 호출 | `FormCubit.calculate()`가 Flutter에서 직접 `dotenv.env['API_URL']/estimateCal` 호출 | `POST /api/v1/records/calorie-estimates` — Spring이 같은 외부 서비스를 서버-서버로 대리 호출(`CALORIE_API_URL`). 응답 포맷은 기존과 동일(snake_case)이라 Flutter `Calorie.fromJson()`은 그대로 |
| 내 피드 삭제 | `FeedCubit.deleteMyFeed()` — report `select` → 계산 → `upsert`(음수면 throw) → `feed` `delete`, 2단계 비원자적 | `DELETE /api/v1/records/feed/{feedId}` — report 차감과 feed 삭제를 한 트랜잭션으로. 리포트 없음/음수면 409로 삭제 자체가 안 일어남(행 잠금 포함, 기존보다 동시성에 더 안전) |
| missionMessage 실시간 전달 | RPC가 `messages`에 직접 insert — Phase 3의 STOMP 전환 이후 이 경로만 `ChatMessageCreated`를 못 태워 다른 참가자에게 실시간으로 안 보였을 가능성 있음 | `ChatReader.recordMissionMessages()`가 일반 메시지와 동일하게 `ChatMessageCreated`를 발행 — STOMP 실시간 전달·Push 알림 정상 작동 |
| 미션 메시지 순서(본문+리뷰) | RPC가 리뷰 텍스트 메시지 시각을 `now() + interval '1 millisecond'`로 밀어서 순서 보장 | Phase 3-1의 `messages.sequence`(DB 트리거 자동 채번)가 이미 삽입 순서를 보장해 트릭 불필요 |

**계획에서 뺀 것**: `GET /api/v1/records?date=`(범용 기록 목록), `POST /api/v1/records/image-uploads`는 만들지 않았다 — 일반 기록 조회는 지금처럼 Flutter가 Supabase에서 직접 읽고(RLS로 보호됨), 이미지 업로드는 기존 이중 업로드 구조(메시지 이미지는 Chat CHT-06 경로, 피드 이미지는 Flutter가 `FeedImages`에 직접)를 그대로 뒀다. 상세: [Phase 5 Record + 미션 통합](phases/phase-05-record-mission-integration.md) §7.

**아직 안 바뀐 것**: `mission_complete` RPC는 권한이 회수되지 않고 그대로 남아 있다(REC-08, Spring 경로 안정성 확인 후 정리 예정). 칼로리 추정 실패 시 Flutter의 수동 입력 UI 진입점은 이번 Phase에서 만들지 않았다(API 계약만 준비).

---

## 전체 진행 상태 요약

| Phase | 상태 | 코드 위치 |
|---|---|---|
| 0. 공통 기반 | 완료 | `udaadaa_server` 전역 설정 |
| 1. Member | 완료 (Flutter 전환까지) | `com.udaadaa.member`, `lib/cubit/auth_cubit.dart` |
| 2. Moderation | 완료 (Flutter 전환까지, 실기기 테스트만 전체 종료 후 일괄) | `com.udaadaa.moderation`, `lib/cubit/chat_cubit.dart`(차단 부분) |
| 3. Chat + Notification | Flutter 전환 A~D 완료, 스모크 테스트로 회귀 4건 수정 완료. Notification은 서버 코드만 완료(기존 Trigger 병행 중) | `com.udaadaa.chat`, `com.udaadaa.notification`, `lib/cubit/chat_cubit.dart` |
| 4. Challenge | 코드 완료 (로컬 `./gradlew test`·`flutter analyze` 확인 대기, 실기기 테스트는 전체 종료 후 일괄) | `com.udaadaa.challenge`, `lib/cubit/challenge_cubit.dart`, `lib/data/challenge_api_client.dart` |
| 5. Record + 미션 통합 | 코드 완료 (로컬 `./gradlew test`·`flutter analyze` 확인 대기, 실기기 테스트는 전체 종료 후 일괄) | `com.udaadaa.record`, `lib/cubit/chat_cubit.dart`(missionComplete)·`form_cubit.dart`(calculate)·`feed_cubit.dart`(deleteMyFeed), `lib/data/record_api_client.dart` |
| 6~8 | 예정 | — |

실기기 회귀 테스트(Task #8)는 전체 Phase 종료 후 일괄 진행하기로 결정된 상태라 이 문서의 "완료"는 코드·시뮬레이터 검증 기준이다.
