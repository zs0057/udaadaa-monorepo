# Phase 5: Record + 미션 통합 Migration

> 상태: 구현 완료 (REC-01~08 2026-08-07 승인·구현, Spring·Flutter 코드 작성 완료 — 로컬 `./gradlew test`·`flutter analyze` 확인과 실기기 테스트만 남음)
> 시작일: 2026-08-07 / 구현일: 2026-08-07
> 선행 단계: [Phase 4: Challenge](phase-04-challenge.md) (머지 완료)
> 변경 매핑: [Migration Changelog §Phase 5](../06-migration-changelog.md#phase-5-record--미션-통합)
> 주의사항: [Implementation Watchlist](../07-implementation-watchlist.md)

## 1. 목적

식단·운동·체중 기록(Record)의 생성·조회와, 지금 Flutter가 `mission_complete` DB 함수 하나로 처리하는 "미션 인증"(Record 기록 + 일별 리포트 + Challenge 진행 + Chat 메시지)을 서버가 소유하는 하나의 흐름으로 옮긴다.

```text
Flutter 칼로리 추정(외부 API) → 사용자 확인/수정
→ Spring 미션 인증 API (이미지 업로드 승인 이후)
→ Record 기록 저장 + 일별 리포트 갱신 + Chat 미션 메시지 저장 (한 트랜잭션)
→ commit 이후 Challenge 진행 재계산·Social/Notification 후속 처리
```

로드맵(§9)이 정한 이번 단계 범위:

> 식단·운동·체중 기록 생성·조회·삭제 / 외부 칼로리 API 호출의 Spring 이전 / 일별 리포트 계산과 갱신 / 이미지 업로드 승인과 참조 검증 / Record·Challenge·Chat을 조정하는 미션 인증 Use Case / 기존 `mission_complete` Database Function 대체

## 2. 확인된 현재 상태 (코드 조사 결과)

### 지금의 "미션 인증"은 사실 세 번의 분리된 쓰기다

`ChatCubit.missionComplete()`(`lib/cubit/chat_cubit.dart:2243`)가 실제로 하는 일을 순서대로 보면:

1. **이미지 두 번 업로드**: `ChatCubit.uploadImage()`로 채팅 메시지용 이미지를(어느 버킷인지는 3-3에서 다룬 메시지 이미지 경로와 별개), `FormCubit.feedInfo()` 내부의 `FormCubit.uploadImage()`로 `FeedImages` 버킷에 피드용 이미지를 **각각** 올린다. 같은 사진 한 장을 두 곳에 두 번 업로드하는 구조다.
2. **`mission_complete` RPC 호출**(`supabase.rpc('mission_complete', ...)`) — `feed`(또는 `weight`) insert와 `messages` insert(`missionMessage` + 선택적 텍스트 메시지)는 이 RPC 안에서 원자적으로 처리된다. RPC는 `security definer`이고 호출자가 넘긴 `user_id`를 그대로 믿는다(내부에서 `auth.uid()`와 비교하지 않음 — System Inventory RPC-01, 이미 "높음" 위험으로 기록됨).
3. **RPC 성공 후**(같은 트랜잭션 아님) `FormCubit.updateReport()`가 `report` 테이블에 `upsert`한다. 이전 값을 먼저 `select`해서 `+=`으로 더한 뒤 다시 쓰는 **read-modify-write**라, 같은 사용자가 짧은 시간 안에 두 번 인증하면 경쟁 상태로 값이 유실될 수 있다. 이 upsert가 실패해도 1·2단계는 이미 커밋된 채로 남는다 — 지금도 "완전히 원자적"이지 않다.
4. **RPC 성공 후, report와 무관하게** `ChallengeCubit.updateMission()`이 호출된다. Phase 4 이후로는 이게 그냥 `GET /api/v1/challenges/me` 재조회다.

체중 전용 경로(`FormCubit.submitWeight()`)와 그 안의 `updateReport` 호출부(주석 처리된 `submit()` 메서드)는 **죽은 코드**다 — 실제 체중 인증도 `ChatCubit.missionComplete()`(`weight_second_view.dart`)를 통해서만 이뤄진다.

### 칼로리 추정은 미션 인증과 분리된 별도 호출

`FormCubit.calculate()`(`form_cubit.dart:153`)가 `dioClient.dio.post('/estimateCal', ...)`로 외부 API에 이미지+설명을 보내 칼로리를 추정한다. 이 호출은 `onboarding/fourth_view.dart`에서 먼저 일어나고, 결과(`Calorie`)를 `fifth_view.dart`(실제로는 "식단 인증 확인" 화면 — 이름만 온보딩)에 전달해 사용자가 칼로리 값을 **직접 수정할 수 있는 다이얼로그**를 이미 제공한다. 그 다음에야 `ChatCubit.missionComplete()`가 호출된다. 즉 "추정 → 확인/수정 → 커밋"이 이미 두 단계로 나뉜 UX이고, Phase 5는 이 구조를 유지하되 두 번째 단계(커밋)만 서버로 옮기면 된다 — 추정 실패 시 수동 입력이 이미 되는 경로가 있다는 뜻이다(추정 자체가 실패해 `Calorie` 객체가 없는 경우의 수동 입력 진입점은 없음 — §8 위험 참고).

### 스키마

| 테이블 | 확인 결과 |
|---|---|
| `feed` | `id`, `user_id`, `created_at`, `review`, `type`(FeedType enum: breakfast/lunch/dinner/snack/exercise/weight), `image_path`, `visibility`, `calorie`(bigint, null 허용), `is_challenge` |
| `weight` | `id`, `user_id`, `created_at`, `weight`(double), `date`, `image_path` |
| `report` | `id`, `user_id`, `date`, `created_at`, `breakfast`/`lunch`/`dinner`/`snack`/`exercise`(bigint, null 허용), `weight`(double, null 허용) — 유니크 키는 `(user_id, date)`로 추정(`upsert(onConflict: 'user_id, date')`로 사용 중) |
| `mission_complete` RPC | Phase 4 조사 때 이미 확인: `security definer`, 파라미터로 받은 `user_id`를 내부에서 검증하지 않음, `challenge`는 갱신 안 함 |

### Phase 4와의 접점

Phase 4의 CHA-04 결정(Challenge가 `feed`·`weight`를 읽기 전용으로 직접 조회)은 그대로 유지한다. Record가 이 두 테이블의 쓰기 소유권을 가져가도 Challenge의 조회 방식은 바뀔 필요가 없다 — 같은 테이블을 Record는 쓰고 Challenge는 읽는 구조가 된다(Notification이 `profiles.fcm_token`을 읽기만 하는 것과 같은 패턴). 다만 Challenge의 성공 판정이 "조회 시점에만" 계산되는 한계(compute-on-read, `07-implementation-watchlist.md` 기록됨)는 Phase 5에서 이벤트를 추가하면 개선할 수 있다(§4 REC-06).

### 발견한 위험

- **경쟁 상태**: `report` upsert의 read-modify-write 패턴은 동시 요청에 취약하다(이미 존재하는 문제, Phase 5가 원자적 SQL로 고쳐야 할 이유).
- **부분 실패 상태**: RPC는 성공했는데 `report` upsert가 실패하면 feed/message는 있는데 리포트엔 반영 안 된 상태로 영구히 남는다(재시도 로직 없음).
- **이미지 이중 업로드**: 실패 지점이 2배로 늘어난다 — 메시지 이미지는 성공했는데 피드 이미지는 실패하는 경우 등, 지금도 정합성 보장이 없다.
- **RPC-01**: `mission_complete`가 `user_id`를 파라미터로 받아 내부 검증 없이 사용 — 이론상 다른 사용자 명의로 기록을 만들 수 있다(Supabase RLS나 Edge Function 레이어에서 어떻게 막고 있는지는 별도 확인 필요, System Inventory에 이미 "높음"으로 기록된 기존 위험).

## 3. 범위

### 포함

- 식단·운동·체중 기록 생성·조회·삭제 API
- 일별 리포트 원자적 갱신(경쟁 상태 없는 SQL)
- 외부 칼로리 API 호출을 Spring이 대신 수행(API 키를 Flutter에 두지 않기 위함)
- 이미지 업로드 승인(Chat의 CHT-06 "경로만 발급" 패턴 재사용)
- 미션 인증(Record 기록 + 리포트 + Chat 메시지)을 하나의 서버 트랜잭션으로 처리하는 Use Case
- 같은 인증 요청 재전송 시 중복 생성 방지(멱등 키)
- 기존 `mission_complete` DB 함수 대체

### 제외

- Payment(보증금 환급·몰수) 연동 — Phase 4와 동일하게 이번 범위 밖
- Social의 피드 공개 노출·반응 — Phase 6에서 다룸. Record는 `feed`를 계속 소유하되 Social이 공개 조회만 가져간다
- 이미지 이중 업로드 구조 자체를 하나로 합치는 리팩터링은 REC-05 결정에 따라 범위 포함 여부가 갈림(아래 참고)

## 4. 결정 기록 (REC-01~08, 2026-08-07 전부 승인·구현 완료)

| ID | 결정 항목 | 승인된 내용과 실제 구현 |
|---|---|---|
| REC-01 | 미션 인증 트랜잭션의 소유 모듈 | 승인대로 새 `record` 모듈(`RecordApplicationService.commitMission`)이 오케스트레이터. `com.udaadaa.chat.ChatReader`(신설, `ChallengeReader`와 대칭)를 통해 Chat을 호출하고, Challenge는 호출하지 않는다(REC-06 결론과 연결 — 이번 Phase에서는 이벤트를 만들지 않기로 확정했기 때문) |
| REC-02 | 외부 칼로리 API 호출 시점 | 승인대로 "추정"과 "커밋"을 분리 유지. `POST /api/v1/records/calorie-estimates`가 대행 호출하고 실패 시 `502 CALORIE_ESTIMATION_FAILED`. 커밋 API(`POST /api/v1/records/missions`)는 이미 계산된 `calorie`/`weight`/`exerciseTime` 값만 받는다 |
| REC-03 | 이미지 업로드 승인 방식 | **제안과 다르게 구현**: "경로 하나만 발급해 이중 업로드를 없앤다"는 제안 대신, 기존 이중 업로드 구조(메시지 이미지는 CHT-06 `POST /chat/rooms/{id}/image-uploads`로, 피드 이미지는 Flutter가 `FeedImages`에 직접)를 그대로 유지하고 Record는 별도 업로드 승인 엔드포인트를 만들지 않았다. 이유는 §7 서술 참고 |
| REC-04 | 커밋 API 멱등성 | 승인대로 `clientRequestId`를 받는다. 구현은 제안(테이블 컬럼에 멱등 키 추가)과 다르게 전용 원장 테이블 `record_mission_commits`(client_request_id PK)를 새로 만들어 처리 — `feed`/`weight` 스키마를 건드리지 않고 재시도 시 이전 결과(feedId/weightId)를 그대로 반환한다 |
| REC-05 | `report` 갱신 방식 | 승인대로 원자적 `insert ... on conflict (user_id, date) do update set col = coalesce(report.col,0) + excluded.col` SQL로 교체(`JdbcRecordRepository.applyReportDelta`). Flutter `FormCubit.updateReport()`의 read-modify-write 호출은 제거했다(더 이상 호출되지 않는 죽은 메서드로 남김) |
| REC-06 | 미션 완료 후 Challenge 통지 방식 | **제안과 다르게 확정**: `MissionRecorded` 이벤트는 이번 Phase에서 만들지 않기로 했다. Phase 4가 이미 `GET /challenges/me`를 compute-on-read로 설계해 놨고(Challenge가 feed/weight를 직접 읽음, CHA-04), Record가 그 테이블의 쓰기 소유자가 돼도 이 조회 방식은 그대로 유효하다 — 별도 이벤트 없이도 다음 조회 때 정확한 값이 나온다. 즉시 통지가 필요해지면(예: 챌린지 성공 즉시 Push) 그때 이벤트를 추가해도 늦지 않다고 판단했다 |
| REC-07 | 칼로리 추정 실패 시 수동 입력 | 승인대로 커밋 API가 `calorie`/`weight`/`exerciseTime`을 모두 nullable로 받는다. Flutter UI에 수동 입력 진입점을 새로 만드는 작업은 **이번 범위에서 하지 않았다** — API 계약만 준비됐고 UI 작업은 별도 후속 과제로 watchlist에 남긴다 |
| REC-08 | `mission_complete` RPC 정리 시점 | 승인대로 이번 Phase에서 RPC 권한을 회수하지 않는다. Flutter가 Spring 경로로 전환됐지만 RPC 자체는 그대로 남아 있다(Phase 3 `message-push` Trigger와 동일한 원칙) |

## 5. 목표 API (실제 구현)

| Method | Path | 역할 | 비고 |
|---|---|---|---|
| `POST` | `/api/v1/records/missions` | 미션 인증 커밋(feed/weight+report+Chat 메시지 원자 처리) | `clientRequestId` 멱등(REC-04). `mission_complete` RPC 대체 |
| `GET` | `/api/v1/records/reports?date=` | 일별 리포트 조회 | |
| `DELETE` | `/api/v1/records/feed/{feedId}` | 내 피드 삭제(report 원자적 차감 포함) | `feed_cubit.dart deleteMyFeed()` 대체(REC-07 재활용 번호, 실제로는 기존 삭제 흐름 이전) |
| `POST` | `/api/v1/records/calorie-estimates` | 외부 칼로리 API 호출 대행 | REC-02, 실패 시 `502` |

**계획에서 뺀 것**: `GET /api/v1/records?date=`(범용 기록 목록 조회)와 `POST /api/v1/records/image-uploads`는 만들지 않았다 — 이유는 §7 "계획 대비 달라진 것" 참고. 일반 피드 목록 조회(`feed_cubit.dart`의 `fetchMyFeeds` 등)는 지금처럼 Flutter가 Supabase에서 직접 읽는다(RLS로 본인 데이터만 보이므로 안전) — 쓰기 소유권만 Record로 옮겼다.

오류 후보(실제 구현):

| HTTP | Code | 상황 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | JWT 없음·오류 |
| `404` | `FEED_NOT_FOUND` | 삭제 대상 피드가 없거나 본인 것이 아님 |
| `409` | `REPORT_ADJUSTMENT_FAILED` | 피드 삭제 시 리포트 데이터가 없거나 차감 결과가 음수가 됨(기존 "No report data"/"Negative report data" 방어 대체) |
| `502` | `CALORIE_ESTIMATION_FAILED` | 외부 칼로리 API 호출 실패 |
| `500` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류(방 참가자가 아닌 채로 미션 커밋을 시도하는 등 — §7 참고) |

## 6. 모듈 구조 (실제 구현)

```text
record/
├─ package-info.java            @ApplicationModule(displayName = "Record")
├─ domain/                      FeedType, MissionCommitResult, ReportSnapshot, FeedOwnership,
│                                CalorieEstimate, CalorieEstimator(포트), RecordRepository(포트)
├─ application/                 RecordApplicationService(commitMission/getReport/deleteMyFeed/
│                                estimateCalorie), MissionCommitCommand, 예외 3종
├─ infrastructure/               JdbcRecordRepository(JdbcTemplate 직접 사용, JPA 엔티티 없음),
│                                CalorieEstimateClient(java.net.http.HttpClient, FcmClient와 동일 패턴),
│                                CalorieApiProperties, RecordConfig
└─ presentation/                RecordController, RecordExceptionHandler, 요청/응답 DTO
```

- `RecordReader`(다른 모듈이 쓸 공개 조회 인터페이스)와 `MissionRecorded` 이벤트는 **만들지 않았다** — REC-06 결론(이벤트 없이 compute-on-read로 충분) 및 이번 Phase에서 Record를 참조하는 다른 모듈이 아직 없다는 이유로 범위에서 뺐다. 필요해지면 Phase 6(Social)에서 추가한다.
- Chat 쪽에 `com.udaadaa.chat.ChatReader`(신설, `ChallengeReader`와 대칭되는 루트 패키지 공개 인터페이스)를 추가했다. `ChatApplicationService`가 이를 구현하며, `recordMissionMessages(...)`가 `missionMessage`(+선택적 리뷰 `textMessage`)를 저장하고 각각 `ChatMessageCreated`를 발행해 일반 메시지와 동일하게 STOMP 실시간 전달·Push 알림이 나가도록 했다 — 이 부분은 기존에도 없던 개선이다(§7 참고).
- `record → chat` 방향의 새 모듈 의존성이 생겼다. Chat이 이미 Challenge를 참조하고 있어(`chat → challenge`) 순환은 없다(`record → chat → challenge`, 단방향).
- `spring_app` 권한: `feed` INSERT·DELETE, `weight` INSERT, `report` INSERT·UPDATE, 새 테이블 `record_mission_commits` SELECT·INSERT를 추가했다(SELECT는 Phase 4에서 이미 부여).
- **원장 테이블 신설(계획에 없던 결정)**: REC-04 제안은 `feed`/`weight`에 멱등 키 컬럼을 추가하는 것이었지만, 실제로는 `record_mission_commits(client_request_id pk, user_id, room_id, feed_id, weight_id, created_at)`라는 전용 원장 테이블을 새로 만들었다. 두 테이블 스키마를 건드리지 않고, "이 요청은 이미 처리됐다 + 그 결과가 무엇이었다"를 한 곳에서 관리할 수 있어 더 단순하다고 판단했다.

## 7. 실행 순서

| 단계 | 작업 | 완료 증거 | 상태 |
|---|---|---|---|
| 5-A | 이 문서 조사·계획 | §2 | 완료 |
| 5-B | 결정 항목 확정 | REC-01~08 승인(2026-08-07) | 완료 |
| 5-C | `record_mission_commits` 테이블 신설 + `spring_app` 쓰기 권한 확장 | 마이그레이션·grants SQL 적용·확인(Supabase MCP) | 완료 |
| 5-D | Chat에 `ChatReader.recordMissionMessages` 모듈 간 API 추가 | `ChatApplicationService` 구현 | 완료 |
| 5-E | Spring `record` 모듈 구현(미션 커밋·리포트 조회·피드 삭제·칼로리 대행) | `RecordIntegrationTests` 작성 | 코드 완료(로컬 `./gradlew test` 확인 대기) |
| 5-F | Flutter `FormCubit`/`ChatCubit.missionComplete`/`FeedCubit.deleteMyFeed`를 Spring API 호출로 전환 | 코드 전환 완료 | 코드 완료(`flutter analyze` 확인 대기) |
| 5-G | 안정화 관찰 후 `mission_complete` RPC 권한 회수 | 운영 지표 확인 | 보류(REC-08, 이번 범위 아님) |
| 5-H | 문서 동기화(changelog, watchlist) | 이 절 | 완료 |

### 2026-08-07 구현: 계획 대비 달라진 것

- **스코프를 좁혔다**: 계획 §5의 `GET /api/v1/records?date=`(범용 기록 목록)와 `POST /api/v1/records/image-uploads`는 만들지 않았다. 실제로 "미션 통합"에 꼭 필요한 것은 커밋(쓰기)이었고, 목록 조회는 이미 `feed_cubit.dart`/`profile_cubit.dart`가 Supabase에서 직접 읽고 있으며 RLS로 안전하게 보호돼 있어 이번 범위에서는 건드리지 않기로 판단했다. 이미지 업로드도 마찬가지 이유로 새 엔드포인트를 만들지 않았다 — 메시지 이미지는 Chat의 기존 CHT-06 엔드포인트를, 피드 이미지는 기존처럼 Flutter가 `FeedImages`에 직접 업로드하는 경로를 그대로 뒀다(REC-03 제안이었던 "업로드 하나로 합치기"는 하지 않음 — 기존 이중 업로드 구조를 유지).
- **원장 테이블 방식으로 멱등성 구현**: REC-04는 `feed`/`weight`에 멱등 키 컬럼을 추가하는 안이었지만, 실제로는 `record_mission_commits` 전용 테이블을 새로 만들어 재시도를 감지한다(§6 참고).
- **`sequence` 트리거 덕분에 메시지 순서 트릭이 필요 없어졌다**: 기존 `mission_complete` RPC는 리뷰 텍스트 메시지의 시각을 `now() + interval '1 millisecond'`로 밀어 순서를 보장했다. Phase 3-1에서 추가된 `messages.sequence`(DB 트리거로 자동 채번)가 이미 삽입 순서를 보장하므로 이 트릭은 재현하지 않았다.
- **의도적으로 고친 것 — report 원자성(REC-05)**: 기존 `FormCubit.updateReport()`의 select-then-upsert 패턴을 완전히 제거하고, Spring의 단일 원자적 `upsert` SQL로 대체했다. Flutter는 이제 서버가 반영한 값을 다시 읽어오기만 한다(`missionComplete()`에서 `updateReport()` 호출을 뺐다) — 이걸 빼지 않으면 서버가 이미 반영한 값 위에 또 더해져 이중 집계가 났을 것이다.
- **의도적으로 고친 것 — missionMessage의 실시간 전달**: 기존에는 `mission_complete` RPC가 `messages`에 직접 insert했는데, Phase 3에서 Flutter가 메시지 실시간 갱신을 Supabase Realtime에서 STOMP로 전환한 뒤로는 이 RPC 경로의 메시지가 다른 참가자에게 실시간으로 안 보였을 가능성이 있다(Phase 3 자체가 만든 회귀는 아니고, 두 Phase 전환 사이의 과도기적 gap). 이번에 `ChatReader.recordMissionMessages`가 일반 메시지와 동일하게 `ChatMessageCreated`를 발행하도록 만들어 이 gap을 없앴다.
- **의도적으로 남겨둔 것 — 알려진 동작**: `feed_cubit.dart`의 기존 `deleteMyFeed()`는 breakfast/lunch/dinner/snack 타입만 report를 차감하고 exercise 타입은 차감하지 않았다(운동 기록을 지워도 `report.exercise`가 줄지 않음). 이 동작을 그대로 재현했다 — 고의적인 설계인지 기존 버그인지 확실하지 않아 이번 Phase에서 임의로 바꾸지 않았다(watchlist에 기록).
- **calorie-estimates 응답 포맷**: Flutter의 `Calorie.fromJson()`이 외부 서비스와 동일한 snake_case 키(`total_calories`/`items`/`ai_text`)를 기대해서, Spring 응답 DTO에 `@JsonProperty`로 명시적으로 snake_case를 강제했다(Spring의 기본 직렬화는 camelCase라 이 부분을 놓치면 Flutter 파싱이 조용히 깨졌을 것).
- **`RoomNotFoundException`이 모듈 경계를 넘지 못한다**: `ChatReader.recordMissionMessages`가 방 참가자가 아닌 사용자에 대해 `com.udaadaa.chat.application.RoomNotFoundException`(패키지 비공개 서브패키지)을 던지면, `record` 모듈은 이를 특정 HTTP 코드로 매핑하지 못하고 전역 `GlobalExceptionHandler`의 일반 오류로 떨어진다(500). 실제로는 미션 인증이 항상 "지금 열려 있는 방"을 대상으로 하므로 정상 사용에서는 발생하지 않아야 하는 경로지만, 깔끔한 에러 코드가 아니라는 점은 알아둘 부분이다.

## 8. 선행 조건과 위험

- ~~`report`의 유니크 키 확인~~ — **해결**: Supabase에서 직접 조회해 `user_date_unique UNIQUE (user_id, date)` 제약을 확인했다. `on conflict (user_id, date)`가 정확한 대상이다.
- ~~Modulith 순환 의존 여부~~ — **해결**: `record → chat → challenge` 단방향이라 순환이 없다. Chat이 Record를 참조하지 않는다.
- REC-02(칼로리 API를 커밋 트랜잭션 밖에 둠)는 여전히 "이미지 업로드는 성공했는데 커밋은 안 된" 상태를 만들 수 있다 — Phase 3-3에서 이미지 업로드 정리 미구현을 그대로 뒀던 것과 같은 종류의 갭이고, 이번에도 정리 배치는 범위 밖으로 뒀다(watchlist에 기록).
- 칼로리 추정이 처음부터 실패한 경우(REC-07) Flutter UI에 수동 입력 진입점을 이번 Phase에서 만들지 않았다 — API 계약만 준비됐다(watchlist에 기록).
- `RoomNotFoundException`이 record 모듈 경계를 넘지 못해 일반 500으로 떨어지는 갭(§7 참고, watchlist에 기록).
- `CALORIE_API_URL` 환경변수가 아직 배포 환경에 설정되지 않았다면 칼로리 추정 API가 즉시 500(`IllegalStateException` → 전역 핸들러)을 반환한다 — 기존 Flutter dotenv `API_URL`과 같은 값으로 설정해야 한다.

## 9. 롤백 기준

- 커밋 API 전환 전에는 기존 `mission_complete` RPC 경로로 복귀할 수 있다.
- 커밋 쓰기가 시작된 후에는 생성된 `feed`/`weight`/`report`/`messages` 데이터의 호환성을 확인하고 서버에서 복구한다. Flutter의 RPC 직접 호출과 Spring 커밋 API를 동시에 허용하지 않는다.
- 이미지 업로드만 성공하고 커밋이 실패한 요청은 임시 파일 정리 대상으로 남긴다(Phase 3-3과 동일한 원칙 — 이번 Phase에서도 자동 정리는 만들지 않는다).

## 10. 변경 파일

**DB(운영에 직접 적용, Supabase MCP)**
- `udaadaa/supabase/migrations/20260807140000_add_record_mission_commits.sql` — `record_mission_commits` 신설
- `udaadaa_server/scripts/db-admin/phase-05-spring-app-record-grants.sql` — `feed` INSERT·DELETE, `weight` INSERT, `report` INSERT·UPDATE, `record_mission_commits` SELECT·INSERT

**Spring — `com.udaadaa.chat`(수정)**
- `ChatReader.java`(신설) — `recordMissionMessages(...)` 공개 인터페이스
- `application/ChatApplicationService.java` — `ChatReader` 구현, `recordMissionMessages`/`saveAndPublish` 추가

**Spring — `com.udaadaa.record`(신설)**
- `package-info.java`
- `domain/FeedType.java`, `MissionCommitResult.java`, `ReportSnapshot.java`, `FeedOwnership.java`, `CalorieEstimate.java`, `CalorieEstimator.java`, `RecordRepository.java`
- `application/RecordApplicationService.java`, `MissionCommitCommand.java`, `FeedNotFoundException.java`, `ReportAdjustmentFailedException.java`, `CalorieEstimationFailedException.java`
- `infrastructure/JdbcRecordRepository.java`, `CalorieEstimateClient.java`, `CalorieApiProperties.java`, `RecordConfig.java`
- `presentation/RecordController.java`, `RecordExceptionHandler.java`, `MissionCommitRequest.java`, `MissionCommitResponse.java`, `ReportResponse.java`, `CalorieEstimateRequest.java`, `CalorieEstimateResponse.java`
- `src/main/resources/application.yml` — `app.record.calorie-api.base-url`(`CALORIE_API_URL`) 추가

**Flutter**
- `lib/data/record_api_client.dart`(신설) — `commitMission`/`deleteMyFeed`/`estimateCalorie`
- `lib/cubit/chat_cubit.dart` — `missionComplete()`를 `recordApiClient.commitMission` 호출로 재작성
- `lib/cubit/form_cubit.dart` — `calculate()`를 `recordApiClient.estimateCalorie` 호출로 재작성, `missionComplete()`에서 `updateReport()` 호출 제거(REC-05), `updateReport()`는 죽은 코드로 남김
- `lib/cubit/feed_cubit.dart` — `deleteMyFeed()`를 `recordApiClient.deleteMyFeed` 호출로 재작성, 이제 안 쓰는 `report.dart` import 제거

**테스트**
- `udaadaa_server/src/test/java/com/udaadaa/record/RecordIntegrationTests.java`(신설) — 미션 커밋(식사/체중/멱등 재시도), 리포트 조회, 피드 삭제(성공/음수 방어/404) 7개 테스트
