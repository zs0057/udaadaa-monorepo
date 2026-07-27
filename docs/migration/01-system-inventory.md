# Udaadaa System Inventory

## 1. 문서 목적

이 문서는 기존 Udaadaa의 사용자 기능과 코드 위치를 정리하고, 각 기능이 의존하는 Supabase 자원을 연결한다. 이후 AS-IS 데이터 흐름, 도메인 경계, 목표 아키텍처와 마이그레이션 순서를 결정하는 기준으로 사용한다.

이 문서는 저장소의 코드와 실제 배포된 Supabase 프로젝트의 메타데이터를 교차 검증한 **현행 시스템 Inventory**다. 배포 환경 검증은 2026-07-27에 읽기 전용으로 수행했으며 실제 사용자 데이터 행, 로그, 비밀 키와 토큰 값은 조회하지 않았다.

## 2. 조사 범위와 상태

### 포함 범위

- Flutter 화면, Cubit, 모델, 서비스와 데이터 접근 코드
- Supabase Auth, Database, RPC, Realtime, Storage와 Edge Function
- 저장소의 migration SQL, RLS, Policy와 DB Function
- 배포 DB의 Schema, RLS Policy, Function 권한, Trigger와 Realtime publication
- 배포 Storage Bucket, Edge Function 상태와 Supabase Advisor 결과
- Firebase Messaging, 외부 HTTP API, 분석 도구와 로컬 설정 등 연관 의존성
- 쓰기 정합성, 권한과 운영상 위험

### 제외 범위

- 실제 사용자 데이터 조회
- 비밀 키와 토큰 값 기록
- Edge Function 배포 소스, 서비스 로그와 Vault Secret 조회
- Auth Provider, Redirect URL과 Dashboard 전용 설정 확인
- 실제 사용자 계정으로 기능을 실행하는 런타임 검증
- 모든 DB 컬럼의 완전한 데이터 사전 작성
- 미사용 후보 삭제, 코드 수정과 Spring 설계 확정

### 조사 상태

| 구분 | 상태 | 설명 |
|---|---|---|
| Flutter 기능 조사 | 완료 | `lib/`의 화면, Cubit, 모델과 서비스 기준 |
| 코드의 Supabase 직접 호출 조사 | 완료 | Auth, Database, RPC, Realtime, Storage, Function 기준 |
| 저장소의 Supabase 자원 조사 | 완료 | Edge Function과 migration SQL 기준 |
| 코드와 저장소 자원 교차 검증 | 완료 | 호출 자원과 정의 자원을 양방향 비교 |
| 배포 환경 교차 검증 | 완료 | 사용자 데이터 없이 DB, RLS, RPC, Trigger, Realtime, Storage, Edge Function 메타데이터 대조 |
| 런타임 기능 검증 | 대기 | 실제 사용자 흐름과 장애·재연결 동작은 별도 검증 필요 |

## 3. 현행 구조 요약

- Flutter 화면 파일 54개, Cubit 8개, 모델 12개, 공용 위젯 19개가 확인됐다.
- 별도 Repository 계층은 확인되지 않았으며 대부분의 Cubit이 Supabase를 직접 호출한다.
- `supabase_flutter` 의존성은 `^2.6.0`이다.
- 배포 환경에는 테이블 14개, View 1개, DB Function 2개와 Storage Bucket 3개가 존재한다. 14개 테이블은 모두 RLS가 활성화되어 있다.
- 저장소에는 Edge Function 7개가 있으나 배포 환경에서 활성 상태로 확인된 Function은 5개다.
- Realtime은 활성 채널 하나에서 채팅 관련 3개 테이블의 4개 이벤트를 함께 구독하며, 세 테이블 모두 배포 publication에 등록되어 있다.
- 저장소와 배포 환경 모두 migration 47개가 확인됐다.
- 초기 채팅 데이터는 Supabase SDK가 아니라 환경변수로 지정된 HTTP endpoint를 직접 호출한다.

```text
Flutter View
→ Cubit
→ Supabase Auth / Database / Realtime / Storage / Edge Function
→ PostgreSQL Table / View / DB Function / RLS
```

## 4. 사용자 기능 Inventory

