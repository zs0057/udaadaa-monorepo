# 2026-08-07 Phase 3 채팅 실시간 스모크 테스트 트러블슈팅

> 관련 계획: [phase-03-chat-notification.md](../phases/phase-03-chat-notification.md)
> 선행 작업: [읽음 위치 STOMP 브로드캐스트 구현](2026-08-06-phase-03-3-3-implementation.md) 이후, Flutter 전환 A~D 전부 머지된 상태에서 진행
> 테스트 환경: iOS 시뮬레이터 2대(iPhone 17 Pro / iPhone 17 Pro Max) + 로컬 Spring(`SPRING_PROFILES_ACTIVE=local`)이 운영 Supabase(`ccpcclfqofyvksajnrpg`)에 `spring_app` Role·Session Pooler로 직접 연결. 실기기 테스트는 Apple 개발자 계정 서명 번거로움 때문에 보류하고 시뮬레이터로 대체(전체 회귀 테스트가 아니라 Phase 3 실시간 기능만 좁혀서 확인하는 목적).

## 테스트 준비 중 발견한 사전 이슈 (코드와 무관)

- CocoaPods 스펙 저장소가 오래돼 `pod install`이 `FBSDKCoreKit` 버전 충돌로 실패 — `pod repo update` → `pod update FBSDKCoreKit`로 해결. `facebook_app_events 0.20.0`이 요구하는 `FBSDKCoreKit ~>18.0`과 `Podfile.lock`에 고정된 `17.4.0`이 어긋나 있던 것으로, 이번 세션 변경과 무관한 로컬 환경 문제.
- 앱에는 "새 채팅방 만들기" 기능이 없다(방은 챌린지 시스템·백엔드에서만 생성됨, `joinRoomByRoomName`으로 "참가"만 가능). 테스트용 계정 2개(`testA`/`testB`)는 시뮬레이터에서 정상 회원가입으로 만들고, 테스트 방("Stomp Test")은 Supabase에 SQL로 직접 만들어 두 계정을 참가자로 연결했다.

## 발견한 버그 4건과 수정

### 1. 발신자 닉네임이 ID 값으로 보임 (간헐적)

**증상**: 채팅 메시지 말풍선 위 발신자 이름이 가끔 UUID 그대로 표시됨.

**원인**: `GET /rooms`가 내려주는 참가자 목록(`room.memberMap`)은 "현재" 참가자 기준이다. 그런데 메시지 발신자 닉네임 해석을 전부 이 캐시에만 의존하다 보니, 메시지를 보낸 뒤 방을 나간 사람이나 방금 참가해서 아직 캐시에 안 뜬 사람의 메시지는 프로필을 못 찾았다. `chat_view.dart`가 이 경우 `message.profile?.nickname ?? user`로 폴백해서 원본 senderId를 그대로 보여준다. 예전 Supabase Realtime 경로(`setChatEventsListener`)는 메시지마다 `profiles` 테이블을 직접 조회해서 이 문제가 없었는데, 이번 마이그레이션(Flutter 전환 A~D)에서 새로 만든 5개 경로(초기 메시지/이미지 로드, 이전 메시지 더보기, STOMP 실시간 수신, 재연결 gap-recovery)가 전부 캐시에만 의존하게 되면서 생긴 회귀다.

**수정**: `ChatCubit._fillMissingSenderProfiles(room, senderIds)` 헬퍼를 추가해, 캐시에서 못 찾은 발신자만 `profiles` 테이블에서 배치 조회(`inFilter`)로 보강하고 `room.memberMap`에도 채워 넣는다(다음부터는 캐시 히트). 위 5곳 전부에 적용. 캐시 미스가 없으면 추가 쿼리가 안 나간다.

### 2. 이미 참가 중인 방에 다시 들어가면 목록이 갱신 안 됨

**증상**: 초대 코드로 입장했는데 방 목록에 안 보임. 확인해보니 `room_participants`에는 정상적으로 참가자 행이 있었음(서버는 정상 동작).

**원인**: `ChatApplicationService.joinRoom`은 이미 참가 중이면 `409 ALREADY_JOINED`를 반환한다(정상 설계). 그런데 Flutter `ChatCubit.joinRoom()`은 이 409를 포함한 모든 예외를 동일하게 "실패"로 처리하고 `_loadRoomsAndMessages()`(목록 새로고침)를 건너뛴 채 바로 리턴했다. 첫 시도에서 참가 자체(`POST /participants`)는 성공했는데 그 직후 후처리(`_loadRoomsAndMessages` 등)가 어떤 이유로든 실패하면, 참가자 등록은 이미 멱등하게 끝나 있는데 로컬 목록만 영영 갱신 안 되는 상태로 남는 구조였다.

부수적으로, 기존 코드는 후처리 블록 전체를 하나의 try/catch로 묶어서 `_loadRoomsAndMessages()`가 실패해도 무조건 `JoinRoomSuccess()`를 emit하고 있었다 — 실패를 사용자에게 숨기는 셈이라 재시도할 기회도 없었다.

