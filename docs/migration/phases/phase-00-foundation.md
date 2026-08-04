# Phase 0: Spring Foundation

> 상태: 완료 기준 전체 충족 (실제 Supabase JWT·spring_app DB Role 연동 검증 완료). 노출된 Secret 로테이션·배포 Secret 관리 방식 확정 후 최종 완료 처리
> 시작일: 2026-07-28

## 1. 문서 목적

이 문서는 Udaadaa Spring 백엔드의 공통 기반을 만들기 위한 Phase 0의 작업 범위, 실행 순서, 기술 결정과 완료 기준을 관리한다.

[Migration Roadmap](../05-migration-roadmap.md)은 전체 마이그레이션 순서를 보여주는 지도이고, 이 문서는 Phase 0을 실제로 수행하면서 계획·결정·검증 결과를 누적하는 작업 문서다.

## 2. 현재 상태

### 확인된 사실

- `udaadaa_server/`에 Spring Boot 프로젝트와 공통 기반이 구현되어 있다.
- 기존 Flutter·Supabase 애플리케이션은 `udaadaa/`에 보존되어 있다.
- [ADR-002](../adr/ADR-002-infrastructure-technical-review.md)에 따라 초기 Spring 서버는 기존 Supabase Auth, PostgreSQL Database와 Storage를 유지한다. Spring 기능을 먼저 안정화하고 마지막 안정화 단계에서 독립 인프라로 이전한다.
- 현재 Supabase 프로젝트는 정상 상태이며 PostgreSQL 15.14를 사용한다.
- 현재 JWKS endpoint에는 공개 키가 없어 레거시 HMAC JWT 검증이 필요하다.
- 운영 DB에는 아직 `spring_app` Role이 없다.
- 목표 구조는 Spring 기반 모듈형 모놀리스이며 Spring Modulith로 경계를 검증한다.
- 초기에는 Kafka, RabbitMQ, Outbox, STOMP와 Storage SDK를 추가하지 않는다.

### 아직 확인할 내용

- 실제 Supabase JWT secret을 배포 환경에 주입한 뒤 실제 사용자 token 검증
- 운영 DB의 `spring_app` 로그인 Role·최소 권한·RLS 정책 생성과 연결 검증
- 실제 배포 환경과 Secret 저장 방식

상세 구현·검증 결과는 [Phase 0 Verification](phase-00-verification.md)에 분리해 기록한다.

## 3. Phase 0 목표

- Spring 애플리케이션을 로컬에서 실행할 수 있다.
- 공통 설정을 local, test와 운영 환경별로 분리한다.
- Supabase JWT의 서명, issuer와 만료를 검증하고 subject를 추출한다.
- 기존 Supabase PostgreSQL에 Spring 전용 설정으로 연결할 수 있다.
- Spring Modulith가 모듈 구조와 잘못된 의존성을 검사한다.
- 공통 오류 응답, validation과 요청 추적 기준을 제공한다.
- 상태 확인, 로그와 최소 운영 지표를 확인할 수 있다.
- 자동화 테스트와 CI가 이후 도메인 구현의 공통 검증 기준이 된다.

## 4. 포함 범위와 제외 범위

### 포함

- Java·Spring Boot·빌드 도구와 기본 패키지 결정
- Spring 프로젝트 골격
- Spring Modulith 기반 모듈 구조와 검증 테스트
- 환경별 설정과 비밀정보 주입 규칙
- Supabase JWT 인증 기반
- PostgreSQL 연결과 DB migration 기반
- 공통 API 오류와 validation
- correlation ID, 로그, 상태 확인과 최소 지표
- 기본 테스트와 CI
- 서버 실행 방법과 검증 결과 문서화

### 제외

- Member 프로필 조회·수정 API
- 사용자 차단 기능
- 채팅 REST API와 STOMP 연결
- Supabase Storage 이미지 업로드
- 메시지 BIGINT 순번과 읽음 cursor Schema
- 미션·챌린지·피드 기능
- 회원 탈퇴 실제 처리
- 기존 Flutter 코드 변경
- 기존 운영 테이블의 구조 변경과 데이터 백필
- Kafka·RabbitMQ·Outbox와 분산 캐시

