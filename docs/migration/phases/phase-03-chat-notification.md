# Phase 3: Chat + Notification Migration

> 상태: 계획 (조사 완료, 구현 승인 대기)
> 시작일: 2026-08-06

## 1. 목적

실시간 채팅(텍스트·이미지·반응·읽음)과 Push 발송의 소유권을 Spring으로 옮긴다. Phase 0~2와 달리 이번 단계는 실시간 연결과 순서 보장이 걸려 있어 리스크가 크므로, 로드맵(§7)이 정의한 3-1~3-4 하위 단계로 쪼개 순서대로 전환한다.

```text
Flutter → Supabase Realtime(Postgres Changes) 직접 구독/직접 insert
→ (전환 후) Flutter → Spring REST 저장 → DB commit → Spring 내부 이벤트 → STOMP 전달
```

이 문서는 계획까지만 다룬다. 구현은 사용자 승인 후 별도 세션에서 진행한다.

## 2. 확인된 현재 상태 (코드·스키마 조사 결과)

### 스키마

| 테이블 | 역할 | 비고 |
|---|---|---|
| `rooms` | 채팅방. `room_name` UNIQUE, `start_day`/`end_day`(챌린지 방 기간) | 일반 방과 챌린지 방이 테이블 구분 없이 섞여 있음 |
| `room_participants` | 방 참가자. `push_option`(방별 알림 on/off) | PK `(user_id, room_id)` |
| `messages` | 메시지. `type`(`textMessage`/`imageMessage`/`missionMessage`/`infoMessage`), `image_path`, `is_deleted` | **정렬 기준이 `created_at`뿐이고 서버 시퀀스 컬럼이 없음** |
| `chat_reactions` | 메시지 반응(이모지) | |
| `read_receipts` | 읽음 위치. PK `(user_id, message_id)` | 메시지 단위 기록이라 "마지막 읽은 시각"을 매번 최신 1건 조회로 계산 (Phase 1 로드맵이 말한 `lastReadSequence` 방식이 아직 없음) |
| `blocked_messages` | 메시지 단위 숨김(Chat 소유, Moderation과 별개) | |
| `profiles.fcm_token`, `profiles.push_option` | 기기 토큰, 전역 알림 on/off | Member 소유(Phase 1에서 이미 Spring으로 이전됨) |

RLS는 전부 `is_room_participant(room_id)` SECURITY DEFINER 함수 기반이며, `messages` INSERT/UPDATE/SELECT, `chat_reactions`, `read_receipts`, `blocked_messages`, Storage `ImageMessages` 버킷까지 동일 패턴을 공유한다.

### Flutter 코드 흐름 (`lib/cubit/chat_cubit.dart`, 약 2400줄)

- **수신**: `setChatEventsListener()`가 Supabase Realtime(`postgres_changes`)으로 `messages` INSERT/UPDATE를 구독. 클라이언트가 직접 정렬·중복 제거·안 읽음 카운트를 계산.
- **송신(텍스트)**: `sendMessage()` — Flutter가 `messages` 테이블에 직접 `upsert`. 서버 검증은 RLS(`is_room_participant` + `auth.uid() = user_id`)뿐이고 애플리케이션 레벨 검증(내용 길이 등)은 없음.
- **송신(이미지)**: `uploadImage()`/`uploadImages()` — Supabase Storage에 직접 업로드(최대 3회 재시도) 후 `sendImageMessage()`가 `messages`에 `image_path`로 직접 insert.
- **읽음**: `sendReadReceipt()` — `read_receipts`에 직접 upsert.
- **반응**: `sendReaction()` — `chat_reactions`에 직접 insert.
- **미션 인증**: `missionComplete()` — `mission_complete` DB 함수(RPC) 호출로 `feed` insert + `messages`(`missionMessage`) insert를 하나의 Postgres 트랜잭션으로 묶음. **다만 그 직후 호출하는 `challengeCubit.updateMission()`은 별도의 클라이언트 호출이라 트랜잭션에 포함되지 않는다** — Record/Chat은 원자적이지만 Challenge 진행 반영은 best-effort. (Phase 4/5 경계에서 다시 다룰 위험)
- **중복 방지 없음**: `clientMessageId` 같은 개념이 없어 네트워크 재시도 시 중복 메시지가 생길 수 있음(로드맵이 이미 알고 있던 gap, 코드로 재확인).