**수정**: `joinRoom()`을 세 단계로 분리했다.
1. `chatApiClient.joinRoom` 호출 — `DioException`이고 status가 409면 실패로 보지 않고 계속 진행(로그만 남김), 그 외 예외는 즉시 `JoinRoomFailed` emit 후 종료.
2. `_loadRoomsAndMessages()` — 이 단계가 실패하면 `JoinRoomFailed("방에 참가했지만 목록을 불러오지 못했습니다...")`를 emit하고 종료한다(거짓 성공 emit 금지). 참가 자체는 멱등이라 재시도하면 그대로 목록에 뜬다.
3. STOMP 구독 + 랭킹/챌린지 연동(부가 기능) — 이 단계는 실패해도 "방 참가" 자체는 성공으로 본다(기존 동작 유지).

### 3. 신규 계정에서 FCM 토큰 등록 시 DB 제약 위반

**증상**: 터미널에 `PostgrestException(message: null value in column "push_option" of relation "profiles" violates not-null constraint, code: 23502)`.

**원인**: `Profile.withPreservedNotificationFields(previous)`는 Spring 응답(`push_option`을 담지 않음)에 이전 로컬 프로필의 `pushOption`을 이어붙이는 역할인데, `previous`가 `null`(신규 가입 직후 첫 로드라 이어붙일 이전 값 자체가 없음)이면 결과도 `null`이 됐다. 이후 `AuthCubit._updateFCMToken`이 `profiles.upsert(profile.toMap())`를 호출하면서 `push_option: null`을 그대로 실어 보내 NOT NULL 제약을 위반했다. Phase 1(Flutter 전환) 때 생긴 기존 버그로, 이번 세션에서 만든 코드는 아니지만 신규 계정 테스트 중 처음 재현되어 같이 고쳤다.

**수정**: `pushOption: previous?.pushOption ?? true`로 기본값을 채웠다(신규 사용자는 푸시 알림 기본 켜짐, 기존 관례와 동일).

### 4. (가장 결정적) 방 목록 화면이 첫 렌더에서 멈춰 영영 안 뜸

**증상**: `flutter run` 로그에는 `💬 Chat List Count: 1`로 정상 로드됐다고 나오는데, 실제 채팅 탭 화면은 계속 완전히 비어 있음.

**원인**: `room_view.dart`의 방 목록 `BlocBuilder`는 `buildWhen: current is ChatMessageLoaded || current is UnreadMessagesUpdated || current is ChatMessagesRefreshedFromPush`로 재빌드 조건을 좁혀뒀다. 그런데 앱 시작 시 실행되는 `ChatCubit._initialize()`는 `_loadRoomsAndMessages()`로 데이터를 다 채운 뒤에도 이 세 상태 중 어떤 것도 `emit`하지 않았다. `BlocBuilder`는 최초 1회는 무조건 빌드하지만, 그 최초 빌드는 `_initialize()`의 네트워크 호출이 끝나기 전(=`chatList`가 아직 빈 배열일 때) 이미 일어난 뒤라, 이후 데이터가 채워져도 재빌드를 트리거하는 이벤트가 없어 화면이 빈 스냅샷에 영구히 멈춘다. 실시간 이벤트가 잦은 방에서는 STOMP 메시지 수신(`_ingestStompMessage`가 `emit(ChatMessageLoaded())`) 등이 우연히 화면을 깨워줘서 잘 드러나지 않았고, "Stomp Test"처럼 조용한 새 방에서 처음 재현됐다.

**수정**: `_initialize()`의 초기 로드 완료 직후 `emit(ChatMessageLoaded())`와 `emit(UnreadMessagesUpdated(unreadMessageCount, unreadMessages))`를 명시적으로 추가했다.

## 영향 범위와 확인

- 버그 1, 4는 Flutter 전환 A(2026-08-06 머지) 시점부터 존재했을 가능성이 높다 — 그동안 실사용에서 안 드러난 건 실시간 이벤트가 우연히 화면을 깨워줬기 때문으로 추정된다. 이번에 조용한 신규 테스트 방으로 처음 정면으로 재현됐다.
- 버그 2는 Flutter 전환 C(참가/나가기, 2026-08-06 머지)에서 생긴 회귀.
- 버그 3은 Phase 1(2026-07~08) Flutter 전환 때 생긴 기존 버그, 이번에 처음 재현·수정.
- 수정 후 testA 계정으로 "Stomp Test" 방이 정상적으로 뜨는 것 확인 완료. STOMP 실시간 송수신, 안읽음 배지 등 나머지 스모크 테스트 체크리스트는 별도 진행 중.

## 변경 파일

- `udaadaa/lib/cubit/chat_cubit.dart` — `_fillMissingSenderProfiles` 추가(5곳 적용), `joinRoom()` 409 처리·후처리 실패 처리 재작성, `_initialize()` 초기 로드 후 emit 추가
- `udaadaa/lib/models/profile.dart` — `withPreservedNotificationFields`의 `pushOption` 기본값
