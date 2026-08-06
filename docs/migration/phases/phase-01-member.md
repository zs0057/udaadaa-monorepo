# Phase 1: Member Migration

> 상태: 구현 중 (Spring 코드 완료, 실제 Supabase 비교 완료, Flutter 전환 코드 완료 — 실기기 회귀 테스트는 전체 Phase 완료 후 일괄 진행 예정)
> 시작일: 2026-07-29
> 진행 기록: [2026-07-29 Phase 1 진행 기록](../progress/2026-07-29-phase-01-progress.md), [2026-08-06 Flutter 전환 기록](../progress/2026-08-06-phase-01-flutter-transition.md)

## 1. 목적

Supabase Auth로 검증된 사용자를 Udaadaa 회원으로 연결하고, 기존 `profiles`의 조회와 수정을 Spring Member 모듈로 점진적으로 이전한다.

```text
Supabase Auth 로그인·JWT 발급
→ Spring JWT 검증
→ JWT sub로 Member 식별
→ Spring Member API
→ 기존 Supabase PostgreSQL profiles
```

Spring 전체가 완성될 때까지 기다리지 않는다. Member API를 검증한 뒤 Flutter의 프로필 기능만 먼저 전환하고, 다른 도메인은 각 Phase에서 별도로 이전한다.

## 2. 확인된 현재 상태

### 코드 기준

- `AuthCubit`이 로그인, 프로필 조회·생성, 닉네임·키·체중 수정, FCM token·Push 설정과 회원 탈퇴를 함께 처리한다.
- 익명 로그인과 소셜 로그인 후 `profiles`가 없으면 랜덤 닉네임으로 프로필을 생성한다.
- Flutter가 `profiles`를 직접 조회·삽입·수정·삭제한다.
- Flutter `Profile`은 `id`, `nickname`, `created_at`, `push_option`, `fcm_token`, `height`, `weight`를 사용한다.

### 배포 메타데이터 기준

2026-07-29 읽기 전용 조회로 다음을 확인했다. 사용자 행과 개인정보 값은 조회하지 않았다.

| 항목 | 확인 결과 |
|---|---|
| `profiles.id` | UUID PK, `auth.users.id` FK |
| `profiles.nickname` | NOT NULL, UNIQUE |
| RLS | 활성화 |
| SELECT | `authenticated` 전체 읽기 허용 |
| INSERT·DELETE | `auth.uid() = id` 기준 |
| UPDATE | `auth.uid() = id` 기준, `WITH CHECK` 없음 |
| Auth 사용자 수 | 2,990명 |
| Profile 수 | 2,973명 |
| Profile이 없는 Auth 사용자 | 17명 |

Profile이 없는 사용자가 실제로 존재하므로 누락 프로필 처리 규칙을 Phase 1에서 반드시 확정해야 한다.

## 3. 범위

### 포함

- 검증된 JWT `sub`와 기존 `profiles` 연결
- 내 프로필 조회
- 내 프로필 수정
- Profile이 없는 인증 사용자 처리
- 다른 모듈이 사용할 최소 회원 조회 기능
- 회원 상태 모델의 도입 시점 결정
- 기존 Flutter 결과와 Spring 결과 비교
- Flutter 프로필 조회·쓰기의 점진적 전환

### 제외

- Spring 자체 로그인과 JWT 발급
- 비밀번호·Apple·Kakao 인증 이전
- 회원 탈퇴 실제 처리
- Notification 소유 데이터의 물리적 이전
- 다른 도메인 데이터 삭제
- 기존 `profiles`와 외래키의 즉시 분리

## 4. 데이터 소유권

| 기존 필드 | Phase 1 책임 | 장기 목표 |
|---|---|---|
| `id` | Member | Member 식별자 |
| `nickname` | Member | Member 프로필 |
| `created_at` | Member | Member 생성 시각 |
| `height`, `weight` | Member | Member 프로필 |
| `fcm_token`, `push_option` | 기존 위치 임시 유지 | Notification으로 이전 |

Member API는 Notification 소유 후보 필드를 프로필 응답에 포함하지 않는다.