### Edge Function / DB Webhook

| 함수 | 트리거 | 역할 |
|---|---|---|
| `post-initial-chat-data` | Flutter가 앱 시작 시 직접 호출 | 채팅방 목록, 최근 메시지, 안 읽음, 차단 목록을 한 번에 반환 |
| `stage-post-initial-chat-data` | 〃 (스테이징 전용) | 위와 동일, 스테이징 프로젝트 대상 |
| `message-push` | **`messages` INSERT에 대한 Database Webhook**(대시보드 설정, 마이그레이션 파일로 관리 안 됨) | 방 참가자 중 push_option=true·차단 안 된 사람에게 FCM 발송 |
| `reaction-push` | `reactions` INSERT Database Webhook으로 추정(동일하게 마이그레이션 파일 없음) | 피드 반응 Push (Social 소유이지만 구조가 동일해 참고) |
| `get-room-id-by-name` | Flutter 직접 호출 | 방 이름으로 방 ID 조회 |

**중요**: `message-push`/`reaction-push`를 트리거하는 Database Webhook 설정 자체가 Supabase 대시보드에만 있고 저장소 어디에도 정의돼 있지 않다. Spring으로 전환 시 이 웹훅을 어떻게 끄고 언제 끌지 운영 절차로 별도 문서화해야 한다.

### 2026-08-06 발견한 보안 이슈 (별도 처리 중)

조사 중 `post-initial-chat-data`, `stage-post-initial-chat-data`에 `service_role` 키가 하드코딩되어 git에 커밋돼 있던 것을 발견했다. 코드는 즉시 환경변수 방식으로 되돌렸다(재배포는 사용자가 별도 진행). 실제 키 무효화는 Supabase 대시보드의 "Legacy anon, service_role 비활성화" 토글이 `anon`도 함께 꺼서 앱 전체가 즉시 중단되는 구조라 보류했다 — Flutter를 새 Publishable/Secret 키 체계로 옮기는 별도 작업 이후 처리하기로 결정. 추적은 [Phase 0 Verification §7](phase-00-verification.md)에 통합해서 관리한다.

## 3. 범위 (로드맵 §7 기준)

로드맵이 이미 4단계로 나눠뒀다. 이 문서는 그 순서를 그대로 채택한다.

| 하위 단계 | 범위 |
|---|---|
| 3-1 채팅 조회와 복구 | 방 목록·참가자·메시지 조회 REST API, 서버 BIGINT 순번 도입, `lastReadSequence` 기반 읽음 위치, 누락 메시지 복구 API, 기존 데이터 백필 |
| 3-2 텍스트 저장과 실시간 전달 | Flutter → Spring REST 저장 → 내부 이벤트 → STOMP 전달, `clientMessageId` 중복 방지, 방 참가·Moderation 권한 검증, 재연결 복구 |
| 3-3 부가 기능과 이미지 | 방 참가·나가기, 읽음 갱신, 반응·삭제·숨김, Spring 승인 후 Storage 직접 업로드, 미완료 업로드 정리 |
| 3-4 Notification 전환 | 기기 토큰·전역/방별 알림 설정, `ChatMessageCreated` 내부 이벤트 이후 FCM 발송, 기존 DB Webhook·Edge Function 제거 |

### 제외 (다른 Phase 소관)

