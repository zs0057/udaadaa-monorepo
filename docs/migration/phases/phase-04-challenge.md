# Phase 4: Challenge Migration

> 상태: 구현 완료 (CHA-01~06 승인 완료, Spring·Flutter 코드 작성 완료 — 로컬 `./gradlew test`·`flutter analyze` 확인과 실기기 테스트만 남음)
> 시작일: 2026-08-07
> 선행 단계: [Phase 3: Chat + Notification](phase-03-chat-notification.md) (Flutter 전환 A~D 완료, 2026-08-07 스모크 테스트로 회귀 수정 완료)
> 변경 매핑: [06-migration-changelog.md](../06-migration-changelog.md#phase-4-challenge)
> 추가 검토 필요 사항: [07-implementation-watchlist.md](../07-implementation-watchlist.md)

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
- **`enterChallenge()`(온보딩 경로)의 7일 하드코딩은 실제 버그로 확인됨**: 챌린지는 기본 2주(14일)이고 성공 판정 `_consecutiveDays >= 13`도 이 14일 기준이 맞다(2026-08-07 확인). 그런데 `view/onboarding/tenth_view.dart:188`에서 호출하는 `enterChallenge()`는 `endDay: today.add(const Duration(days: 6))`로 7일짜리 챌린지를 만든다 — 13일 연속 성공을 요구하는데 챌린지 자체가 7일이라 **온보딩으로 시작한 챌린지는 수학적으로 성공이 불가능하다.** Phase 4에서 서버 API로 옮길 때 7일이 아니라 14일로 고쳐야 한다(방 기반 참여 `enterChallengeByDay`는 방의 `start_day`/`end_day`를 그대로 쓰므로 이 버그와 무관 — 방은 이미 14일로 만들어지는 것으로 보임).
- **방-챌린지 연결 부재**: `challenge`에 `room_id`가 없어 "이 참여가 어느 방에서 시작됐는지"를 나중에 조회할 방법이 없다. 지금은 기간(`start_day`/`end_day`)만으로 방과 챌린지를 느슨하게 연결하고 있다.
- **미션 계산이 Record 도메인 데이터에 의존**: 진행·연속 성공 계산은 `feed`·`weight`를 읽어야 하는데, 이 두 테이블은 아직 Spring으로 넘어가지 않은 Record(Phase 5) 소관이다. Phase 4 시점에는 Challenge가 이 두 테이블을 읽기 전용으로 직접 조회할 수밖에 없다(§4 CHA-04에서 결정).

## 3. 범위

### 포함

- 챌린지 참여 조회(참여 여부·기간·진행 일수·연속 성공일수·오늘 완료 여부·최종 성공 여부) API
- 일반 챌린지 참여(14일 고정, 기존 7일 하드코딩 버그 수정) API
- 챌린지 방 참가 시 Chat 참가 + Challenge 참여를 하나의 `@Transactional`로 묶기(한쪽만 성공하는 상태 방지)
- 같은 참여 반복 요청에 대한 멱등 처리
- `challenge`에 `room_id`(nullable) 컬럼 Expand — 방-챌린지 연결을 명시적으로 남김
- Flutter `ChallengeCubit`의 조회·참여 로직을 Spring API 호출로 전환

### 제외 (Phase 5로 유보)

- `mission_complete` RPC 자체의 대체
- Record(식단·운동·체중) 기록 생성·조회의 Spring 이전
- 미션 인증 한 번으로 Record+Challenge+Chat이 함께 커밋되는 통합 트랜잭션
- Payment(보증금 환급·몰수) 연동

## 4. 결정 기록 (CHA-01~06, 2026-08-07 전부 승인·구현 완료)

| ID | 결정 항목 | 제안 |
|---|---|---|
| CHA-01 | 챌린지 방 판별 기준 | 새 컬럼을 만들지 않는다. 기존 그대로 `rooms.start_day`·`rooms.end_day`가 둘 다 있으면 챌린지 방으로 판단한다 |
| CHA-02 | 방 참가+챌린지 참여 트랜잭션의 위치 | `chat` 모듈이 `challenge` 모듈의 공개 기능(`ChallengeReader` 또는 `ChallengeParticipationService`, 가칭)을 호출하는 방향으로 만든다 — 지금 Flutter가 `ChatCubit.joinRoom()`에서 `challengeCubit`을 호출하는 방향과 동일하고, Moderation을 Chat·Notification이 참조하는 기존 패턴과도 일치한다. `ChatApplicationService.joinRoom()` 안에서 방이 챌린지 방이면 같은 트랜잭션 안에서 참여까지 처리하고, REST로 별도 "챌린지 참여" 엔드포인트를 노출하지 않는다(방 참가 API 하나로 통합) |
| CHA-03 | 참여 멱등성 | `challenge`에 `(user_id, room_id)` 부분 유니크 인덱스(`room_id is not null`인 경우만)를 추가하고, insert는 `on conflict do nothing`으로 멱등 처리한다. 일반 참여(`room_id is null`)는 "현재 진행 중인 참여가 있는지" 조회 후 있으면 `409`로 막는다(기존 `enterChallenge()`의 `isEntered()` 검증과 동일한 정책 유지) |
| CHA-04 | 미션 진행 계산이 읽는 데이터 | Record가 아직 Spring에 없으므로, Challenge 모듈이 `feed`·`weight`를 읽기 전용으로 직접 조회해 진행·연속 성공을 계산한다(Expand 방식 임시 조치). Phase 5에서 Record가 이벤트(`MissionCompleted` 등)로 이관되면 직접 조회를 이벤트 구독으로 교체한다 |
| CHA-05 | 챌린지 기간·연속 성공 기준 | (승인됨, 2026-08-07) 챌린지는 기본 2주(14일), 성공 판정은 `_consecutiveDays >= 13` 그대로 서버에 옮긴다. 다만 온보딩 경로(`enterChallenge()`)가 7일로 하드코딩된 버그를 Phase 4 구현 시 14일로 고친다(§2 참고) |
| CHA-06 | 날짜 경계 처리 | `DateTime(y, m, d, -9)` 트릭 대신 서버에서 `ZoneId.of("Asia/Seoul")`로 명시적으로 KST 하루 경계를 계산한다 |

## 5. 목표 API 초안

| Method | Path | 역할 | 비고 |
|---|---|---|---|
| `GET` | `/api/v1/challenges/me` | 현재 참여 중인 챌린지 상태(참여 여부, 시작·종료일, 진행 일수, 연속 성공일수, 오늘 완료 여부, 최종 성공 여부, 오늘자 feed·weight 건수, 날짜별 미션 건수 목록) | `isEntered`+`getCurrentChallenges`+`getConsecutiveChallengeDays`+`getTodayMission`+`getCurrentChallengeCompletedDays`+`getSelectedDayMission`을 한 응답으로 대체 |
| `GET` | `/api/v1/challenges/me/history` | 종료된 챌린지 목록 | `fetchChallenge()` 대체 |
| `POST` | `/api/v1/challenges` | 일반(14일 고정) 챌린지 참여 | 이미 진행 중이면 `409 ALREADY_CHALLENGING`. 기존 온보딩 경로의 7일 하드코딩 버그를 14일로 수정 |

**계획 대비 추가된 것**: `GET /me` 응답의 `todayFeedCount`/`todayWeightCount`(미션 카드의 진행률 %), `dailyMissionCounts`(챌린지 시작일~오늘 날짜별 건수 목록)는 최초 API 초안에 없었다. `widgets/calendar.dart`의 `Calendar`가 실제로 살아있는 위젯이라 과거 날짜를 탭하면 `ChallengeCubit.selectDay()`가 호출되는데, 원래 계획한 응답만으로는 "오늘" 외의 날짜에 대한 진행률을 보여줄 수 없어 구현 중 추가했다.

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
| 4-B | 결정 항목 확정 | CHA-01~06 승인(2026-08-07) | 완료 |
| 4-C | `challenge.room_id` Expand + `spring_app` 권한 추가 | SQL 스크립트 작성·운영 DB(`ccpcclfqofyvksajnrpg`) 적용·`information_schema` 확인 완료 | 완료 |
| 4-D | Spring `challenge` 모듈 구현(조회·참여·진행 계산) | 코드 작성 완료(`ChallengeIntegrationTests`), 로컬 `./gradlew test` 확인 대기 | 코드 완료 |
| 4-E | `ChatApplicationService.joinRoom`에 챌린지 참여 원자 처리 연결 | `ChatIntegrationTests`에 챌린지 방 참가·재참가 시나리오 테스트 추가 | 코드 완료 |
| 4-F | Flutter `ChallengeCubit`을 Spring API 호출로 전환 | `challenge_api_client.dart` 신규, `challenge_cubit.dart` 전면 재작성, `chat_cubit.dart`의 `joinRoom` 롤백 로직 제거 | 코드 완료 |
| 4-G | 테스트·로컬 빌드 확인 | `ChallengeIntegrationTests`(신규)·`ChatIntegrationTests`(챌린지 연동 3건 추가) 작성 완료, 사용자 로컬 `./gradlew test`·`flutter analyze` 확인 필요 | 테스트 작성 완료 |
| 4-H | 안정화와 문서 동기화 | changelog·이 문서·위험 목록 갱신, 실기기 테스트는 전체 Phase 종료 후 일괄 | 완료 |

2026-08-07 구현: 위 CHA-01~06 결정을 그대로 승인해 계획→구현을 한 세션에서 진행했다(Phase 2와 동일한 방식, 사용자 요청). `challenge.room_id` Expand와 `spring_app` 권한(challenge INSERT/SELECT/UPDATE, feed/weight SELECT)을 운영 DB에 직접 적용했다. Spring `challenge` 모듈(domain/application/infrastructure/presentation)을 새로 작성하고, `ChatApplicationService.joinRoom()`이 챌린지 방이면 `ChallengeReader.enterForRoom()`을 같은 트랜잭션 안에서 호출하도록 연결했다 — 재참가(409) 요청에서도 챌린지 참여 보강은 계속 시도하도록 만들어, 기존에 Flutter가 매 `joinRoom()` 호출마다 `enterChallengeByDay()`를 다시 부르던 보강 동작을 원자적 멱등 처리로 재현했다. Flutter `ChallengeCubit`을 전면 재작성해 5개 Supabase 직접 쿼리를 `GET /api/v1/challenges/me` 한 번으로 대체하고, `ChatCubit.joinRoom()`의 앱 레벨 보상 트랜잭션(`enterChallengeByDay` 실패 시 `leaveRoom` 롤백)을 제거했다(서버가 원자적으로 처리하므로 더 이상 필요 없음). 구현 중 계획에 없던 추가 발견 2건을 처리했다: (1) 온보딩 경로(`enterChallenge()`)가 7일로 하드코딩되어 있어 13일 연속 성공 기준과 모순되던 기존 버그를 14일로 수정, (2) `widgets/calendar.dart`의 `Calendar`가 실제로 살아있는 위젯이라 과거 날짜 선택 시 미션 진행률을 보여줘야 해서 `GET /me` 응답에 `dailyMissionCounts`(시작일~오늘 날짜별 건수)를 추가했다. 이 세션 샌드박스에는 Java 21이 없어 직접 빌드하지 못했다 — 사용자 로컬 터미널에서 `./gradlew test`(특히 `com.udaadaa.challenge.*`, `com.udaadaa.chat.*`)와 `flutter analyze` 확인이 필요하다. 상세 변경 파일: §10 참고, 추가 검토가 필요한 항목은 [07-implementation-watchlist.md](../07-implementation-watchlist.md).

## 8. 선행 조건과 위험

- CHA-05(연속 성공 기준)는 코드 조사만으로 답을 낼 수 없는 제품 결정이다 — 구현 전에 반드시 확인한다.
- `feed`·`weight`를 Challenge가 직접 읽는 것(CHA-04)은 Record가 아직 그 데이터의 소유권을 Spring으로 옮기지 않은 상태에서의 임시 조치다. Phase 5에서 Record가 전환되면 이 직접 조회를 이벤트 구독으로 반드시 교체해야 한다 — 안 하면 Challenge가 Record의 테이블을 영구히 직접 참조하는 경계 위반이 굳어진다.
- `mission_complete` RPC는 여전히 `challenge`를 갱신하지 않는다. Phase 4가 참여·진행 조회를 서버로 옮겨도, 미션 인증 자체(Flutter → RPC)는 그대로라 인증 직후 진행 상태가 즉시 반영되지 않고 다음 조회 시점에야 반영된다 — Phase 5 전까지 남는 알려진 갭.
- `challenge.room_id` Expand 시점에 기존 데이터의 `room_id`를 백필할 방법이 마땅치 않다(기간과 사용자만으로는 어느 방이었는지 100% 확정할 수 없는 케이스가 있을 수 있음) — 기존 행은 `room_id null`로 남기고, 새 참여부터만 채우는 방향을 기본으로 한다.

## 9. 롤백 기준

- 참여 쓰기 전환 전에는 기존 Flutter 직접 insert 경로로 복귀할 수 있다.
- `challenge.room_id` Expand는 기존 스키마와 호환되므로(nullable 컬럼 추가) 언제든 되돌릴 수 있다.
- Chat의 `joinRoom`이 Challenge 참여까지 트랜잭션으로 묶은 뒤에는, 데이터 정합성을 확인하고 서버에서 복구한다. Flutter의 앱 레벨 보상 트랜잭션(`enterChallengeByDay` 실패 시 `leaveRoom` 호출)과 Spring의 원자적 트랜잭션을 동시에 활성화하지 않는다.

## 10. 변경 파일

**Spring (`com.udaadaa.challenge`, 신규)**

- `ChallengeReader.java` — Chat이 호출하는 공개 참여 함수
- `domain/ChallengeParticipation.java`, `domain/ChallengeRepository.java`
- `application/ChallengeApplicationService.java`, `application/ChallengeStatus.java`, `application/DailyMissionCount.java`, `application/AlreadyChallengingException.java`
- `infrastructure/ChallengeJpaEntity.java`, `infrastructure/SpringDataChallengeRepository.java`, `infrastructure/JpaChallengeRepository.java`
- `presentation/ChallengeController.java`, `presentation/ChallengeExceptionHandler.java`, `presentation/ChallengeStatusResponse.java`, `presentation/ChallengeHistoryItemResponse.java`, `presentation/DailyMissionCountResponse.java`

**Spring (`com.udaadaa.chat`, 수정)**

- `domain/ChallengeRoomPeriod.java` (신규) — 방이 챌린지 방인지 판단하는 최소 정보
- `domain/ChatRepository.java`, `infrastructure/JpaChatRepository.java` — `findChallengeRoomPeriod` 추가
- `application/ChatApplicationService.java` — `joinRoom()`이 `ChallengeReader.enterForRoom()`을 같은 트랜잭션에서 호출하도록 재작성

**DB**

- `udaadaa/supabase/migrations/20260807130000_add_room_id_to_challenge.sql` — `challenge.room_id` Expand + 부분 유니크 인덱스
- `udaadaa_server/scripts/db-admin/phase-04-spring-app-challenge-grants.sql` — `spring_app` 권한(challenge INSERT/SELECT/UPDATE, feed/weight SELECT)

**Flutter**

- `lib/data/challenge_api_client.dart` (신규)
- `lib/cubit/challenge_cubit.dart` — 전면 재작성(Supabase 직접 쿼리 제거, Spring API 호출로 전환)
- `lib/cubit/chat_cubit.dart` — `joinRoom()`의 챌린지 등록·롤백 로직을 `challengeCubit.refresh()` 호출로 교체
- `lib/view/home/home_view.dart` — 죽은 코드였던 `checkChallenger()`의 `isEntered()` 호출을 `refresh()`로 정리(실사용처는 없음)

**테스트**

- `udaadaa_server/src/test/java/com/udaadaa/challenge/ChallengeIntegrationTests.java` (신규)
- `udaadaa_server/src/test/java/com/udaadaa/chat/ChatIntegrationTests.java` — 챌린지 방 참가·재참가·일반 방 참가 시나리오 3건 추가
