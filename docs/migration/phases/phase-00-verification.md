# Phase 0 Verification

> 상태: 로컬 검증 완료, 운영 Supabase 연동 대기
> 검증일: 2026-07-28

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

- 실제 Supabase JWT secret과 실제 사용자 token을 이용한 정상·만료 검증
- 운영 `spring_app` 로그인 Role의 최소 권한, RLS 정책과 connection limit 적용
- Direct Connection 또는 Session Pooler 연결 확인
- 배포 환경의 Secret 저장·주입 방식 확정

GitHub Actions는 통과했지만 v4 Action의 Node.js 20 사용 중단 경고가 있다. 현재 실행에는 영향이 없으며, major 버전 갱신은 호환성을 별도 검토한다.

이 항목은 실제 Secret 또는 운영 DDL 변경이 필요하므로 로컬 구현 완료와 분리한다. 임시 관리자 권한이나 운영 데이터 쓰기로 우회하지 않는다.
