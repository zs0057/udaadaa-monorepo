# Phase 4: Challenge Migration

> 상태: 계획 (결정 항목 승인 대기)
> 시작일: 2026-08-07
> 선행 단계: [Phase 3: Chat + Notification](phase-03-chat-notification.md) (Flutter 전환 A~D 완료, 2026-08-07 스모크 테스트로 회귀 수정 완료)

## 1. 목적

챌린지 방 참가와 챌린지 참여를 하나의 서버 트랜잭션으로 묶고, 지금 Flutter `ChallengeCubit`이 매번 `feed`·`weight`를 다시 훑어 계산하는 미션 진행·연속 성공·최종 상태 판정을 Spring `challenge` 모듈로 옮긴다.

```text
Flutter 챌린지 방 참가·진행 조회
→ Spring Challenge API (Chat 참가와 같은 트랜잭션)
→ 기존 Supabase PostgreSQL challenge / feed / weight (읽기)
```

로드맵(§8)이 정한 이번 단계 범위:

> 챌린지 방 입장 시 Chat 참가와 Challenge 참여의 단일 트랜잭션, 미션 조건·진행·연속 성공·최종 상태 계산

`mission_complete` RPC 자체의 대체와 Record·Challenge·Chat을 아우르는 미션 인증 트랜잭션 통합은 **Phase 5**의 범위다(로드맵 §9). Phase 4는 그 전 단계로 "누가 챌린지에 참여했는가·기간이 언제인가·지금까지 얼마나 진행했는가"만 서버가 정확히 알도록 만든다.

## 2. 확인된 현재 상태 (코드 조사 결과)

### 스키마

| 테이블 | 확인 결과 |
|---|---|
| `challenge` (`20241025053118_initial_challenge_schema.sql`) | `id`, `created_at`, `start_day`, `end_day`, `user_id`(FK `profiles.id`), `is_success`(`20241031125811`에서 추가, 기본 `false`). **`room_id` 컬럼이 없다** — 어떤 방에서 시작된 참여인지 DB에 남지 않는다. **유니크 제약이 없다** — 같은 사용자가 같은 기간에 여러 번 insert돼도 막을 방법이 없다 |
| `rooms.start_day` / `rooms.end_day` (`20241219122228_add_column_day_at_table_rooms.sql`) | 둘 다 `not null`이면 챌린지 방, 둘 다 `null`이면 일반 방 — 지금 코드가 실제로 쓰는 유일한 구분 기준(`ChatCubit.joinRoom`의 `if (roomInfo.startDay != null && roomInfo.endDay != null)`) |
| RLS | `challenge`는 활성화, 본인 행만 insert/update/delete, 인증 사용자 전체가 select 가능(다른 참여자 진행 상황을 볼 수 있어야 해서로 추정) |

### Flutter 사용처 (`udaadaa/lib/cubit/challenge_cubit.dart`)

| 메서드 | 줄 | 역할 |
|---|---|---|
| `enterChallenge()` | 64 | 일반 참여(오늘부터 7일, `isEntered()`로 중복 확인 후 insert) |
| `enterChallengeByDay(startDay, endDay)` | 90 | **방 기간 기준 참여 — 중복 확인이 아예 없다.** `ChatCubit.joinRoom()`이 챌린지 방일 때 이 메서드를 그대로 호출한다 |
| `isEntered()` | 109 | 참여 여부 + 현재 챌린지 로드 + 아래 3개 계산 메서드 호출 |
| `getCurrentChallengeCompletedDays()` | 247 | 시작일부터 오늘까지 하루씩 훑으며 `feed`(`type != 'exercise'`) 2건 이상 + `weight` 1건 이상이면 "완료된 날"로 카운트 |
| `getConsecutiveChallengeDays()` | 296 | 같은 조건으로 어제부터 거슬러 올라가며 연속 성공일 계산, 조건 미충족 날에서 멈춤 |
| `getTodayMission()` / `getSelectedDayMission()` | 411 / 351 | 오늘/선택한 날짜의 `feed`·`weight` 카운트 |
| `updateMission()` | 463 | 위 계산 결과로 `_consecutiveDays >= 13`이고 오늘이 `endDay`면 `is_success = true`로 갱신 |