| ID | 기능 | 주요 코드 | 데이터·플랫폼 의존성 | 확인 상태 |
|---|---|---|---|---|
| F-01 | 인증·회원·프로필 | `auth_cubit.dart`, `splash_view.dart`, 로그인·마이페이지 화면 | Supabase Auth, `profiles`, `delete-auth-user`, FCM | 확인 |
| F-02 | 신규 사용자 온보딩 | `view/newonboarding/`, `shared_preferences.dart` | `profiles`, FCM, SharedPreferences, 로컬 알림 | 확인 |
| F-03 | 챌린지 참여·진행·결과 | `challenge_cubit.dart`, `view/home/challenge/`, `view/result/` | `challenge`, `feed`, `weight`, `report`, `reactions` | 확인 |
| F-04 | 채팅방 검색·참여 | `register_view.dart`, `enter_room_view.dart`, `ChatCubit.joinRoom*` | `rooms`, `room_participants`, `get-room-id-by-name`, `challenge` | 확인 |
| F-05 | 실시간 채팅 | `chat_cubit.dart`, `view/chat/` | `messages`, `read_receipts`, `chat_reactions`, 차단 테이블, Realtime, `ImageMessages` | 확인 |
| F-06 | 식단·체중·운동 인증 | `form_cubit.dart`, 인증 입력 화면, `ChatCubit.missionComplete` | `FeedImages`, 외부 칼로리 API, `mission_complete`, `feed`, `weight`, `messages`, `report` | 확인 |
| F-07 | 피드·반응·차단 | `feed_cubit.dart`, `feed_view.dart`, 기록 상세 화면 | `feed`, `random_feed`, `reactions`, `blocked_feed`, `FeedImages` | 확인 |
| F-08 | 건강 기록·리포트 | `profile_cubit.dart`, `report_view.dart`, 리포트 위젯 | `report`, `weight`, `feed` | 확인 |
| F-09 | 푸시·로컬 알림 | `main.dart`, `main_view.dart`, `notification_service.dart` | Firebase Messaging, FCM, `profiles.fcm_token`, push Edge Function | 확인 |
| F-10 | 분석·로컬 설정 | `utils/analytics/`, `shared_preferences.dart` | Firebase Analytics, Mixpanel, Amplitude, Facebook App Events, SharedPreferences | 확인 |

### 4.1 기능별 주요 근거

#### F-01 인증·회원·프로필

- 앱 진입과 인증 분기: `udaadaa/lib/view/splash_view.dart:21`
- 익명·이메일·Apple·Kakao 로그인: `udaadaa/lib/cubit/auth_cubit.dart:81`, `:291`, `:328`, `:362`
- 프로필 생성과 변경: `udaadaa/lib/cubit/auth_cubit.dart:206`, `:436`, `:534`
- 회원 탈퇴: `udaadaa/lib/cubit/auth_cubit.dart:557`
- 확인된 흐름:

```text
로그인 화면
→ AuthCubit
→ Supabase Auth
→ profiles 직접 조회·수정
→ 탈퇴 시 delete-auth-user Edge Function
```

#### F-03 챌린지

- 참여와 기간 기반 참여: `udaadaa/lib/cubit/challenge_cubit.dart:64`, `:90`
- 미션과 완료 일수 계산: `udaadaa/lib/cubit/challenge_cubit.dart:247`, `:411`
- 성공 판정과 결과: `udaadaa/lib/cubit/challenge_cubit.dart:463`, `udaadaa/lib/view/result/result_view.dart`
- 챌린지 규칙과 환급 안내 화면은 존재하지만 실제 결제·보증금 처리 코드는 확인되지 않았다.

#### F-04·F-05 채팅방과 실시간 채팅

- 방 이름으로 검색: `udaadaa/lib/cubit/chat_cubit.dart:1420`
- 방 참여와 참가자 저장: `udaadaa/lib/cubit/chat_cubit.dart:1460`
- 초기 채팅 데이터 HTTP 호출: `udaadaa/lib/cubit/chat_cubit.dart:119`
- 활성 Realtime 구독: `udaadaa/lib/cubit/chat_cubit.dart:1174`
- 메시지 전송과 삭제: `udaadaa/lib/cubit/chat_cubit.dart:1793`, `:1822`
- 이미지 메시지: `udaadaa/lib/cubit/chat_cubit.dart:1856`, `:1903`, `:1962`
- 읽음·반응·차단: `udaadaa/lib/cubit/chat_cubit.dart:1991`, `:2007`, `:2027`, `:2046`