- 챌린지 방 참가·기간·미션 진행 규칙 → Phase 4
- `mission_complete` 흐름의 Record/Challenge 쪽 재작성 → Phase 5 (단, 3-2에서 메시지 저장 API를 만들 때 `missionMessage` 타입 저장 방식을 Phase 5가 그대로 재사용할 수 있게 설계해야 함)
- 피드 반응·Social 전용 Push(`reaction-push`) → Phase 6

## 4. 결정이 필요한 항목 (초안 — 승인 필요)

| ID | 항목 | 제안 | 비고 |
|---|---|---|---|
| CHT-01 | 실시간 전달 기술 | STOMP(WebSocket) 신규 도입 (확정, 2026-08-06) | TO-BE 아키텍처 문서 기준. Spring Boot에 `spring-boot-starter-websocket` 추가 필요. 연결 관리·재연결·스케일 시 sticky session 운영 부담을 팀이 새로 떠안는다는 점 인지하고 승인함 |
| CHT-02 | 메시지 정렬 기준 | `messages`에 `sequence BIGINT` 컬럼 추가(방별 단조 증가) (확정, 2026-08-06) | 기존 `created_at` 정렬을 유지하며 병행 추가(Expand), 안정화 후 전환(Switch) |
| CHT-03 | 중복 방지 | `client_message_id` 컬럼 + UNIQUE(`room_id`, `client_message_id`) 추가 (확정, 2026-08-06) | Flutter가 UUID 생성해 전송 |
| CHT-04 | Realtime 대체 시점 | 3-2 완료 후에도 당분간 Supabase Realtime 구독을 유지하고, STOMP 안정화 확인 후 제거 (확정, 2026-08-06) | 두 경로 동시 활성화 금지 원칙과 충돌하지 않도록 "받기만" 이중화하고 "쓰기"는 항상 단일 경로 유지 |
| CHT-05 | DB Webhook 전환 | `message-push` 트리거 정의를 마이그레이션 파일로 baseline 캡처(확정, 2026-08-06) 후 3-4 배포와 동시에 `DROP TRIGGER`로 전환 | 대시보드 UI 설정이 아니라 일반 Postgres 트리거(`supabase_functions.http_request`)로 확인됨. baseline: [phase-03-message-push-trigger-baseline.sql](../sql/phase-03-message-push-trigger-baseline.sql). 이 트리거 정의 자체에 유출된 `service_role` 키가 Authorization 헤더로 박혀 있어, 실제 키 로테이션 시 Edge Function 코드 2개뿐 아니라 이 트리거도 같이 갱신해야 함(Phase 0 Verification §7 추적) |
| CHT-06 | 이미지 업로드 방식 | Spring이 업로드 가능 여부를 승인(발급된 경로/서명)한 뒤 Flutter가 Storage에 직접 업로드하는 현재 패턴 유지 (확정, 2026-08-06) | 이미지 자체를 Spring 서버로 프록시하지 않음(트래픽·비용 이유) |

2026-08-06 CHT-01~06 전체 승인 완료. 3-1(채팅 조회와 복구) 구현에 착수한다.

## 5. 목표 API/이벤트 초안 (3-1, 3-2 우선)

| Method/이벤트 | Path/이름 | 역할 |
|---|---|---|
| `GET` | `/api/v1/chat/rooms` | 내가 참가한 방 목록 + 마지막 메시지 |
| `GET` | `/api/v1/chat/rooms/{roomId}/messages?after={sequence}` | 순번 기준 메시지 조회(초기·페이지·복구 공용) |
| `POST` | `/api/v1/chat/rooms/{roomId}/messages` | 메시지 저장(`clientMessageId` 포함), 저장 성공 시 즉시 응답 |
| `PATCH` | `/api/v1/chat/rooms/{roomId}/read-position` | `lastReadSequence` 갱신 |
| `GET` | `/api/v1/chat/rooms/{roomId}/read-positions` | 방 참가자 전원의 `lastReadSequence` 조회(2026-08-06 Flutter 전환 계획 중 추가 — 메시지별 "안읽음 N명" 표시를 클라이언트가 계산하려면 내 위치만으로는 불가능해서 필요해짐) |
| STOMP `/topic/rooms/{roomId}` | 구독 | 저장 커밋 이후 내부 이벤트로 브로드캐스트 |
| 내부 이벤트 | `ChatMessageCreated` | 메시지 저장 트랜잭션 커밋 후 발행 → STOMP 전달 + (3-4) Notification 트리거 |