Phase 0에서는 후속 기능에 필요한 모든 기술을 미리 추가하지 않는다. 실제 사용 단계가 오기 전에는 STOMP, Storage, FCM과 Outbox 관련 구현을 만들지 않는다.

## 5. 적용할 AI 작업 방식

### Harness Engineering

Phase 0를 조사, 결정, 문서, 생성, 설정, 구현과 검증 단계로 나눈다. 각 단계의 산출물과 완료 조건을 확인한 뒤 다음 단계로 이동한다.

### ReAct

현재 저장소와 공식 문서를 확인하고 그 결과를 다음 기술 결정에 반영한다. 버전, JWT와 Supabase 연결 방식을 기억에 의존해 확정하지 않는다.

### Self-Consistency

빌드 도구, DB 접근, 테스트 환경처럼 대안이 있는 결정은 개발, 보안, 운영, 테스트와 유지보수 관점에서 비교한다.

### 단계별 검토

인증과 DB 설정은 `사실 확인 → 규칙 적용 → 구현 → 정상·실패 검증` 순서로 처리한다.

### Memory & Compaction

확정 버전, 선택 이유, 보안 규칙, 검증 결과와 미해결 위험은 이 문서에 보존한다. 일시적인 검색 과정과 반복 로그는 요약한다.

## 6. 기술 결정 항목

다음 표의 결정은 공식 문서와 현재 개발 환경을 조사한 뒤 확정한다.

| 항목 | 초기 후보 | 상태 | 확인 기준 |
|---|---|---|---|
| Java | 21 LTS | 결정됨 | 로컬 21.0.11과 Spring Boot 지원 범위 확인 |
| Spring Boot | 4.1.0 | 결정됨 | 공식 안정 버전과 Java 21 호환 확인 |
| 빌드 도구 | Gradle 9.5.1 Kotlin DSL | 결정됨 | Wrapper·dependency lock·CI 동일 명령 사용 |
| 기본 패키지 | `com.udaadaa` | 결정됨 | 서비스 기준 최상위 package |
| Web | Spring MVC | 결정됨 | 초기 REST API와 동기식 DB 작업에 적합 |
| DB 접근 | Spring Data JPA | 결정됨 | 도메인 CRUD와 transaction 기반 |
| DB migration | Flyway | 결정됨 | migration과 자동 baseline을 기본 비활성화하고 최초 적용은 별도 승인 |
| 인증 | Spring Security Resource Server | 결정됨 | 현재 HMAC, 향후 JWKS 방식을 설정으로 전환 가능 |
| 모듈 검증 | Spring Modulith 2.1.0 | 결정됨 | Spring Boot 4.1과 함께 경계 테스트 통과 |
| 운영 상태 | Spring Boot Actuator | 결정됨 | health만 HTTP에 노출하고 내부 지표는 운영 경로 확정 후 추가 |
| 테스트 DB | Testcontainers PostgreSQL 15 | 결정됨 | CI 기본값, Docker 불가 시 격리된 외부 DB 사용 가능 |
| CI | GitHub Actions | 구성됨 | Wrapper 검증 후 `clean test` 실행, 원격 실행 대기 |

기술 선택이 장기간 영향을 주거나 기존 ADR과 충돌하면 새로운 ADR을 작성한다. 단순 버전 확인과 구현 세부사항은 이 문서의 결정 기록에 남긴다.

## 7. 예상 의존성 범위

정확한 artifact와 버전은 공식 문서 확인 후 확정한다.

### 초기 포함 후보

- Spring Web
- Validation
- Spring Security
- OAuth2 Resource Server
- Spring Data JPA
- PostgreSQL Driver
- Flyway
- Spring Modulith Core와 Test
- Spring Boot Actuator
- Spring Boot Test
- Spring Security Test