#### F-06 미션 인증

- 외부 칼로리 추정 API: `udaadaa/lib/cubit/form_cubit.dart:153`
- 통합 DB RPC 호출: `udaadaa/lib/cubit/chat_cubit.dart:2085`
- 리포트 갱신: `udaadaa/lib/cubit/form_cubit.dart:326`
- 챌린지 미션 갱신: `udaadaa/lib/cubit/challenge_cubit.dart:463`

```text
이미지 업로드
→ 칼로리 계산 또는 수치 입력
→ mission_complete RPC
→ feed 또는 weight + messages 저장
→ report 갱신
→ challenge 완료 여부 재계산
```

#### F-07·F-08 피드와 리포트

- 피드 조회·페이지네이션: `udaadaa/lib/cubit/feed_cubit.dart:165`, `:208`, `:252`
- 피드 반응·차단·삭제: `udaadaa/lib/cubit/feed_cubit.dart:676`, `:729`, `:759`
- 일별·기간 리포트: `udaadaa/lib/cubit/profile_cubit.dart:59`, `:94`, `:117`, `:140`

## 5. Supabase Auth Inventory

| ID | 기능 | 코드 근거 | 비고 |
|---|---|---|---|
| AUTH-01 | 현재 사용자·세션 확인 | `auth_cubit.dart:21`, `chat_cubit.dart:1857` | 여러 Cubit이 전역 client의 사용자 ID를 직접 참조 |
| AUTH-02 | 인증 상태 구독 | `auth_cubit.dart:44` | 로그인 시 프로필 조회 또는 생성 |
| AUTH-03 | 익명 로그인 | `auth_cubit.dart:81` | 익명 사용자와 `profiles` 레코드를 함께 생성 |
| AUTH-04 | 이메일·비밀번호 로그인 | `auth_cubit.dart:291` | 로그인 후 `profiles` 직접 조회 |
| AUTH-05 | Apple 로그인 | `auth_cubit.dart:328` | nonce와 ID token 사용 |
| AUTH-06 | Kakao OAuth | `auth_cubit.dart:362`, `:377` | OAuth와 WebView 기반 흐름이 함께 존재 |
| AUTH-07 | 로그아웃 | `auth_cubit.dart:506` | Supabase Auth 세션 종료 |
| AUTH-08 | 회원 탈퇴 | `auth_cubit.dart:557` | profile 삭제 후 Auth admin 삭제를 순차 실행 |

### 주요 관찰

- 인증과 프로필 생성·FCM 토큰 갱신이 `AuthCubit`에 함께 존재한다.
- 회원 탈퇴는 `profiles` 삭제와 Auth 사용자 삭제가 하나의 트랜잭션이 아니므로 부분 실패 가능성이 있다.
- 앱 복귀와 이미지 업로드 전에 클라이언트가 직접 세션을 갱신한다.
- 실제 배포 환경의 익명 로그인, 이메일, Apple과 Kakao Provider 설정은 별도 확인이 필요하다.

## 6. Database와 View Inventory

| ID | 자원 | 주요 기능 | 클라이언트 동작 | 서버 측 규칙·부수효과 | 위험도 |
|---|---|---|---|---|---|
| DB-01 | `profiles` | 인증·프로필·푸시 | CRUD, upsert | Auth user FK, nickname unique, 다수 FK cascade의 시작점 | 높음 |
| DB-02 | `challenge` | 챌린지 | insert, select, update | 본인 쓰기, 전체 인증 사용자 읽기 | 중간 |
| DB-03 | `feed` | 인증 피드 | insert, select, delete | `mission_complete`도 insert, profile 삭제 시 cascade | 높음 |
| DB-04 | `random_feed` View | 피드 탐색 | select | 최종 migration은 `security_invoker=true` | 중간 |
| DB-05 | `reactions` | 피드 반응 | select, upsert | `(user_id, feed_id)` unique, push webhook 입력 전제 | 중간 |
| DB-06 | `blocked_feed` | 피드 차단 | select, upsert | `(user_id, feed_id)` unique | 낮음 |
| DB-07 | `report` | 일별 건강 기록 | select, upsert | `(user_id, date)` unique, 여러 클라이언트 쓰기 흐름 | 높음 |
| DB-08 | `weight` | 체중 기록·랭킹 | insert, select | `mission_complete`도 insert, 전체 인증 사용자 읽기 | 높음 |
| DB-09 | `rooms` | 채팅방 | select | 이름 unique, 생성 주체는 저장소에서 확인되지 않음 | 높음 |
| DB-10 | `room_participants` | 방 참가·알림 | select, insert, delete, update | 채팅 RLS와 Storage 권한의 기준 | 매우 높음 |
| DB-11 | `messages` | 채팅 | select, upsert, soft-delete update | Realtime 대상, RPC insert, room/profile cascade | 매우 높음 |
| DB-12 | `read_receipts` | 읽음 | select, upsert | Realtime 대상, `(user_id, message_id)` PK | 높음 |
| DB-13 | `chat_reactions` | 메시지 반응 | nested select, upsert | Realtime 대상, room/message/profile cascade | 높음 |
| DB-14 | `blocked_users` | 사용자 차단 | select, upsert | message push 수신자 필터에 사용 | 중간 |
| DB-15 | `blocked_messages` | 메시지 차단 | select, upsert | 초기 채팅 데이터 필터에 사용 | 중간 |

