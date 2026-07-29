# 2026-07-29 Phase 1 진행 기록

## 1. 현재 상태

- Phase 0 공통 기반 위에 Member 모듈의 초기화·조회·수정 기능을 구현했다.
- Docker 로컬 PostgreSQL과 Testcontainers 자동 테스트에서 동작을 검증했다.
- 실제 Supabase 프로젝트 비교와 Flutter 호출 전환 전이므로 Phase 1 상태는 `구현 중`으로 유지한다.

## 2. 오늘 완료한 작업

| 구분 | 결과 |
|---|---|
| Member API | 내 Profile 초기화·조회·부분 수정 구현 |
| 인증·권한 | 검증된 Supabase JWT의 `sub`만 Member ID로 사용 |
| 데이터 연결 | 기존 `public.profiles`를 JPA Entity로 연결 |
| 초기화 정책 | Profile이 없을 때만 생성하는 멱등 API 구현 |
| 입력 검증 | 닉네임·키·체중 검증과 닉네임 중복 처리 |
| 오류 응답 | `400`, `401`, `404`, `409`를 공통 `ApiError` 형식으로 반환 |
| 모듈 공개 기능 | 다른 모듈에 `MemberReader`와 `MemberSummary`만 공개 |
| 로컬 환경 | Docker PostgreSQL, `local` Profile과 로컬 JWT 생성 스크립트 구성 |
| 포트폴리오 | 백엔드 마이그레이션 요약 PPT 작성 |

## 3. 구현 구조와 흐름

```text
HTTP 요청
→ Phase 0 SecurityFilterChain에서 JWT 검증
→ CurrentUserProvider가 JWT sub 조회
→ MemberController가 MemberId 생성
→ MemberApplicationService가 Use Case와 transaction 처리
→ MemberProfileRepository 도메인 계약 호출
→ JpaMemberProfileRepository가 JPA로 구현
→ ProfileJpaEntity와 기존 profiles 테이블 연결
→ MemberProfile 도메인 모델
→ MemberProfileResponse 반환
```

계층별 책임:

| 계층 | 주요 코드 | 책임 |
|---|---|---|
| Presentation | `MemberController`, 요청·응답 DTO | HTTP 계약과 입력 검증 |
| Application | `MemberApplicationService` | 초기화·조회·수정 흐름과 transaction |
| Domain | `MemberProfile`, `MemberProfileRepository` | 회원 모델과 저장소 계약 |
| Infrastructure | JPA Adapter·Entity·Spring Data Repository | 기존 PostgreSQL 접근 구현 |

## 4. API 검증 결과

| API | 확인한 동작 |
|---|---|
| `POST /api/v1/members/me/initialize` | 최초 생성과 반복 호출 시 단일 Profile 유지 |
| `GET /api/v1/members/me` | JWT 사용자의 Profile 조회와 미존재 `404` |
| `PATCH /api/v1/members/me` | 전체·부분 수정, 입력 오류와 닉네임 중복 처리 |

수동으로 Health, 인증 실패, Profile 미존재, 초기화, 멱등성, 조회, 전체·부분 수정, 입력 오류, 두 번째 사용자, 닉네임 중복과 DB 저장 결과를 확인했다.

## 5. 자동 테스트

2026-07-29 최종 실행:

```text
./gradlew clean test
BUILD SUCCESSFUL
전체 16개 / 실패 0개 / 오류 0개 / 건너뜀 0개
```

| 테스트 | 개수 |
|---|---:|
| 애플리케이션 Context | 1 |
| Spring Modulith 경계 | 1 |
| JWT·인증·권한 | 7 |
| RLS 호환성 | 1 |
| Member 통합 | 6 |

## 6. 다음 작업

우선순위 순서:

1. 실제 Supabase JWT 서명 방식과 Spring 검증 결과를 확인한다.
2. 운영 `spring_app` DB Role의 최소 권한과 RLS 적용 범위를 검증한다.
3. 기존 Supabase `profiles`와 Spring API 조회 결과를 비교한다.
4. Flutter Profile 읽기를 Spring API로 전환한다.
5. Spring 쓰기를 검증한 후 Flutter Profile 쓰기를 전환한다.
6. Flutter의 Supabase 직접 Profile 쓰기를 제거해 이중 쓰기를 방지한다.
7. 로그와 오류 지표를 확인하고 Phase 1 완료 여부를 결정한다.

## 7. 남은 위험

- 실제 Supabase JWT·DB·RLS 환경은 아직 검증하지 않았다.
- Flutter 구버전이 남으면 Supabase 직접 쓰기와 Spring 쓰기가 충돌할 수 있다.
- 기존 UPDATE RLS에 `WITH CHECK`가 없어 Flutter 직접 접근 유지 기간에 추가 검토가 필요하다.
- 회원 상태는 코드 모델만 존재하며 DB 컬럼 도입은 회원 탈퇴 Phase로 미뤘다.
