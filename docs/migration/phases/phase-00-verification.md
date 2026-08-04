# Phase 0 Verification

> 상태: 완료 — 로컬·실제 Supabase JWT·DB Role 검증 모두 통과, 전체 자동 테스트 재실행 통과
> 검증일: 2026-07-28 (로컬), 2026-08-04 (실제 Supabase JWT·DB Role, 최종 자동 테스트)

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

## 6. spring_app DB Role 실제 적용·연결 검증 결과 (2026-08-04)

`docs/migration/phases/phase-00-foundation.md`에서 설계한 `spring_app` Role을 운영 Supabase DB에 실제 생성하고, 로컬 Spring(`SPRING_PROFILES_ACTIVE=local`)에서 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`를 실제 값으로 주입하여 연결·권한을 검증했다.

### 적용 내용

- `udaadaa_server/scripts/db-admin/phase-00-spring-app-role-create.sql`을 Supabase SQL Editor에서 실제 비밀번호로 교체해 실행
- 비밀번호는 저장소·문서에 기록하지 않음. 실행 중 채팅에 원문이 노출되어, JWT Secret과 마찬가지로 추후 로테이션 필요 (7절 참고)

### 연결 방식

| 항목 | 값 |
|---|---|
| 방식 | Session Pooler (IPv4 호환) |
| Host | `aws-0-ap-northeast-2.pooler.supabase.com` |
| Port | 5432 |
| Database | `postgres` |
| Username | `spring_app.ccpcclfqofyvksajnrpg` |
| SSL | `sslmode=require` |

Transaction Pooler(6543)는 Hibernate Prepared Statement와 충돌 가능성이 있어 사용하지 않았다.

### 권한 검증 결과

실제 로그인 Access Token(`jwt-test@udaadaa.test` 테스트 계정)으로 Member API를 호출해 `spring_app`의 GRANT가 실제로 동작하는지 확인했다.

| 케이스 | API | 기대 | 실제 결과 |
|---|---|---|---|
| INSERT | `POST /api/v1/members/me/initialize` | 200/201, 신규 프로필 생성 | 200, `profiles`에 실제 행 생성 확인 |
| UPDATE | `PATCH /api/v1/members/me` | 200, 닉네임 변경 반영 | 200, 닉네임 변경 확인 |
| SELECT | 위 두 응답에 조회 결과 포함 | 응답 body에 최신 값 반영 | 확인됨 (별도 GET 호출은 생략) |

- Hikari 연결 풀이 정상적으로 커넥션을 확보했고, Hibernate가 `Database version: 15.14`로 실제 운영 DB에 접속했음을 로그로 확인
- `spring_app`이 `BYPASSRLS`로 설정된 상태에서 정상적으로 읽기·쓰기가 되는 것을 확인 — RLS 우회가 의도대로 동작함
- DELETE는 GRANT하지 않았으므로 별도 실패 테스트는 생략 (Member API에도 삭제 endpoint가 아직 없어 API 레벨에서 자연히 차단됨)

### 트러블슈팅 기록

- 최초 연결 시도에서 `password authentication failed for user "spring_app"` 발생. 원인은 Role 생성 시 사용한 비밀번호와 연결 시도에 사용한 비밀번호가 서로 달랐기 때문. `ALTER ROLE spring_app WITH PASSWORD ...`로 동기화 후 해결
- 이 과정에서 DB 비밀번호도 채팅에 노출됨 (아래 보안 조치 항목 참고)

### 남은 정리 항목

- 테스트 중 운영 `profiles`에 생성된 `jwt-test@udaadaa.test` 테스트 프로필 행은 이메일로 식별 가능하여 당장 위험하지 않다고 판단, 정리하지 않고 보류함. 추후 일괄 정리 필요
- `phase-00-profiles-rls-hardening.sql`(UPDATE 정책 `WITH CHECK` 보완)은 이번 검증 범위에 포함하지 않음. Flutter 회귀 테스트와 함께 별도 적용 여부 결정 필요

## 7. 노출된 Secret 정리 필요 목록

이번 Phase 0 실제 연동 검증 과정에서 아래 2개 Secret이 채팅 세션에 노출됐다. 즉시 서비스 장애로 이어지진 않지만, 다음 유지보수 시점에 정리한다.

| Secret | 노출 시점 | 필요 조치 | 상태 |
|---|---|---|---|
| Supabase Legacy JWT Secret | JWT 검증 트러블슈팅 중 | Signing Key 로테이션 후 기존 키 Revoke (Edge Function 5개 동시 업데이트 필요, 6절 참고) | 보류 (2026-08-04, 운영 트래픽 사실상 중단 수준이라 즉시 위험 낮음으로 판단) |
| `spring_app` DB Role 비밀번호 | DB 연결 트러블슈팅 중 | `ALTER ROLE spring_app WITH PASSWORD '새값'`로 교체, Spring 배포 환경 변수도 동기화 | 보류 (2026-08-04, Edge Function 의존성 없어 우선순위 높게 별도 예정) |

두 조치 모두 실제 로테이션 전까지 미해결 리스크로 남기며, 트래픽 재개 시점 또는 Phase 1 Flutter 전환 착수 전 처리한다.

## 8. 최종 자동 테스트 재실행 (2026-08-04)

실제 Supabase JWT·DB Role 검증을 마친 뒤, 전체 자동 테스트를 다시 실행해 회귀가 없는지 확인했다.

```bash
./gradlew clean test --no-daemon
./gradlew check --no-daemon
```

- 결과: 두 명령 모두 `BUILD SUCCESSFUL`
- 실행 환경: 로컬 Docker Testcontainers PostgreSQL (운영 DB와 분리)
- 이번 실행에서 코드 변경은 없었으며(문서·SQL 스크립트만 추가), 회귀 확인 목적으로 재실행함

이로써 Phase 0의 모든 완료 기준을 충족했다.