### RLS와 권한 관찰

- 배포된 14개 public 테이블은 모두 RLS가 활성화되어 있다.
- 일반 데이터는 대체로 `auth.uid()`와 `user_id`를 비교하여 본인 쓰기를 허용한다.
- `feed`, `challenge`, `weight` 등은 인증 사용자 전체 읽기 정책이 존재한다.
- 채팅 데이터는 `is_room_participant(room_id)` DB Function을 공통 권한 조건으로 사용한다.
- 배포된 `messages` update 정책은 방 참가 여부만 확인하며 작성자 제한과 `WITH CHECK`가 없다.
- 여러 Policy가 `public` 역할을 대상으로 하며 Supabase Security Advisor가 익명 접근 가능성 경고를 보고한다.
- 일부 update Policy는 `WITH CHECK`가 없어 변경 후 행의 소유권을 다시 검증하지 않는다.

## 7. RPC와 DB Function Inventory

| ID | Function | 역할 | 호출자 | 주요 위험 |
|---|---|---|---|---|
| RPC-01 | `mission_complete` | feed 또는 weight와 채팅 메시지 1~2건을 한 DB 트랜잭션으로 생성 | `ChatCubit.missionComplete` | 배포본이 `security definer`이고 anon 실행 가능, 내부 `auth.uid()`·방 참가 검증 없음 |
| RPC-02 | `is_room_participant` | 현재 Auth 사용자의 방 참가 여부 반환 | 채팅 RLS, Storage Policy | 배포본이 `security definer`이고 anon 실행 가능, 내부 `auth.uid()` 검증은 존재 |

### `mission_complete` 쓰기 범위

```text
feed_type = weight
→ weight insert
→ mission message insert

그 외
→ feed insert
→ mission message insert
→ review가 있으면 text message 추가 insert
```

저장소의 최종 정의는 `udaadaa/supabase/migrations/20250517093610_mission_complete_function_update.sql`이다. 배포본은 저장소와 같은 최종 시그니처이며 `mission_complete`와 `is_room_participant` 모두 anon·authenticated 역할이 실행할 수 있다. 두 Function 모두 고정된 `search_path`가 없고, 특히 `mission_complete`에는 호출자 본인과 방 참가 여부를 확인하는 로직이 없어 마이그레이션 전 별도 보안 조치가 필요하다.

## 8. Realtime Inventory

| ID | 채널 | 이벤트 | 후속 처리 | 상태 |
|---|---|---|---|---|
| RT-01 | `public:chat_events` | `messages INSERT` | 발신자 profile 추가 조회, 메시지·채팅 목록 갱신, 현재 방이면 읽음 저장 | 활성 |
| RT-02 | 같은 채널 | `messages UPDATE` | 삭제 표시 또는 메시지 교체 | 활성 |
| RT-03 | 같은 채널 | `chat_reactions INSERT` | 로컬 메시지 반응 갱신 | 활성 |
| RT-04 | 같은 채널 | `read_receipts INSERT` | 로컬 메시지 읽음 사용자 갱신 | 활성 |

### 주요 관찰