### 초기 제외

- Spring WebSocket·STOMP
- Supabase Storage SDK
- Firebase Admin SDK
- Kafka·RabbitMQ
- Outbox 구현
- Redis
- 결제 SDK

의존성은 실제 Phase 0 산출물에 필요한 것만 추가하고 버전을 고정하며 lockfile 또는 dependency verification 사용 여부를 검토한다.

## 8. 목표 프로젝트 구조

정확한 package 이름은 기술 결정 후 반영한다.

```text
udaadaa_server/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle/
├─ gradlew
├─ gradlew.bat
├─ README.md
└─ src/
   ├─ main/
   │  ├─ java/com/udaadaa/
   │  │  ├─ UdaadaaApplication.java
   │  │  ├─ common/
   │  │  │  ├─ config/
   │  │  │  ├─ error/
   │  │  │  ├─ security/
   │  │  │  └─ web/
   │  │  ├─ member/
   │  │  ├─ moderation/
   │  │  ├─ chat/
   │  │  ├─ notification/
   │  │  ├─ challenge/
   │  │  ├─ record/
   │  │  └─ social/
   │  └─ resources/
   │     ├─ application.yml
   │     ├─ application-local.yml
   │     └─ db/migration/
   └─ test/
      └─ java/com/udaadaa/
```

- Phase 0에서는 도메인 package의 경계만 준비하고 실제 비즈니스 기능은 구현하지 않는다.
- 공통이라는 이유로 비즈니스 규칙을 `common`에 넣지 않는다.
- 각 도메인은 이후 `presentation`, `application`, `domain`, `infrastructure` 책임을 기준으로 구성한다.

## 9. 작업 분해

### 실행 계획 요약

| ID | 작업 | 선행 조건 | 핵심 산출물 | 상태 |
|---|---|---|---|---|
| 0-A | 현재 환경과 공식 자료 조사 | 없음 | 환경 현황, 호환 버전과 Supabase 연결 조건 | 완료 |
| 0-B | 기술 기준 확정 | 0-A 결과와 사용자 승인 | 기술 결정 표와 선택 이유 | 완료 |
| 0-C | Spring 프로젝트 생성 | 0-B 완료 | 빌드 가능한 Spring 프로젝트와 Wrapper | 완료 |
| 0-D | 환경 설정과 비밀정보 관리 | 0-C 완료 | 환경별 설정, 예제 변수와 마스킹 규칙 | 완료 |
| 0-E | 인증 기반 | 0-B·0-D 완료 | Supabase JWT 검증과 공통 인증 사용자 | 검증 중 |
| 0-F | PostgreSQL과 migration 기반 | 0-B·0-D 완료 | DB 연결, `spring_app` Role·RLS 검증안과 Flyway 기준 | 검증 중 |
| 0-G | 모듈 경계 기반 | 0-C 완료 | Modulith 모듈 구조와 경계 규칙 | 완료 |
| 0-H | 공통 API와 관찰 기반 | 0-C·0-D 완료 | 오류 응답, validation, correlation ID와 상태 지표 | 완료 |
| 0-I | 자동화 테스트와 CI 구성 | 0-E~0-H 구현 | 테스트 구조와 GitHub Actions | 완료 |
| 0-J | 최종 검증과 문서 동기화 | 0-I 완료·검증 실행 승인 | 실행 결과, 완료 증거와 남은 위험 | 완료 |

진행 상태는 `예정 → 진행 중 → 검증 중 → 완료` 순서로 변경한다. 각 작업은 산출물과 완료 기준을 확인한 뒤 다음 작업으로 넘어간다.

### 승인 지점

- **기술 승인:** 0-A 조사 결과를 검토하고 0-B 기술 조합에 사용자가 동의한 뒤 프로젝트를 생성한다.
- **검증 승인:** 0-I까지 구현한 뒤 빌드·테스트·정적 검사는 별도 승인을 받고 실행한다.
- **범위 변경 승인:** 운영 Schema 변경, 실제 사용자 데이터 접근 또는 기존 Flutter 수정이 필요하면 Phase 0 작업을 중단하고 범위를 다시 합의한다.