## 6. 목표 모듈 구조

```text
chat/
├─ presentation/     REST Controller, STOMP Controller/Handler
├─ application/       메시지 저장·조회·읽음·반응 Use Case, ChatMessageCreated 발행
├─ domain/            Room, Message, ReadPosition, 순번 규칙
└─ infrastructure/    기존 rooms/messages/read_receipts/chat_reactions JPA 연결

notification/ (3-4)
├─ presentation/
├─ application/       ChatMessageCreated 구독 → FCM 발송 Use Case
├─ domain/            기기 토큰, 알림 설정
└─ infrastructure/    profiles.fcm_token, room_participants.push_option 연결, FCM 클라이언트
```

- Chat은 Moderation의 `ModerationReader.canInteractWith`를 참조해 차단된 상대의 메시지를 필터링한다(Phase 2에서 만들어둔 공개 API를 여기서 처음 실제로 소비).
- Notification은 Chat이 발행하는 내부 이벤트만 구독하고 Chat의 Repository를 직접 쓰지 않는다.

## 7. 실행 순서 (제안)

| 단계 | 작업 | 완료 증거 | 상태 |
|---|---|---|---|
| 3-0 | 이 계획 승인 | CHT-01~06 결정 | 완료 (2026-08-06) |
| 3-1 | 채팅 조회·복구 API + 순번 백필 | DB Expand·백필 완료, `spring_app` 읽기 권한 부여 완료, `chat` 모듈(조회 API 2개) 코드 작성 완료 | 코드 완료 (로컬 `./gradlew test` 확인 대기, Flutter 전환 전) |
| 3-2 | 메시지 저장 API + STOMP 전달 | 저장·전달 E2E 테스트, 중복 전송 방지 테스트 | 코드 완료 (로컬 `./gradlew test` 확인 대기, Flutter 전환 전) |
| 3-3 | 참가·읽음·반응·이미지 승인 | 권한·중복 테스트 | 코드 완료 (로컬 `./gradlew test` 확인 대기, Flutter 전환 전) |
| 3-4 | Notification 전환 + 기존 Webhook 제거 | 중복 Push 없음 확인 | Notification 모듈 코드 완료, 기존 트리거 제거는 아직 미실행 (아래 참고) |

2026-08-06 3-1 구현: `messages`에 `sequence`(방별 단조 증가, 트리거로 자동 채번)·`client_message_id` 컬럼을 Expand 방식으로 추가하고 기존 4,053건을 방별로 백필했다(운영 DB에 직접 적용, 검증 완료). `spring_app` Role에 채팅 관련 6개 테이블 SELECT 권한을 추가했다. Spring `chat` 모듈을 새로 만들어 `GET /api/v1/chat/rooms`(참가 방 목록+마지막 메시지), `GET /api/v1/chat/rooms/{roomId}/messages?after=&limit=`(순번 기준 조회) 2개 API를 구현했다 — 비참가자의 접근은 방 존재 여부를 노출하지 않도록 항상 `404 ROOM_NOT_FOUND`로 응답한다. Flutter는 아직 이 API를 호출하지 않는다(기존 Edge Function 조회 경로 그대로 유지 중). 상세: [2026-08-06 Phase 3 3-1 구현 기록](../progress/2026-08-06-phase-03-3-1-implementation.md).