- 활성 구독은 `udaadaa/lib/cubit/chat_cubit.dart:1174`에서 설정된다.
- 모든 방의 네 이벤트를 필터 없이 한 채널에서 구독한다.
- `subscribe` 상태 callback이 없어 연결 성공·실패·재연결 상태를 명시적으로 관리하지 않는다.
- 메시지 INSERT 수신 후 `profiles`를 추가 조회하므로 UI 갱신 전 네트워크 왕복이 추가된다.
- 주석 처리된 과거 개별 채널 코드는 활성 의존성에서 제외했다.
- 배포된 `supabase_realtime` publication에는 `messages`, `chat_reactions`, `read_receipts`가 등록되어 있다.
- 세 테이블의 replica identity는 모두 `default`다.

## 9. Storage Inventory

| ID | Bucket | 사용 기능 | 경로 규칙 | 권한·접근 | 확인 사항 |
|---|---|---|---|---|---|
| ST-01 | `FeedImages` | 식단·운동·체중 인증, 피드 | `{userId}/{type}/{timestamp}.jpg` | 본인 폴더 쓰기, 인증 사용자 읽기 Policy | 배포 확인, public Bucket |
| ST-02 | `ImageMessages` | 채팅 이미지 | `{roomId}/{timestamp}_{userId}.jpg` | 방 참가자 insert/select | 배포 확인, public Bucket |
| ST-03 | `fallback-images` | 기본 피드 이미지 | 코드에 고정된 public URL | migration 정의 없음 | 배포 확인, public Bucket |

### 주요 관찰

- `FeedImages` 업로드: `udaadaa/lib/cubit/form_cubit.dart:110`
- `ImageMessages` 업로드: `udaadaa/lib/cubit/chat_cubit.dart:1856`, `:1903`
- 세 Bucket 모두 배포 환경에서 public이며 파일 크기와 MIME type 제한은 설정되어 있지 않다.
- `fallback-images`는 배포 환경에는 존재하지만 migration 정의가 없어 배포 설정 drift에 해당한다.
- 파일 업로드 성공 후 DB 쓰기가 실패할 때 업로드된 파일을 제거하는 보상 로직이 확인되지 않아 orphan 파일이 발생할 수 있다.

## 10. Edge Function Inventory

| ID | Function | 역할 | 호출·연결 근거 | 상태·확인 사항 |
|---|---|---|---|---|
| EF-01 | `delete-auth-user` | Auth admin 사용자 삭제 | Flutter `functions.invoke` | 배포 활성, JWT 검증 활성, 요청 `userId`와 JWT 주체 비교는 코드에서 미확인 |
| EF-02 | `get-room-id-by-name` | 방 이름으로 ID 조회 | Flutter `functions.invoke` | 배포 활성, JWT 검증 활성 |
| EF-03 | `post-initial-chat-data` | 초기 채팅 목록·메시지·읽음·차단 데이터 구성 | Flutter의 동적 HTTP endpoint 후보 | 배포 활성, JWT 검증 활성, 실제 endpoint 사용 여부 미확인 |
| EF-04 | `stage-post-initial-chat-data` | EF-03의 stage 버전 | Flutter의 동적 HTTP endpoint 후보 | 저장소에만 존재, 배포 목록에 없음 |
| EF-05 | `message-push` | 새 메시지 수신자 계산 후 FCM 전송 | `messages` INSERT Trigger | 배포 활성, JWT 검증 활성 |
| EF-06 | `reaction-push` | 피드 반응 발생 시 FCM 전송 | `reactions` INSERT Trigger | 배포 활성, JWT 검증 활성 |
| EF-07 | `hello` | 테스트 응답 | 호출 근거 없음 | 저장소에만 존재, 배포 목록에 없음 |

### 초기 채팅 Edge Function 접근 범위

- `blocked_users`, `blocked_messages`
- `room_participants`, `rooms`, `profiles`
- `messages`, `read_receipts`, `chat_reactions`
- 방 목록, 최근·초기·이미지 메시지, 읽음과 미읽음 수를 하나의 응답으로 구성

환경변수 `INITIAL_CHAT_END_POINT` 값은 Git에서 제외되어 있어 Flutter가 production과 stage 중 어느 Function을 호출하는지 저장소만으로 확정할 수 없다.

배포 DB에는 `messages` INSERT 후 실행되는 `message-push` Trigger와 `reactions` INSERT 후 실행되는 `reaction-push`, `my_webhook` Trigger가 존재한다. `reactions`의 두 Trigger가 같은 알림을 중복 발송하는지는 대상 endpoint와 런타임 동작을 추가로 확인해야 한다.

