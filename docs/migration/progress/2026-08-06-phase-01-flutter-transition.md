# 2026-08-06 Phase 1 Flutter 전환 기록

## 1. 현재 상태

- Flutter `AuthCubit`의 프로필 조회·초기화·수정 경로를 Spring Member API 호출로 전환했다.
- `fcm_token`, `push_option`, 회원 탈퇴(`profiles` 삭제)는 Spring Member API가 아직 다루지 않는 범위라 계속 Supabase `profiles`를 직접 사용한다.
- 로컬 iOS 시뮬레이터에서 실제 운영 Supabase(`ccpcclfqofyvksajnrpg`) + 로컬 Spring(`spring_app` 계정, Session Pooler 연결) 조합으로 동작을 확인했다.
- 오늘 검증은 개발자 본인 카카오 계정(운영 실사용자 데이터)과 익명 로그인 흐름으로 진행했다. 이메일·Apple 로그인 경로는 코드는 전환했으나 오늘 실기기 테스트는 하지 않았다.

## 2. 배경

Phase 0 완료 후 남은 Phase 1 작업 중 1-E(Flutter Member 호출 전환)를 진행했다. 원래 계획은 "테스트 계정으로 구조 검증만" 하는 것이었으나, 실제로는 개발자 본인의 기존 카카오 계정으로 로그인하게 되어 1-C(실제 Supabase profiles와 Spring 조회 결과 비교)도 함께 검증됐다.

## 3. 변경 사항

### 신규 파일

| 파일 | 역할 |
|---|---|
| `lib/data/member_api_client.dart` | Spring Member API(`/api/v1/members/me*`) 전용 Dio 클라이언트. `Authorization: Bearer <Supabase access token>`을 요청마다 자동 첨부 |

### 수정 파일

| 파일 | 변경 |
|---|---|
| `lib/models/profile.dart` | `Profile.fromSpringMap()`(Spring 응답 파싱), `withPreservedNotificationFields()`(fcm_token·push_option을 이전 값에서 이어받는 병합) 추가 |
| `lib/utils/constant.dart` | `springApiUrl` 추가 (`.env`의 `SPRING_API_URL`, 기본값 `http://localhost:8080`) |
| `lib/cubit/auth_cubit.dart` | 아래 4절 참고 |
| `.env` (gitignore 대상, 저장소 미포함) | `SPRING_API_URL=http://localhost:8080` 추가 |

### `auth_cubit.dart` 전환 상세

| 메서드 | 이전 | 이후 |
|---|---|---|
| 생성자 초기 프로필 조회 | `profiles.select().single()` | `GET /api/v1/members/me` |
| `onAuthStateChange` 리스너(카카오·애플) | `profiles.select().maybeSingle()` | `GET /api/v1/members/me` (404 처리 포함) |
| `_anonymousLogin` | insert + 닉네임 충돌 재시도 루프(~50줄) | `POST /api/v1/members/me/initialize` 한 번 호출 |
| `makeProfile` | insert + 닉네임 충돌 재시도 루프(~40줄) | `POST /api/v1/members/me/initialize` 한 번 호출 |
| `updateNickname` | `profiles.update()` | `PATCH /api/v1/members/me` |
| `updateProfile`(height/weight) | 낙관적 로컬 갱신 후 Supabase 업데이트(실패해도 UI는 갱신됨) | 서버 응답 성공 시에만 `_profile` 갱신 (검증 실패를 UI에 정확히 반영) |
| `signInWithEmail` 프로필 조회 | `profiles.select().single()` + `_profile != null` 오타(널 체크 반대로 되어 있어 최초 로그인 시 크래시 가능했던 기존 버그) | `GET /api/v1/members/me`로 전환하며 널 체크 조건도 같이 수정 |
| `signInWithKakaoByWebView` 프로필 조회 | `profiles.select().maybeSingle()` | `GET /api/v1/members/me` |

**닉네임 생성 책임 이동**: 기존에는 Flutter가 랜덤 닉네임을 만들고 유니크 제약 위반(`23505`) 시 클라이언트가 재시도했다. Spring의 `initialize` API가 이 로직을 서버에서 전담하므로(`MemberApplicationService.initialize`, 최대 5회 내부 재시도) Flutter 쪽 재시도 코드는 전부 제거했다.