### 0-A. 현재 환경과 공식 자료 조사

입력:

- 현재 `udaadaa_server/` 상태
- 로컬 Java·Gradle 환경
- Spring Boot·Spring Modulith 공식 문서
- Supabase Auth·PostgreSQL 공식 문서와 현재 프로젝트 설정

산출물:

- 사용할 기술 후보와 호환 버전 표
- 현재 JWT와 DB 연결 방식에 대한 확인 결과
- 아직 사용자가 결정해야 하는 항목

완료 기준:

- 모든 버전과 연결 방식에 확인 근거가 있음
- 확인된 사실과 추천을 구분함
- 비밀 키와 연결 비밀번호를 문서에 기록하지 않음

### 0-B. 기술 기준 확정

작업:

- Java·Spring Boot·Spring Modulith 버전 확정
- Gradle 또는 Maven 확정
- package 이름과 모듈 명명 규칙 확정
- JPA·Flyway·테스트 DB·CI 방향 확정

산출물:

- 이 문서의 기술 결정 표 갱신
- 중요한 변경이 필요한 경우 추가 ADR

완료 기준:

- 사용자가 주요 기술 선택에 동의함
- 선택 이유와 포기한 대안을 기록함

### 0-C. Spring 프로젝트 생성

작업:

- 빌드 파일과 Wrapper 생성
- 애플리케이션 진입점 생성
- main·test source set 구성
- 최소 의존성 추가
- 서버 실행 방법 작성

완료 기준:

- 깨끗한 환경에서 동일한 명령으로 빌드 가능
- Spring Context가 실행됨
- 불필요한 Phase 3 이후 의존성이 없음

### 0-D. 환경 설정과 비밀정보 관리

작업:

- 기본·local·test 설정 분리
- 환경 변수 이름 정의
- 실제 값이 없는 예제 설정 제공
- Git ignore와 로그 마스킹 확인