모든 날짜 경계 계산이 `DateTime(y, m, d, -9)` 트릭(음수 hour로 KST 자정 보정)을 4곳에서 반복한다.

### Chat과의 결합 (`ChatCubit.joinRoom`, Phase 3 스모크 테스트에서 재작성된 버전)

```dart
if (roomInfo.startDay != null && roomInfo.endDay != null) {
  try {
    await challengeCubit.enterChallengeByDay(roomInfo.startDay!, roomInfo.endDay!);
  } catch (e) {
    logger.e("joinRoom 챌린지 등록 실패, 참가를 롤백합니다: $e");
    await chatApiClient.leaveRoom(roomId);
  }
}
```

앱 레벨 "보상 트랜잭션"이다 — 실제 DB 트랜잭션이 아니라 두 번의 독립된 네트워크 호출과 실패 시 수동 되돌리기다. 두 호출 사이에 앱이 죽거나 `leaveRoom()` 자체가 실패하면 "방에는 참가했지만 챌린지엔 없는" 상태가 그대로 남는다.

### `mission_complete` RPC (Phase 5 소관, 참고용)

`security definer`, `user_id`를 파라미터로 받아 호출자가 자기 자신인지 내부에서 검증하지 않는다(System Inventory RPC-01, 이미 "높음" 위험으로 기록됨). `feed`/`weight`와 `messages`는 원자적으로 쓰지만 **`challenge` 테이블은 건드리지 않는다** — 미션 인증이 끝나도 챌린지 진행 상태는 여전히 클라이언트가 다시 계산해야 한다. Phase 4는 이 RPC를 바꾸지 않는다.

### 발견한 위험

- **중복 참여**: `enterChallengeByDay`에 멱등성이 없고 `challenge`에 유니크 제약도 없다. `joinRoom` 재시도(예: 네트워크 재시도, 이번 Phase 3 스모크 테스트에서처럼 409 이후 재진입)가 반복되면 같은 사용자가 같은 기간에 여러 `challenge` 행을 가질 수 있다.
- **연속 성공 기준 불일치**: 방 챌린지 기간은 `endDay = startDay + 6일`(7일)인데 성공 판정은 `_consecutiveDays >= 13`이다. 7일짜리 챌린지가 13일 연속을 요구하는 건 앞뒤가 맞지 않는다 — 기존 버그인지 의도된 규칙(예: 여러 챌린지를 이어 참여하는 걸 가정)인지 코드만으로는 확정할 수 없다. 그대로 서버에 옮기지 않고 확인이 필요하다.
- **방-챌린지 연결 부재**: `challenge`에 `room_id`가 없어 "이 참여가 어느 방에서 시작됐는지"를 나중에 조회할 방법이 없다. 지금은 기간(`start_day`/`end_day`)만으로 방과 챌린지를 느슨하게 연결하고 있다.
- **미션 계산이 Record 도메인 데이터에 의존**: 진행·연속 성공 계산은 `feed`·`weight`를 읽어야 하는데, 이 두 테이블은 아직 Spring으로 넘어가지 않은 Record(Phase 5) 소관이다. Phase 4 시점에는 Challenge가 이 두 테이블을 읽기 전용으로 직접 조회할 수밖에 없다(§4 CHA-04에서 결정).

## 3. 범위

### 포함

- 챌린지 참여 조회(참여 여부·기간·진행 일수·연속 성공일수·오늘 완료 여부·최종 성공 여부) API
- 일반 챌린지 참여(7일 고정) API
- 챌린지 방 참가 시 Chat 참가 + Challenge 참여를 하나의 `@Transactional`로 묶기(한쪽만 성공하는 상태 방지)
- 같은 참여 반복 요청에 대한 멱등 처리
- `challenge`에 `room_id`(nullable) 컬럼 Expand — 방-챌린지 연결을 명시적으로 남김
- Flutter `ChallengeCubit`의 조회·참여 로직을 Spring API 호출로 전환

### 제외 (Phase 5로 유보)

- `mission_complete` RPC 자체의 대체
- Record(식단·운동·체중) 기록 생성·조회의 Spring 이전
- 미션 인증 한 번으로 Record+Challenge+Chat이 함께 커밋되는 통합 트랜잭션
- Payment(보증금 환급·몰수) 연동