**변경하지 않은 부분** (Spring Member API 범위 밖):
- `setFCMToken`, `_setFCMToken`, `_updateFCMToken`, `turnOffPush`, `togglePush` — `fcm_token`·`push_option`은 계속 `profiles` 직접 upsert
- `withdrawAccount`의 `profiles.delete()` — 회원 탈퇴는 Phase 1 범위 밖(Phase 7)

## 4. 환경 설정 트러블슈팅 (참고용)

오늘 로컬 iOS 실행 환경을 처음부터 구축하면서 겪은 문제와 해결책. 다음에 같은 문제가 재발하면 참고한다.

| 문제 | 원인 | 해결 |
|---|---|---|
| `pod install` 시 "specs repository too out-of-date" | 실제로는 `facebook_app_events` 버전업으로 `Podfile.lock`의 `FBSDKCoreKit` 고정 버전과 충돌 | `ios/Podfile.lock`, `ios/Pods` 삭제 후 재설치 |
| Xcode `could not find included file 'Config.xcconfig'` | gitignore된 로컬 전용 설정 파일이 아예 없었음 | 팀 백업 값으로 `ios/Config.xcconfig` 재생성 (Facebook App ID/Client Token) |
| `flutter run`은 실패하는데 Xcode 직접 실행은 성공 | Swift Package Manager 캐시가 `firebase-ios-sdk`가 요구하는 `swift-protobuf` traits와 불일치 | `~/Library/Developer/Xcode/DerivedData`, `~/Library/Caches/org.swift.swiftpm`, 프로젝트 내 `Package.resolved` 삭제 후 재해석 |
| Member API 호출이 전부 401 | IntelliJ 초록 버튼으로 Spring을 켜면 `SUPABASE_JWT_SECRET` 등 환경변수가 실제로 프로세스에 전달되지 않고 `application-local.yml`의 더미 값으로 대체됨 (Phase 0에서도 동일 증상) | Spring은 항상 터미널에서 `./gradlew bootRun`에 환경변수를 직접 지정해 실행하기로 정리 |

또한 `.env`, `ios/Config.xcconfig`, `firebase.json`, `ios/Runner/GoogleService-Info.plist`, `android/app/google-services.json`이 모두 gitignore 대상이라 로컬에 없었다. 팀에서 보관 중인 백업 값으로 복원했다.

## 5. 환경 결정: 로컬 개발과 운영 DB

로컬 Spring·Flutter 개발을 계속 운영 Supabase DB(`ccpcclfqofyvksajnrpg`)에 직접 연결하는 방식으로 유지하기로 했다 (Staging 프로젝트로 전환하는 대안도 검토했으나, 오늘은 시간 관계상 보류). 리스크와 대안은 알고 있는 상태이며, 필요 시 이후 별도로 Staging 전환을 진행한다.

## 6. 검증한 것 / 안 한 것

검증함:
- 익명 로그인 → Spring `initialize` → 프로필 생성·표시
- 기존 카카오 계정 로그인 → Spring `GET /members/me` → 기존 운영 데이터 정상 조회

검증 못 함 (다음 세션 확인 필요):
- 닉네임 변경(`PATCH`) 실기기 테스트
- 키·몸무게 변경(`PATCH`) 실기기 테스트
- 이메일·Apple 로그인 경로 실기기 테스트
- `flutter analyze` 정적 분석 결과
- 기존 Supabase 직접 쓰기 완전 제거 여부의 회귀 테스트 (fcm_token/push_option 경로 정상 동작 확인)

## 7. 다음 작업

1. 위 "검증 못 함" 항목 실기기 테스트
2. `flutter analyze`로 컴파일·린트 이상 없는지 확인
3. iOS 빌드 관련 로컬 변경 파일(`Podfile.lock`, `project.pbxproj` 등)을 검토 후 필요한 것만 별도로 커밋할지 결정
4. 안정성 확인되면 Phase 1 1-F(안정화) 완료 처리 및 `phase-01-member.md` 갱신