## 11. 외부·로컬 의존성

| ID | 의존성 | 용도 | 마이그레이션 영향 |
|---|---|---|---|
| EXT-01 | Firebase Messaging | 채팅·피드 Push, FCM token | Spring이 Push 발송 책임을 가져갈지 결정 필요 |
| EXT-02 | Flutter Local Notifications | 미션 로컬 알림 | 클라이언트에 유지 가능한 기능 |
| EXT-03 | 외부 칼로리 추정 HTTP API | 음식 이미지·설명 기반 칼로리 계산 | API 인증·오류·비용과 서버 이전 여부 확인 필요 |
| EXT-04 | Kakao·Apple | 소셜 로그인 | Supabase Auth 유지 기간과 Spring 인증 경계에 영향 |
| EXT-05 | Firebase Analytics·Mixpanel·Amplitude·Facebook App Events | 행동·오류 분석 | 이벤트 중복과 개인정보 정책 검토 필요 |
| EXT-06 | SharedPreferences | 온보딩·튜토리얼·알림 설정 | 서버 데이터와 로컬 데이터 구분 필요 |

## 12. 주요 쓰기 흐름과 정합성

| ID | 흐름 | 현재 순서 | 위험 |
|---|---|---|---|
| W-01 | 일반 미션 인증 | Storage upload → feed/weight insert → report upsert | 중간 실패 시 orphan 파일·부분 데이터 가능 |
| W-02 | 채팅방 미션 인증 | Storage upload → `mission_complete` → report 갱신 → challenge 재계산 | RPC 내부는 원자적이나 전체 흐름은 분산 |
| W-03 | 피드 삭제 | report 조회·계산·upsert → feed delete | 중간 실패 시 리포트와 피드 불일치 가능 |
| W-04 | 회원 탈퇴 | profile cascade delete → Auth admin delete → sign out | Auth와 서비스 데이터가 분리될 수 있음 |
| W-05 | 방 참여 | participant insert → 데이터 초기화·챌린지 생성, 실패 시 participant delete 시도 | 보상 삭제도 실패할 수 있음 |
| W-06 | 메시지 전송 | DB upsert → Realtime 이벤트 수신 후 UI 반영 | 발신자도 Realtime 왕복과 추가 profile 조회를 기다림 |

## 13. 보안과 운영 위험

실제 키·토큰·개인정보 값은 이 문서에 기록하지 않는다.

| ID | 우선순위 | 확인된 위험 | 근거 | 필요한 후속 조치 |
|---|---|---|---|---|
| SEC-01 | 긴급 | 인증 자격증명 형태의 값이 Flutter와 두 초기 채팅 Function 소스에 하드코딩됨 | `chat_cubit.dart`, `post-initial-chat-data`, `stage-post-initial-chat-data` | 유효성 확인, 폐기·회전, 환경변수화, Git 이력 점검 |
| SEC-02 | 긴급 | Git이 추적하는 `seed.sql`에 Auth·서비스·Storage·Vault TABLE DATA 구역이 존재 | `udaadaa/supabase/seed.sql` | 개인정보·비밀 포함 여부 격리 점검, 필요 시 이력 정리 |
| SEC-03 | 매우 높음 | `delete-auth-user`가 유효한 JWT를 요구하지만 요청 본문의 사용자 ID와 JWT 주체 비교는 코드에서 확인되지 않음 | Edge Function 코드, 배포 JWT 설정 | JWT 주체 일치 검증 추가 |
| SEC-04 | 매우 높음 | 배포된 초기 채팅 Function이 유효한 JWT를 요구하지만 service role로 요청 `userId`의 데이터를 조회 | Function 코드, 배포 JWT 설정 | JWT 주체 검증, 권한 범위 축소 |
| SEC-05 | 매우 높음 | 배포된 `mission_complete`가 `security definer`이고 anon 실행 가능하며 전달된 사용자·방을 내부에서 검증하지 않음 | 배포 Function 메타데이터와 코드 | 실행 권한 축소, `auth.uid()`, 방 참가, `search_path` 보강 검토 |
| SEC-06 | 높음 | 채팅 내용·차단 ID·사용자·Push 관련 정보가 로그에 기록될 수 있음 | Push와 초기 채팅 Function, 일부 Cubit | 로그 최소화와 민감정보 마스킹 |
| SEC-07 | 높음 | Trigger 3개, Realtime publication과 public Bucket 설정 일부가 migration에 완전히 표현되지 않음 | 배포 메타데이터와 migration 대조 | 배포 설정의 migration 편입과 drift 관리 |
| SEC-08 | 높음 | Supabase Advisor가 Security 경고 54개와 Performance 항목 17개를 보고 | 배포 Advisor | 경고별 영향 평가 후 보안 조치를 별도 계획으로 수립 |