## 4. 결정이 필요한 항목 (제안 — 승인 필요)

| ID | 결정 항목 | 제안 |
|---|---|---|
| CHA-01 | 챌린지 방 판별 기준 | 새 컬럼을 만들지 않는다. 기존 그대로 `rooms.start_day`·`rooms.end_day`가 둘 다 있으면 챌린지 방으로 판단한다 |
| CHA-02 | 방 참가+챌린지 참여 트랜잭션의 위치 | `chat` 모듈이 `challenge` 모듈의 공개 기능(`ChallengeReader` 또는 `ChallengeParticipationService`, 가칭)을 호출하는 방향으로 만든다 — 지금 Flutter가 `ChatCubit.joinRoom()`에서 `challengeCubit`을 호출하는 방향과 동일하고, Moderation을 Chat·Notification이 참조하는 기존 패턴과도 일치한다. `ChatApplicationService.joinRoom()` 안에서 방이 챌린지 방이면 같은 트랜잭션 안에서 참여까지 처리하고, REST로 별도 "챌린지 참여" 엔드포인트를 노출하지 않는다(방 참가 API 하나로 통합) |
| CHA-03 | 참여 멱등성 | `challenge`에 `(user_id, room_id)` 부분 유니크 인덱스(`room_id is not null`인 경우만)를 추가하고, insert는 `on conflict do nothing`으로 멱등 처리한다. 일반 참여(`room_id is null`)는 "현재 진행 중인 참여가 있는지" 조회 후 있으면 `409`로 막는다(기존 `enterChallenge()`의 `isEntered()` 검증과 동일한 정책 유지) |
| CHA-04 | 미션 진행 계산이 읽는 데이터 | Record가 아직 Spring에 없으므로, Challenge 모듈이 `feed`·`weight`를 읽기 전용으로 직접 조회해 진행·연속 성공을 계산한다(Expand 방식 임시 조치). Phase 5에서 Record가 이벤트(`MissionCompleted` 등)로 이관되면 직접 조회를 이벤트 구독으로 교체한다 |
| CHA-05 | 연속 성공 기준 재정의 | `_consecutiveDays >= 13` 기준을 그대로 서버에 옮기지 않는다. 7일 챌린지 기간과의 불일치를 사용자에게 먼저 확인하고, 확정된 규칙으로 구현한다(예: "기간 내 성공한 날 수 ≥ N" 방식으로 재정의할지, 기존 숫자를 그대로 유지할 이유가 있는지) |
| CHA-06 | 날짜 경계 처리 | `DateTime(y, m, d, -9)` 트릭 대신 서버에서 `ZoneId.of("Asia/Seoul")`로 명시적으로 KST 하루 경계를 계산한다 |

## 5. 목표 API 초안

| Method | Path | 역할 | 비고 |
|---|---|---|---|
| `GET` | `/api/v1/challenges/me` | 현재 참여 중인 챌린지 상태(참여 여부, 시작·종료일, 진행 일수, 연속 성공일수, 오늘 완료 여부, 최종 성공 여부) | `isEntered`+`getCurrentChallenges`+`getConsecutiveChallengeDays`+`getTodayMission`+`getCurrentChallengeCompletedDays`를 한 응답으로 대체 |
| `GET` | `/api/v1/challenges/me/history` | 종료된 챌린지 목록 | `fetchChallenge()` 대체 |
| `POST` | `/api/v1/challenges` | 일반(7일 고정) 챌린지 참여 | 이미 진행 중이면 `409 ALREADY_CHALLENGING` |

방 기반 참여(`enterChallengeByDay`)는 별도 엔드포인트로 만들지 않는다 — CHA-02 결정대로 `POST /rooms/{roomId}/participants`(Phase 3에서 이미 만든 방 참가 API) 내부에서 챌린지 방이면 함께 처리한다.

오류 후보:

| HTTP | Code | 상황 |
|---:|---|---|
| `400` | `INVALID_REQUEST` | 잘못된 요청 |
| `401` | `UNAUTHORIZED` | JWT 없음·오류 |
| `404` | `ROOM_NOT_FOUND` | 방 참가 시 방이 없거나 비참가자(Chat 기존 정책과 동일) |
| `409` | `ALREADY_CHALLENGING` | 일반 참여 시 이미 진행 중인 챌린지가 있음 |
| `500` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