환경 변수 후보:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
SUPABASE_JWT_ISSUER
SUPABASE_JWT_JWK_SET_URI
```

- 이름은 구현 과정에서 Spring 표준 설정과 배포 환경에 맞게 조정할 수 있다.
- 실제 JWT, DB 비밀번호, `service_role`과 비밀 키는 저장소·문서·로그에 넣지 않는다.

완료 기준:

- 비밀값 없이 저장소를 공유할 수 있음
- local·test 설정이 운영 설정을 덮어쓰지 않음
- 설정 누락 시 원인을 알 수 있는 오류가 발생함

#### Secret 관리 원칙 (2026-08-04)

배포 플랫폼(AWS 등)은 아직 확정 전이므로, 플랫폼 결정과 무관하게 지금 적용 가능한 원칙만 정한다. 플랫폼별 Secret 관리 도구(AWS Secrets Manager 등) 연동은 배포 인프라 ADR에서 별도로 다룬다.

| 환경 | 주입 방식 |
|---|---|
| 로컬 개발 | 터미널에서 `./gradlew bootRun` 실행 시 환경변수로 직접 전달. IntelliJ Run Configuration의 세미콜론 구분 입력 필드는 특수문자 포함 값에서 파싱 오류가 재현되어 권장하지 않음 (2026-08-04 트러블슈팅 기록 참고) |
| CI (GitHub Actions) | 저장소 Secret 없이 Testcontainers 임시 자격 증명만 사용. 실제 Secret 주입 없음 |
| 운영 배포 | 배포 플랫폼 확정 시 해당 플랫폼의 Secret 관리 도구로 주입. 결정 전까지는 실제 배포를 진행하지 않음 |

공통 규칙:

- 실제 Secret 값은 저장소, 문서, 채팅 세션 어디에도 원문으로 남기지 않는다.
- Secret이 실수로 노출되면 즉시 로테이션하고 노출 시점·범위를 검증 문서에 기록한다 (아래 대응 기록 참고).
- SQL 스크립트(`scripts/db-admin/`)에는 비밀번호를 플레이스홀더로만 남기고, 실행 시점에 대시보드에서 직접 교체한다.

#### 노출 대응 기록

2026-08-04 실제 Supabase 연동 검증 중 트러블슈팅 과정에서 Legacy JWT Secret과 `spring_app` DB 비밀번호가 채팅 세션에 노출됐다.

**로테이션 보류 결정 (2026-08-04):** 현재 운영 트래픽이 사실상 중단 수준이라 즉시 위험도가 낮다고 판단하여 로테이션을 지금 진행하지 않는다. 단, JWT Secret 로테이션은 `reaction-push`, `message-push`, `get-room-id-by-name`, `post-initial-chat-data`, `delete-auth-user` Edge Function 5개의 동시 업데이트가 필요해 단독 작업이 아니므로, 실제 트래픽이 다시 늘어나기 전 또는 Phase 1 Flutter 전환 착수 전 별도 작업으로 예정한다. `spring_app` DB 비밀번호는 Edge Function 의존성이 없어 더 가볍게 로테이션 가능하므로 우선순위를 높게 둔다.

미해결 상태로 [Phase 0 Verification §7](phase-00-verification.md#7-노출된-secret-정리-필요-목록)에 추적한다.

### 0-E. 인증 기반

작업:

- Bearer token 수신
- JWT 서명·issuer·만료 검증
- 검증된 subject를 공통 인증 객체로 변환
- 공개 endpoint와 보호 endpoint 구분
- 인증 실패 오류 형식 통일

보안 규칙:

- 요청 body의 `userId`를 인증 근거로 사용하지 않는다.
- 검증된 JWT subject만 인증 사용자 식별에 사용한다.
- 사용자 수정 가능 metadata를 권한 판단에 사용하지 않는다.
- Member 조회와 서비스 회원 상태 검증은 Phase 1에서 구현한다.

완료 기준:

- 정상 token에서 subject를 추출함
- 서명 오류, 만료, issuer 불일치 token을 거부함
- 보호 endpoint의 무인증 접근을 거부함
- JWT 원문이 로그에 남지 않음

### 0-F. PostgreSQL과 migration 기반

작업:

- Spring 전용 PostgreSQL 연결 정보와 최소 권한 검토
- connection pool과 timeout 기본값 결정
- 기존 Schema validation 방식 결정
- Flyway와 기존 Supabase migration의 책임 구분
- 테스트 DB 격리 방식 결정

원칙:

- Supabase `service_role` API key는 JDBC DB 자격 증명이 아니다.
- Flutter에 DB 비밀번호나 서버 권한을 노출하지 않는다.
- Data API 직접 접근이 남아 있는 동안 기존 RLS를 임의로 제거하지 않는다.
- Phase 0에서는 운영 테이블을 변경하거나 사용자 데이터를 조회하지 않는다.

완료 기준:

- Spring이 허용된 DB 연결로 health check 또는 최소 연결 검증을 수행함
- 운영 DB와 테스트 DB가 분리됨
- migration 적용 대상과 실행 환경이 명확함
- 연결 비밀번호가 저장소와 로그에 없음

#### spring_app Role 설계 (2026-08-04, SQL 작성만 완료·운영 미적용)

| 결정 항목 | 결정 |
|---|---|
| Role 종류 | `LOGIN` Role, `NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT` |
| RLS 처리 | `BYPASSRLS` 적용. Supabase RLS는 `auth.uid()` 기반이라 Spring의 JWT 인증 컨텍스트와 맞지 않으므로, 행 단위 권한은 Spring 애플리케이션 계층(JWT `sub` 검증)이 책임진다. 기존 `authenticated`/RLS는 Flutter 직접 접근 경로에 그대로 유지한다 |
| 권한 범위 | Phase 1 범위인 `public.profiles`에 `SELECT, INSERT, UPDATE`만 부여. `DELETE` 제외(회원 탈퇴는 별도 Phase에서 재검토). 이후 Phase가 필요로 하는 테이블은 해당 Phase 문서에서 추가 `GRANT`로 확장 |
| Connection Limit | 15 (Spring HikariCP `maximum-pool-size` 기본 10보다 여유 있게 설정) |
| 연결 방식 | IPv6 지원 여부에 따라 Direct Connection(5432) 또는 Session Pooler(5432) 사용. Transaction Pooler(6543)는 Hibernate Prepared Statement와 충돌 가능하여 제외 |
| 비밀번호 | SQL 파일에 값을 넣지 않는다. 적용 시점에 별도로 생성하여 Secret 관리 도구에 저장한다 |

산출물 (Spring 운영 admin 스크립트로 분류하여 `docs/`가 아닌 `udaadaa_server/scripts/db-admin/`에 보관):

- [`spring_app Role 생성 SQL`](../../../udaadaa_server/scripts/db-admin/phase-00-spring-app-role-create.sql)
- [`spring_app Role 롤백 SQL`](../../../udaadaa_server/scripts/db-admin/phase-00-spring-app-role-rollback.sql)
- [`profiles RLS 보완 SQL(선택)`](../../../udaadaa_server/scripts/db-admin/phase-00-profiles-rls-hardening.sql) — `UPDATE` 정책에 `WITH CHECK` 추가, 정책 대상을 `public`에서 `authenticated`로 명확화. spring_app 도입과 무관하게 Flutter 직접 쓰기 경로의 기존 취약점을 보완하는 별도 변경이라 승인 후 개별 적용

이 SQL은 검토용으로만 작성했으며 운영 DB에는 적용하지 않았다. 적용은 Task #3(운영 DB Role 적용 및 연결 검증)에서 별도 승인 후 진행한다.

### 0-G. 모듈 경계 기반

작업:

- Spring Modulith가 인식할 최상위 모듈 package 정의
- 모듈 공개 API와 내부 package 규칙 정의
- 모듈 간 순환·내부 접근 검증 테스트 추가

규칙:

- 다른 모듈의 Entity와 Repository를 직접 참조하지 않는다.
- 즉시 결과가 필요하면 공개 Application Service를 호출한다.
- commit 이후 후속 작업은 Spring 내부 이벤트를 사용한다.
- 외부 메시지 브로커는 추가하지 않는다.

완료 기준:

- Spring Modulith 검증 테스트가 통과함
- 의도적인 잘못된 의존성을 테스트에서 탐지할 수 있음
- 공통 package가 도메인 규칙의 우회 경로가 되지 않음

### 0-H. 공통 API와 관찰 기반

작업:

- 공통 오류 코드와 응답 형식
- validation 오류 처리
- correlation ID 생성·전파
- health endpoint
- 요청 성공률·지연과 DB 연결 상태의 최소 지표

오류 응답 후보:

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청값이 올바르지 않습니다.",
  "traceId": "request-trace-id"
}
```

