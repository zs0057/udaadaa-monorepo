# Phase 0 Verification

> 상태: 로컬 검증 완료, 실제 Supabase JWT 검증 완료, DB Role·RLS 적용 대기
> 검증일: 2026-07-28 (로컬), 2026-08-04 (실제 Supabase JWT)

## 1. 결과 요약

Spring 공통 기반을 구현하고 격리된 PostgreSQL에서 전체 테스트와 서버 기동을 검증했다. 기존 Flutter 코드와 운영 Supabase Schema·데이터는 변경하지 않았다.

| 영역 | 결과 | 확인 내용 |
|---|---|---|
| 실행 기반 | 통과 | Java 21, Spring Boot 4.1.0, Gradle Wrapper 9.5.1 |
| 인증 | 로컬 통과 | HMAC 서명, issuer, audience, 만료, UUID subject, 무인증 요청 |
| DB·Flyway | 로컬 통과 | PostgreSQL 연결, 격리된 migration 이력, JPA Context |
| RLS 경계 | 로컬 통과 | `authenticated`는 사용자 행만, `spring_app`은 서버 정책에 따라 조회 |
| 모듈 | 통과 | Member·Moderation·Chat·Notification·Challenge·Record·Social 경계 |
| 공통 API | 통과 | 공통 401 JSON, correlation ID, 내부 오류 비노출 |
| 관찰 | 통과 | Actuator health `UP`, health 외 endpoint HTTP 노출 제외 |
| CI | 통과 | Wrapper 검증과 Testcontainers PostgreSQL 기반 전체 테스트 통과 |

## 2. 자동화 검증

실행 명령의 DB 주소와 자격 증명은 로컬 테스트 환경 변수로 주입했다.

```bash
./gradlew clean test --no-daemon
./gradlew check --no-daemon
```

- 결과: 두 명령 모두 `BUILD SUCCESSFUL`
- 테스트: 10개, 실패 0개
- 범위: Context 1, Modulith 1, 인증·Actuator 7, RLS 1
- 테스트 DB: 운영과 분리된 임시 PostgreSQL 17
- CI 기본 DB: PostgreSQL 15.14 Testcontainers
- GitHub Actions: [Server CI 성공](https://github.com/zs0057/udaadaa-monorepo/actions/runs/30343831518)

서버 기동 후 다음 결과도 직접 확인했다.

```text
GET /actuator/health  → 200, status=UP
GET /api/v1/auth/me   → 401, 공통 오류 JSON과 X-Correlation-Id
```

## 3. Supabase 확인 결과

운영 프로젝트는 read-only로만 확인했다.

| 항목 | 확인 결과 | Phase 0 반영 |
|---|---|---|
| PostgreSQL | 15.14 | CI Testcontainers도 동일 major·patch 사용 |
| JWT JWKS | 공개 키 0개 | 현재 기본값을 HMAC으로 두고 JWKS 전환 설정 제공 |
| DB Role | `spring_app` 없음 | 로컬 RLS 계약만 검증하고 운영 생성은 보류 |
| 상태 | Active Healthy | 운영 Schema·사용자 데이터 변경 없음 |

## 4. 운영 연동 전 남은 검증

- ~~실제 Supabase JWT secret과 실제 사용자 token을 이용한 정상·만료 검증~~ 완료 (5절 참고)
- 운영 `spring_app` 로그인 Role의 최소 권한, RLS 정책과 connection limit 적용
- Direct Connection 또는 Session Pooler 연결 확인
- 배포 환경의 Secret 저장·주입 방식 확정

GitHub Actions는 통과했지만 v4 Action의 Node.js 20 사용 중단 경고가 있다. 현재 실행에는 영향이 없으며, major 버전 갱신은 호환성을 별도 검토한다.

이 항목은 실제 Secret 또는 운영 DDL 변경이 필요하므로 로컬 구현 완료와 분리한다. 임시 관리자 권한이나 운영 데이터 쓰기로 우회하지 않는다.

## 5. 실제 Supabase JWT 검증 결과 (2026-08-04)

로컬 Spring(`SPRING_PROFILES_ACTIVE=local`)에 실제 운영 Supabase Legacy JWT Secret과 실제 로그인 Access Token을 주입하여 `GET /api/v1/auth/me`로 검증했다.

| 케이스 | 기대 | 실제 결과 |
|---|---|---|
| 토큰 없음 | 401 | 401 |
| 정상 Access Token | 200, `id` = JWT `sub` | 200, `id` 일치 확인 |
| 만료된 Access Token | 401 | 401 (`Invalid signature`로 로그에는 표기되나 시간 검증상 만료가 원인) |
| 변조된 Access Token | 401 | 401 |

- 발급자(`iss`): `https://ccpcclfqofyvksajnrpg.supabase.co/auth/v1` — 설정값과 일치
- 대상(`aud`): `authenticated` — 설정값과 일치
- 서명 알고리즘: HS256 (Legacy JWT Secret 기반) — Signing Keys 대시보드 기준 Current Key가 여전히 `Legacy HS256 (Shared Secret)`으로 확인됨. ES256 Standby Key는 아직 미적용 상태
- JWKS 엔드포인트에 ES256 키 1개가 등록된 것을 확인했다(2026-07-28 조회 시 0개였음). Signing Keys 기능이 프로젝트에 활성화됐지만 서명 자체는 아직 HS256 legacy 경로를 사용 중이다. 향후 Rotate 시 Spring도 JWKS 모드로 전환 필요

### 트러블슈팅 기록

- 최초 IntelliJ 세미콜론 구분 환경변수 입력 방식에서 반복적으로 `Invalid signature` 오류 발생
- Python HMAC 스크립트로 Secret 자체는 유효함을 별도 확인 (Spring/IntelliJ 문제로 범위 좁힘)
- 원인: `SUPABASE_JWT_SECRET` 값이 실제로는 Spring에 전달되지 않고 `application-local.yml`의 더미 기본값(`local-only-jwt-secret-change-before-shared-use`)으로 대체되고 있었음
- IntelliJ Run Configuration 환경변수 설정 대신 터미널에서 `./gradlew bootRun`에 환경변수를 직접 지정하는 방식으로 전환하여 해결

### 보안 조치 필요

검증 과정에서 실제 Legacy JWT Secret 원문이 채팅 세션에 노출됐다. Phase 0 원칙(Secret을 채팅·코드에 노출하지 않음)이 지켜지지 않았으므로, **Supabase 대시보드에서 JWT Signing Key 로테이션(Rotate) 후 기존 키 Revoke를 조만간 진행해야 한다.** 로테이션 시점은 Flutter 등 기존 서비스가 새 키로 서명된 토큰을 문제없이 검증하는지 확인 후 결정한다.