Security Advisor의 주요 경고는 Function `search_path` 미고정, `security definer` Function의 anon·authenticated 실행 권한, 익명 접근 가능 Policy, 긴 OTP 만료 시간과 유출 비밀번호 보호 비활성화다. Performance Advisor는 미인덱스 FK 16개와 `messages` RLS의 반복적인 `auth.uid()` 평가 1개를 보고한다.

> 이 표는 위험 식별 결과이며 실제 악용 가능성을 확정한 보안 감사 결과는 아니다. 배포 설정과 키 유효성을 별도로 확인해야 한다.

## 14. 미사용 후보와 미확인 항목

### 미사용 후보

- `TutorialCubit`: 전역 Provider 등록이 없고 발견된 화면 사용부는 주석 상태
- `ResultListView`, `RecordView`, 일부 과거 온보딩 화면과 공용 위젯: 활성 진입점 추가 확인 필요
- `FoodFormView`, `WeightFormView`, `ExerciseFormView`: 별도 FAB 흐름과 함께 활성 여부 확인 필요
- 과거 개별 Realtime 채널과 signed URL 로직: 주석 상태
- `stage-post-initial-chat-data`, `hello` Edge Function: 저장소에는 있으나 배포 목록에 없음

### 배포 환경 확인 필요

- 실제 `INITIAL_CHAT_END_POINT`와 사용 중인 Edge Function 버전
- 배포된 Edge Function 소스와 저장소 소스의 정확한 일치 여부
- `reactions`의 두 INSERT Trigger가 호출하는 endpoint와 중복 알림 여부
- Dashboard의 Cron, Vault Secret과 기타 저장소 외 설정
- Auth Provider와 Redirect URL
- `rooms` 생성·관리 주체
- 실제 사용자 흐름과 Realtime 재연결·누락 복구 동작

## 15. 조사와 누락 검증 방법

### 기능에서 의존성으로 추적

```text
화면
→ Navigator 진입
→ Cubit public method
→ Supabase 또는 외부 API 호출
→ Table / Function / Storage / Realtime
```

### 의존성에서 기능으로 역추적

- Dart: `Supabase`, `.from()`, `.rpc()`, `.channel()`, `onPostgresChanges`, `.storage`, `.auth`, `functions.invoke`
- Edge Function: `createClient`, `.from()`, `auth.admin`, `fetch`, `Deno.env`
- SQL: Table, View, Function, Trigger, Policy, RLS, Storage Bucket, Publication과 Grant
- 호출 자원과 migration 정의를 양방향으로 비교하고, 연결되지 않은 항목은 미사용 후보 또는 배포 확인 항목으로 유지했다.

## 16. 완료 판단

코드와 배포 메타데이터 기반 Inventory는 다음 기준을 충족했다.

- 주요 사용자 기능과 진입 코드가 목록화됨
- 코드에 존재하는 직접 Supabase 호출이 기능 또는 미확인 항목에 연결됨
- Auth, Database, RPC, Realtime, Storage와 Edge Function이 유형별로 분류됨
- 배포된 Schema, RLS, Function 권한, Trigger, Realtime, Bucket과 Edge Function 상태가 교차 검증됨
- 고위험 쓰기 흐름, 권한과 부수효과가 식별됨
- 미사용 후보와 배포 환경 확인 항목이 분리됨
- 실제 비밀 값과 사용자 데이터가 문서에 포함되지 않음

현재 Inventory는 AS-IS 데이터 흐름과 시스템 구조를 작성할 수 있는 기준으로 사용한다. 런타임 기능 검증, Auth Provider와 Dashboard 전용 설정처럼 이번 읽기 전용 메타데이터 조사에서 확인하지 않은 항목은 후속 검증 대상으로 유지한다.