로그 금지 항목:

- JWT와 인증 header
- 비밀번호·비밀 키·FCM token
- 채팅 메시지 본문
- 불필요한 개인정보

완료 기준:

- 같은 요청의 API·오류 로그를 correlation ID로 연결할 수 있음
- health와 운영 endpoint의 공개 범위가 제한됨
- 오류 응답이 내부 stack trace를 노출하지 않음

### 0-I. 자동화 테스트와 CI 구성

작업:

- 단위·통합·인증·모듈 경계 테스트 구조 구성
- 운영 DB와 분리된 테스트 PostgreSQL 실행 방식 구성
- GitHub Actions에서 동일한 Gradle 명령을 실행하도록 구성
- 의존성 캐시와 비밀정보가 없는 CI 환경 확인

완료 기준:

- 로컬과 CI가 같은 Wrapper 명령을 사용함
- 테스트가 운영 DB와 실제 사용자 데이터에 접근하지 않음
- 저장소의 비밀정보 없이 CI를 실행할 수 있음
- 실패한 검증의 단계와 원인을 CI 결과에서 확인할 수 있음

### 0-J. 최종 검증과 문서 동기화

검증 후보:

- Spring Context 실행 테스트
- 기본 health endpoint 테스트
- 공개·보호 endpoint 접근 테스트
- 정상·만료·위조 JWT 테스트
- PostgreSQL 연결 테스트
- Spring Modulith 경계 테스트
- 설정 파일의 비밀정보 검사
- 빌드와 정적 검사