## 5. 결정 기록

| ID | 결정 항목 | 상태 | 결정·추천 |
|---|---|---|---|
| M-01 | Member ID | 확정 | `MemberId = profiles.id = JWT sub` UUID를 사용한다. 새 ID와 백필을 만들지 않는다. |
| M-02 | 누락 Profile | 확정 | 조회의 부수효과로 생성하지 않고 멱등 초기화 API로 생성한다. 일반 조회에서는 `404`를 반환한다. |
| M-03 | 수정 가능 필드 | 확정 | 초기에는 `nickname`, `height`, `weight`만 허용한다. |
| M-04 | 회원 상태 | 확정 | 상태 모델은 코드에 정의하고 기존 Profile은 `ACTIVE`로 해석한다. 운영 컬럼은 탈퇴 Phase에서 추가 여부를 결정한다. |
| M-05 | 공개 Profile | 확정 | 다른 모듈에는 `id`, `nickname`, `status`만 공개하고 여러 ID의 묶음 조회를 제공한다. 외부 사용자 조회 API는 아직 추가하지 않는다. |
| M-06 | 프로필 입력 규칙 | 확정 | 닉네임은 비공백·최대 30자·유일, 키는 50~250cm, 체중은 20~500kg로 검증한다. 기존 데이터는 자동 변경하지 않는다. |

## 6. 목표 API 초안

| Method | Path | 역할 | 인증 |
|---|---|---|---|
| `POST` | `/api/v1/members/me/initialize` | Profile이 없으면 생성하고 있으면 기존 Profile 반환 | 필수 |
| `GET` | `/api/v1/members/me` | 내 프로필 조회 | 필수 |
| `PATCH` | `/api/v1/members/me` | 내 닉네임·키·체중 일부 수정 | 필수 |

권한 원칙:

- 요청 body와 path의 사용자 ID를 본인 확인 근거로 사용하지 않는다.
- 검증된 JWT `sub`만 현재 Member ID로 사용한다.
- 다른 모듈은 Member Entity나 Repository를 직접 사용하지 않고 Member의 공개 기능을 사용한다.

오류 후보:

| HTTP | Code | 상황 |
|---:|---|---|
| `400` | `INVALID_REQUEST` | 프로필 입력값 오류 |
| `401` | `UNAUTHORIZED` | JWT 없음·오류 |
| `404` | `MEMBER_NOT_FOUND` | 인증됐지만 Profile 없음 |
| `409` | `NICKNAME_ALREADY_EXISTS` | 닉네임 중복 |
| `500` | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

## 7. 목표 모듈 구조

```text
member/
├─ presentation/     REST Controller와 요청·응답
├─ application/      회원 초기화·조회·수정 Use Case
├─ domain/           MemberId, MemberProfile과 규칙
└─ infrastructure/   기존 profiles JPA 연결
```

- 기존 `profiles`를 먼저 그대로 사용한다.
- 다른 모듈에 JPA Entity와 Repository를 공개하지 않는다.
- Member 공개 조회는 여러 ID를 한 번에 조회할 수 있게 설계하여 Chat의 반복 조회를 방지한다.

## 8. 실행 순서

| 단계 | 작업 | 완료 증거 | 상태 |
|---|---|---|---|
| 1-A | 기존 코드·Schema·RLS 조사 | 확인 사실과 위험 목록 | 완료 |
| 1-B | Member 규칙·API 계약 확정 | M-02~M-06 결정 | 완료 |
| 1-C | Spring 읽기 구현 | 조회·권한·기존 결과 비교 테스트 | 완료 |
| 1-D | Spring 초기화·수정 구현 | 멱등성·검증·중복 테스트 | 완료 |
| 1-E | Flutter Member 호출 전환 | 프로필 직접 조회·쓰기 제거(nickname/height/weight 범위) | 코드 완료 (실기기 검증 보류) |
| 1-F | 안정화와 문서 동기화 | 로그·오류·데이터 비교 결과 | 예정 (일괄 실기기 테스트 이후) |