2026-08-06 3-2 구현: `spring_app`에 `messages` INSERT 권한을 추가했다. `POST /api/v1/chat/rooms/{roomId}/messages`를 새로 만들어 `clientMessageId` 기반 멱등 저장(같은 값 재전송 시 새 행을 만들지 않고 기존 메시지를 그대로 반환)을 구현했고, 저장 성공 후 커밋 이후에만 발행되는 `ChatMessageCreated` 이벤트로 STOMP(`/ws/chat` 엔드포인트, `/topic/rooms/{roomId}` 브로커)를 통해 같은 방 참가자에게 실시간 전달한다. 인증은 STOMP CONNECT 프레임의 Authorization 헤더에서 JWT를 검증하는 방식(HTTP 핸드셰이크 자체는 permitAll)이고, SUBSCRIBE마다 참가자 여부를 서버가 다시 확인한다(`BYPASSRLS` Role이라 DB가 막아주지 않음). 이 API가 만들 수 있는 타입은 `textMessage`/`imageMessage`뿐이고, `missionMessage`(Phase 5 소관)·`infoMessage`(시스템 생성)는 이 경로로 거부된다. Flutter는 아직 이 API를 호출하지 않는다. 상세: [2026-08-06 Phase 3 3-2 구현 기록](../progress/2026-08-06-phase-03-3-2-implementation.md).

2026-08-06 3-3 구현: `room_participants`에 `last_read_sequence`(Expand) 컬럼을 추가하고, `spring_app`에 참가/나가기·반응·메시지 삭제·숨김에 필요한 쓰기 권한을 추가했다. 새 API 7종을 구현했다 — 방 참가(`POST /rooms/{roomId}/participants`, 이미 참가 중이면 `409 ALREADY_JOINED`), 방 나가기(`DELETE /rooms/{roomId}/participants/me`, 멱등), 읽음 위치 갱신(`PATCH /rooms/{roomId}/read-position`, 뒤로 가는 값은 무시), 반응 추가/삭제(`POST/DELETE .../reactions`, 운영 DB와 동일하게 중복 반응 허용·남의 반응 삭제는 조용히 무시), 메시지 삭제(`DELETE /rooms/{roomId}/messages/{messageId}`, 발신자 본인만·아니면 `404 MESSAGE_NOT_FOUND`), 메시지 숨김(`POST .../hide`, 멱등), 이미지 업로드 경로 승인(`POST /rooms/{roomId}/image-uploads`). 이미지 업로드는 CHT-06 두 옵션 중 "경로만 발급"으로 확정해, 실제 업로드는 지금처럼 Flutter가 자기 Supabase 세션 + Storage RLS로 직접 처리하고 Spring은 참가자 확인 후 방 스코프 경로만 내려준다 — service_role 키를 Spring이 새로 다루지 않아도 되는 선택. 미완료 업로드 정리는 지금 앱에도 없는 기능이라 이번 범위에서 의도적으로 뺐다(회귀 아님). Flutter는 아직 이 API들을 호출하지 않는다. 상세: [2026-08-06 Phase 3 3-3 구현 기록](../progress/2026-08-06-phase-03-3-3-implementation.md).