사용자가 Phase 0 전체 구현과 검증을 요청하여 로컬 테스트·빌드·기동 검증을 실행했다.

산출물:

- 실행한 명령과 결과
- 실패 원인과 수정 결과
- 실행하지 못한 검증과 이유
- Phase 0 완료 증거
- Roadmap 상태 갱신

## 10. Phase 0 완료 기준

- [x] Java·Spring Boot·Spring Modulith와 빌드 도구를 확정함
- [x] Spring 프로젝트와 Wrapper가 저장소에 포함됨
- [x] 환경별 설정과 비밀정보 주입 규칙이 적용됨
- [x] HMAC JWT의 서명·issuer·audience·만료·subject 검증 로직이 통과함
- [x] 검증된 JWT subject를 공통 인증 사용자로 사용할 수 있음
- [x] PostgreSQL 연결과 Flyway migration 기반을 구성함
- [x] 운영 DB와 테스트 DB를 분리함
- [x] Spring Modulith가 현재 모듈 경계를 검증함
- [x] 공통 오류·validation·correlation ID가 적용됨
- [x] health, 로그와 최소 지표를 확인함
- [x] 기본 빌드·테스트와 변경 정합성 검사가 통과함
- [x] 비밀정보가 저장소와 로그에 포함되지 않음을 확인함
- [x] 실행 방법과 검증 결과를 문서화함
- [x] 실제 Supabase JWT secret·사용자 token으로 인증을 검증함
- [x] 운영 Supabase DB에 `spring_app` Role을 만들고 최소 권한·RLS·연결을 검증함 (Session Pooler 연결, SELECT/INSERT/UPDATE 실제 검증 완료. 결과: [Phase 0 Verification §6](phase-00-verification.md#6-spring_app-db-role-실제-적용연결-검증-결과-2026-08-04))
- [x] GitHub Actions 원격 실행이 통과함

## 11. 중단·롤백 기준

- Phase 0에서는 Flutter 호출과 운영 데이터의 쓰기 주체를 변경하지 않는다.
- 운영 DB 변경이나 실제 사용자 데이터 쓰기가 발생하려 하면 작업을 중단하고 범위를 다시 검토한다.
- JWT 방식이나 DB 권한을 확인하지 못하면 임의의 비밀 키 또는 관리자 권한으로 우회하지 않는다.
- 의존성 호환 문제가 발생하면 버전을 임의로 반복 변경하지 않고 공식 호환표를 다시 확인한다.
- 배포 문제가 발생하면 Spring 배포만 중단하거나 이전 버전으로 되돌리며 기존 Flutter·Supabase 서비스는 유지한다.

## 12. 위험과 미해결 질문

| 항목 | 위험 | 처리 방향 |
|---|---|---|
| 실제 JWT 연동 | 로컬 HMAC 계약은 통과했지만 실제 secret·token 검증 전에는 운영 인증을 확정할 수 없음 | 배포 Secret 주입 후 정상·만료 token 검증 |
| 운영 DB 권한 | 실제 `spring_app` Role이 아직 없어 운영 RLS 조합이 미검증 | 별도 승인된 DDL로 최소 권한 Role·정책 생성 후 연결 테스트 |
| 기존 migration | 기준선 이전 운영 Schema를 Flyway가 소유하면 충돌 가능 | Flyway 기본 비활성화, 기준선 승인 후 Spring 변경만 관리 |
| Docker 환경 | 로컬 Docker Desktop 종료로 Testcontainers 실행이 불안정했음 | CI는 Testcontainers, 로컬은 격리 PostgreSQL 대체 경로 제공 |
| 모듈 구조 | 현재는 빈 도메인 package라 실제 의존성 규칙 검증 범위가 작음 | Phase 1부터 공개 API·내부 package가 생길 때 검증 강화 |
| CI Action 런타임 | Actions v4가 Node.js 20 사용 중단 경고를 표시함 | 공식 최신 major 호환성을 별도 검토한 뒤 갱신 |

## 13. 결정과 검증 기록

Phase 0 진행 중 다음 형식으로 누적한다.

| 날짜 | 구분 | 결과 | 근거·검증 |
|---|---|---|---|
| 2026-07-28 | 계획 | Phase 0 범위, 0-A~0-J 실행 순서와 승인 지점 확정 | Roadmap·TO-BE·ADR-001~003 기준 |
| 2026-07-28 | 기술 | Java 21, Spring Boot 4.1.0, Spring Modulith 2.1.0, Gradle 9.5.1 확정 | 공식 호환 범위와 로컬 환경 확인 |
| 2026-07-28 | Supabase | PostgreSQL 15.14, JWKS 공개 키 없음, `spring_app` Role 없음 확인 | 운영 프로젝트 read-only 조회 |
| 2026-07-28 | 구현 | 실행·JWT·DB/Flyway·모듈·오류·관찰·테스트·CI 기반 구현 | `udaadaa_server/`, `server-ci.yml` |
| 2026-07-28 | 검증 | 격리 PostgreSQL에서 전체 10개 테스트, 서버 기동, health·401·Actuator 비노출 검증 통과 | [Phase 0 Verification](phase-00-verification.md) |
| 2026-07-28 | CI | Testcontainers PostgreSQL을 사용한 GitHub Actions 통과 | [Server CI](https://github.com/zs0057/udaadaa-monorepo/actions/runs/30343831518) |
| 2026-08-04 | 실제 연동 | 운영 Supabase Legacy JWT Secret과 실제 Access Token으로 정상·만료·변조·무토큰 4개 케이스 검증 통과 | [Phase 0 Verification §5](phase-00-verification.md#5-실제-supabase-jwt-검증-결과-2026-08-04) |
| 2026-08-04 | 설계 | spring_app Role(BYPASSRLS, profiles 최소 권한) 생성·롤백 SQL과 profiles RLS 보완 SQL 작성. 운영 미적용 | [spring_app Role 설계](#spring_app-role-설계-2026-08-04-sql-작성만-완료운영-미적용) |
| 2026-08-04 | 실제 연동 | spring_app Role을 운영 DB에 생성하고 Session Pooler로 연결. Member API의 INSERT·UPDATE·SELECT를 실제 운영 DB에서 검증 통과 | [Phase 0 Verification §6](phase-00-verification.md#6-spring_app-db-role-실제-적용연결-검증-결과-2026-08-04) |

## 14. 바로 다음 작업

Phase 0의 완료 기준 항목은 모두 충족했다. 남은 것은 검증 과정에서 채팅에 노출된 Secret 2개(JWT Legacy Secret, spring_app DB 비밀번호) 로테이션과 배포 환경 Secret 관리 방식 확정이며, 이후 Phase 1의 Flutter 전환 작업으로 넘어간다.

1. 실제 Supabase JWT secret을 배포 Secret으로 주입하고 실제 사용자 token 검증
2. 승인된 DDL로 운영 `spring_app` Role·최소 권한·RLS 정책 구성
3. Direct Connection 또는 Session Pooler로 운영 DB 연결 검증
4. 두 운영 연동 항목이 통과하면 Phase 0을 완료로 전환하고 Phase 1 Member 설계 시작