2026-07-29 Testcontainers PostgreSQL에서 전체 16개 테스트가 통과했다. Member 초기화·조회·수정은 Docker 로컬 DB에서도 검증했다.

2026-08-06 실제 운영 Supabase 계정(개발자 본인 카카오 계정)으로 로그인해 Spring `GET /api/v1/members/me` 응답과 기존 Flutter 조회 결과가 일치함을 확인해 1-C를 완료로 전환했다. 같은 세션에서 Flutter `AuthCubit`의 조회·초기화·닉네임 수정 경로를 Spring API 호출로 전환했다(1-E 코드 작업 완료). 닉네임·키·몸무게 수정과 이메일·Apple 로그인 경로의 실기기 회귀 테스트는 개별 Phase마다 하지 않고, 전체 마이그레이션 Phase가 끝난 뒤 한 번에 일괄 진행하기로 결정했다(속도 우선, 개발 중 반복 테스트 비용 절감 목적). 이 결정에 따라 1-F(안정화)도 그 일괄 테스트 이후로 미룬다. 상세: [2026-08-06 Flutter 전환 기록](../progress/2026-08-06-phase-01-flutter-transition.md).

## 9. 전환 원칙

```text
기존 Flutter 조회 유지
→ Spring 조회 결과 비교
→ Spring 쓰기 검증
→ Flutter 조회를 Spring으로 전환
→ Flutter 쓰기를 Spring으로 전환
→ profiles 직접 쓰기 제거
```

- Shadow Read는 결과 비교용으로만 허용한다.
- 동일 프로필을 Flutter와 Spring이 동시에 수정하는 이중 쓰기는 금지한다.
- Flutter 구버전이 남는다면 최소 지원 버전과 강제 업데이트 여부를 전환 전에 결정한다.
- 기존 RLS와 Flutter 접근 권한은 구버전 정책이 정리되기 전까지 제거하지 않는다.

## 10. 검증 기준

- JWT `sub`와 같은 `profiles.id`만 내 회원으로 식별한다.
- 요청 body의 사용자 ID를 변경해도 다른 회원을 수정할 수 없다.
- 기존 Profile 조회 결과와 Spring 응답이 합의한 필드에서 일치한다.
- 초기화 API를 반복 호출해도 Profile이 하나만 존재한다.
- 닉네임 중복과 입력값 오류가 합의한 오류 코드로 반환된다.
- `fcm_token`과 비밀정보가 API 응답·로그에 노출되지 않는다.
- Member가 다른 도메인의 Repository를 직접 사용하지 않는다.
- Flutter의 Member 쓰기 전환 후 동일 기능의 Supabase 직접 쓰기가 남지 않는다.

## 11. 선행 조건과 위험

구현과 실제 Supabase 검증 전 Phase 0의 다음 항목이 필요하다.

- 실제 Supabase JWT 검증
- 운영 `spring_app` 로그인 Role과 최소 권한
- DB 연결 Secret 주입 방식

현재 주요 위험:

- Auth 사용자 17명에게 Profile이 없어 초기화·복구 정책이 필요하다.
- `profiles`가 많은 도메인의 외래키 기준이므로 Phase 1에서 물리적 분리를 시도하면 영향 범위가 커진다.
- 현재 UPDATE RLS에 `WITH CHECK`가 없어 Flutter 직접 접근이 남는 동안 보안 검토가 필요하다.
- FCM·Push 필드는 아직 `profiles`와 기존 Edge Function에서 사용하므로 Phase 1에서 제거할 수 없다.
- 실기기 회귀 테스트를 전체 Phase 종료 시점까지 미루기로 해, 이후 Phase에서 문제가 발견되면 어느 Phase의 변경이 원인인지 구분하기 어려울 수 있다. 일괄 테스트 시 Phase별로 순서대로 검증한다.

## 12. 롤백 기준

- Spring 쓰기 전에는 Flutter의 기존 Profile 조회 경로로 복귀할 수 있다.
- Spring 쓰기 전환 후에는 기존 `profiles`와 호환되는 API를 유지하며 서버를 수정한다.
- 장애 시 Flutter와 Spring 쓰기를 동시에 활성화하지 않는다.