2026-08-06 3-4 구현: `notification` 모듈을 새로 만들었다. `ChatMessageCreated`를 구독(`@TransactionalEventListener(AFTER_COMMIT)` + `@Async` — 이 프로젝트 첫 `@Async` 사용처)해 같은 방의 다른 참가자에게 FCM Push를 보낸다. 대상자는 방별·전역 알림 설정이 모두 켜져 있고 fcm_token이 있는 사람만, 그중 Moderation의 `ModerationReader.canInteractWith`로 차단 관계(양방향)를 한 번 더 거른다 — 기존 `message-push` Edge Function은 방별 설정만 봤는데(전역 `push_option` 미확인), 이번에 전역 설정까지 같이 보도록 동작을 개선했다. FCM 발송은 Google 서비스 계정 JWT bearer로 OAuth2 액세스 토큰을 받아 FCM HTTP v1 API를 직접 호출하는 방식으로, 기존 Edge Function(`message-push`)과 동일한 인증 방식을 Java로 재구현했다 — 이 자격 증명은 Supabase `service_role` 키와 무관한 별도의 Google Cloud 서비스 계정이다. `spring_app`에 새 DB 권한은 필요 없었다(읽기만 하고, 이미 있는 SELECT 권한으로 충분). **기존 `message-push` DB 트리거는 아직 그대로 두었다** — Spring Push가 실제 환경에서 검증되기 전까지는 이중 발송을 피하기 위해 함께 켜둔 채로 둬야 한다(baseline: [phase-03-message-push-trigger-baseline.sql](../sql/phase-03-message-push-trigger-baseline.sql)). 상세: [2026-08-06 Phase 3 3-4 구현 기록](../progress/2026-08-06-phase-03-3-4-implementation.md).

실기기 테스트는 Phase 1·2와 동일하게 전체 마이그레이션 종료 후 일괄 진행 원칙을 유지하되, **Phase 3은 실시간성이 핵심 기능이라 3-2 완료 시점에는 최소한 로컬/스테이징에서 한 번은 실제 기기로 확인하는 예외를 두는 것을 권장**(일반 CRUD와 달리 STOMP 연결 자체의 성립 여부는 자동 테스트만으로 완전히 보장하기 어려움).

## 8. 선행 조건과 위험

- ~~Phase 0~2에서 만든 `spring_app` Role에 `rooms`, `room_participants`, `messages`, `chat_reactions`, `read_receipts`, `blocked_messages` 권한이 아직 없다~~ 2026-08-06 SELECT 권한 부여 완료([SQL](../../../udaadaa_server/scripts/db-admin/phase-03-spring-app-chat-read-grant.sql)). INSERT/UPDATE/DELETE는 3-2 착수 시 추가.
- Supabase Storage(`ImageMessages` 버킷) 접근 방식을 Spring이 어떻게 승인할지(서명 URL 등) 아직 결정되지 않았다.
- `message-push`를 트리거하는 것은 일반 Postgres 트리거이며 baseline을 마이그레이션 파일로 캡처해뒀다([참고](../sql/phase-03-message-push-trigger-baseline.sql)). 다만 3-4 배포와 `DROP TRIGGER` 실행 시점이 어긋나면 여전히 이중 Push가 발생할 수 있어, 같은 배포 창에서 순서대로 실행하는 절차를 3-4 착수 시 체크리스트로 만들어야 한다.
- 이 트리거의 Authorization 헤더에 유출된 `service_role` 키가 그대로 박혀 있다(Edge Function 코드와 동일 키). 실제 로테이션 시 트리거도 같이 갱신 필요(Phase 0 Verification §7).
- `mission_complete` RPC가 `messages`에 직접 insert하므로, 3-2에서 메시지 저장 주체를 Spring으로 완전히 옮기기 전까지는 미션 인증 메시지(`missionMessage`)만 예외적으로 기존 RPC 경로가 남는다. Phase 5와 순서를 맞춰야 한다.
- STOMP는 이 프로젝트에 전혀 없던 신규 기술이라 팀 러닝 커브·운영 부담(연결 수 관리, 재연결, 로드밸런싱 시 sticky session 등)이 다른 Phase보다 크다.

## 9. 롤백 기준

- 3-1(조회)은 Flutter의 기존 조회 경로를 건드리지 않으므로 언제든 되돌릴 수 있다.
- 3-2(쓰기 전환) 이후에는 STOMP 장애 시에도 저장 API 자체는 유지하고 Flutter가 REST 폴링·재조회로 복구할 수 있어야 한다.
- 메시지 저장 주체를 Spring으로 전환한 뒤에는 Flutter의 기존 `messages` 직접 insert 경로를 반드시 제거한다(이중 쓰기 금지 원칙).