## 6. 목표 모듈 구조

```text
challenge/
├─ ChallengeReader.java     Chat 등 다른 모듈이 쓸 공개 조회·참여 함수 (Moderation의 ModerationReader와 동일한 역할)
├─ domain/                  ChallengeParticipation, 기간·진행·연속 성공 판정 규칙
├─ application/             참여·진행 계산·성공 판정 Use Case
└─ infrastructure/          challenge 테이블 쓰기 + feed·weight 테이블 읽기 전용 JPA 연결(CHA-04)
```

- `chat` 모듈이 `challenge`의 root package 공개 타입만 참조한다(도메인·애플리케이션 내부 타입 참조 금지 — Moderation 때와 같은 원칙).
- `spring_app` DB Role에 `challenge` INSERT/SELECT/UPDATE 권한과 `feed`·`weight` SELECT 권한을 추가해야 한다(Phase 0 Role에 아직 없음).

## 7. 실행 순서

| 단계 | 작업 | 완료 증거 | 상태 |
|---|---|---|---|
| 4-A | 기존 코드·스키마 조사 | 이 문서 §2 | 완료 |
| 4-B | 결정 항목 확정 | CHA-01~06 승인 | 승인 대기 |
| 4-C | `challenge.room_id` Expand + `spring_app` 권한 추가 | SQL 스크립트 작성·운영 DB 적용·확인 | 예정 |
| 4-D | Spring `challenge` 모듈 구현(조회·참여·진행 계산) | 단위·통합 테스트 통과 | 예정 |
| 4-E | `ChatApplicationService.joinRoom`에 챌린지 참여 원자 처리 연결 | 통합 테스트(방+챌린지 동시 성공/롤백 시나리오) | 예정 |
| 4-F | Flutter `ChallengeCubit`을 Spring API 호출로 전환 | 코드 전환 완료 | 예정 |
| 4-G | 안정화와 문서 동기화 | 로그·오류 확인, 실기기 테스트는 전체 Phase 종료 후 일괄 | 예정 |

## 8. 선행 조건과 위험

- CHA-05(연속 성공 기준)는 코드 조사만으로 답을 낼 수 없는 제품 결정이다 — 구현 전에 반드시 확인한다.
- `feed`·`weight`를 Challenge가 직접 읽는 것(CHA-04)은 Record가 아직 그 데이터의 소유권을 Spring으로 옮기지 않은 상태에서의 임시 조치다. Phase 5에서 Record가 전환되면 이 직접 조회를 이벤트 구독으로 반드시 교체해야 한다 — 안 하면 Challenge가 Record의 테이블을 영구히 직접 참조하는 경계 위반이 굳어진다.
- `mission_complete` RPC는 여전히 `challenge`를 갱신하지 않는다. Phase 4가 참여·진행 조회를 서버로 옮겨도, 미션 인증 자체(Flutter → RPC)는 그대로라 인증 직후 진행 상태가 즉시 반영되지 않고 다음 조회 시점에야 반영된다 — Phase 5 전까지 남는 알려진 갭.
- `challenge.room_id` Expand 시점에 기존 데이터의 `room_id`를 백필할 방법이 마땅치 않다(기간과 사용자만으로는 어느 방이었는지 100% 확정할 수 없는 케이스가 있을 수 있음) — 기존 행은 `room_id null`로 남기고, 새 참여부터만 채우는 방향을 기본으로 한다.

## 9. 롤백 기준

- 참여 쓰기 전환 전에는 기존 Flutter 직접 insert 경로로 복귀할 수 있다.
- `challenge.room_id` Expand는 기존 스키마와 호환되므로(nullable 컬럼 추가) 언제든 되돌릴 수 있다.
- Chat의 `joinRoom`이 Challenge 참여까지 트랜잭션으로 묶은 뒤에는, 데이터 정합성을 확인하고 서버에서 복구한다. Flutter의 앱 레벨 보상 트랜잭션(`enterChallengeByDay` 실패 시 `leaveRoom` 호출)과 Spring의 원자적 트랜잭션을 동시에 활성화하지 않는다.
