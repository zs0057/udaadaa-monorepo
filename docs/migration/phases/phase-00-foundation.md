# Phase 0: Spring Foundation

> 상태: 진행 중 (계획 작성)
> 시작일: 2026-07-28

## 1. 문서 목적

이 문서는 Udaadaa Spring 백엔드의 공통 기반을 만들기 위한 Phase 0의 작업 범위, 실행 순서, 기술 결정과 완료 기준을 관리한다.

[Migration Roadmap](../05-migration-roadmap.md)은 전체 마이그레이션 순서를 보여주는 지도이고, 이 문서는 Phase 0을 실제로 수행하면서 계획·결정·검증 결과를 누적하는 작업 문서다.

## 2. 현재 상태

### 확인된 사실

- `udaadaa_server/`에는 아직 Spring 빌드 파일과 소스 코드가 없다.
- 기존 Flutter·Supabase 애플리케이션은 `udaadaa/`에 보존되어 있다.
- [ADR-002](../adr/ADR-002-supabase-removal-timing.md)에 따라 초기 Spring 서버는 기존 Supabase Auth, PostgreSQL Database와 Storage를 유지한다. Spring 기능을 먼저 안정화하고 마지막 안정화 단계에서 독립 인프라로 이전한다.
- 목표 구조는 Spring 기반 모듈형 모놀리스다.
- 모듈 경계 검증에는 Spring Modulith를 사용하기로 결정했다.
- 초기에는 Kafka, RabbitMQ와 Outbox를 사용하지 않는다.

### 아직 확인할 내용

- 현재 안정적인 Spring Boot 버전과 Java 호환 범위
- Spring Boot와 Spring Modulith의 호환 버전
- Supabase 프로젝트의 현재 JWT 서명 방식과 검증 endpoint
- Spring이 사용할 PostgreSQL 연결 방식과 최소 권한
- 로컬·테스트 환경의 DB 구성 방식
- CI 환경과 배포 환경

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
| Java | LTS 버전 | 확인 필요 | Spring Boot 지원 범위, 로컬·CI 환경 |
| Spring Boot | 현재 안정 버전 | 확인 필요 | 공식 지원 상태와 Modulith 호환성 |
| 빌드 도구 | Gradle Kotlin DSL 우선 검토 | 확인 필요 | 사용자 선호, 학습·의존성 관리·CI |
| 기본 패키지 | `com.udaadaa` 후보 | 확인 필요 | 서비스 소유 도메인과 패키지 명명 규칙 |
| Web | Spring MVC | 확인 필요 | 현재 요청·응답 모델과 초기 운영 규모 |
| DB 접근 | Spring Data JPA 우선 검토 | 확인 필요 | 기존 Schema 매핑과 쿼리 복잡도 |
| DB migration | Flyway 우선 검토 | 확인 필요 | 기존 Supabase migration과 공존 방식 |
| 인증 | Spring Security Resource Server | 확인 필요 | 현재 Supabase JWT 서명·issuer 방식 |
| 모듈 검증 | Spring Modulith | 결정됨 | Spring Boot 호환 버전만 확인 |
| 운영 상태 | Spring Boot Actuator | 확인 필요 | 노출 endpoint와 보안 범위 |
| 테스트 DB | 별도 PostgreSQL 환경 우선 검토 | 확인 필요 | 운영 데이터 격리와 CI 재현성 |
| CI | GitHub Actions 후보 | 확인 필요 | 저장소 운영 방식과 실행 비용 |

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

### 0-I. 검증과 문서 동기화

검증 후보:

- Spring Context 실행 테스트
- 기본 health endpoint 테스트
- 공개·보호 endpoint 접근 테스트
- 정상·만료·위조 JWT 테스트
- PostgreSQL 연결 테스트
- Spring Modulith 경계 테스트
- 설정 파일의 비밀정보 검사
- 빌드와 정적 검사

작업 규칙에 따라 테스트·빌드·정적 검사는 실행 전에 사용자에게 별도 승인을 받는다.

산출물:

- 실행한 명령과 결과
- 실패 원인과 수정 결과
- 실행하지 못한 검증과 이유
- Phase 0 완료 증거
- Roadmap 상태 갱신

## 10. Phase 0 완료 기준

- [ ] Java·Spring Boot·Spring Modulith와 빌드 도구를 확정함
- [ ] Spring 프로젝트와 Wrapper가 저장소에 포함됨
- [ ] 환경별 설정과 비밀정보 주입 규칙이 적용됨
- [ ] Supabase JWT의 서명·issuer·만료를 검증함
- [ ] 검증된 JWT subject를 공통 인증 사용자로 사용할 수 있음
- [ ] 기존 PostgreSQL 연결과 migration 기반을 구성함
- [ ] 운영 DB와 테스트 DB를 분리함
- [ ] Spring Modulith가 모듈 경계를 검증함
- [ ] 공통 오류·validation·correlation ID가 적용됨
- [ ] health, 로그와 최소 지표를 확인함
- [ ] 기본 빌드·테스트·정적 검사가 통과함
- [ ] 비밀정보가 저장소와 로그에 포함되지 않음을 확인함
- [ ] 실행 방법과 검증 결과를 문서화함

## 11. 중단·롤백 기준

- Phase 0에서는 Flutter 호출과 운영 데이터의 쓰기 주체를 변경하지 않는다.
- 운영 DB 변경이나 실제 사용자 데이터 쓰기가 발생하려 하면 작업을 중단하고 범위를 다시 검토한다.
- JWT 방식이나 DB 권한을 확인하지 못하면 임의의 비밀 키 또는 관리자 권한으로 우회하지 않는다.
- 의존성 호환 문제가 발생하면 버전을 임의로 반복 변경하지 않고 공식 호환표를 다시 확인한다.
- 배포 문제가 발생하면 Spring 배포만 중단하거나 이전 버전으로 되돌리며 기존 Flutter·Supabase 서비스는 유지한다.

## 12. 위험과 미해결 질문

| 항목 | 위험 | 처리 방향 |
|---|---|---|
| JWT 서명 방식 | 잘못된 방식으로 검증하면 인증 우회 또는 정상 사용자 거부 가능 | 현재 프로젝트 설정과 공식 문서 확인 후 구현 |
| DB 권한 | 과도한 권한은 Spring 장애나 취약점의 영향 범위를 키움 | Spring 전용 최소 권한 검토 |
| 기존 migration | Supabase와 Spring migration 이력이 충돌할 수 있음 | 기준선과 이후 책임을 명확히 분리 |
| 테스트 DB | 운영 DB를 테스트에 사용하면 데이터 훼손 위험 | 별도 DB 또는 격리 환경 사용 |
| 모듈 구조 | 빈 package만 만들면 경계 검증의 의미가 약함 | 최소 공개 API 예제와 위반 탐지 테스트 구성 |
| 관찰 endpoint | Actuator가 과도하게 공개되면 내부 정보 노출 가능 | endpoint별 인증과 노출 범위 제한 |

## 13. 결정과 검증 기록

Phase 0 진행 중 다음 형식으로 누적한다.

| 날짜 | 구분 | 결과 | 근거·검증 |
|---|---|---|---|
| 2026-07-28 | 계획 | Phase 0 범위와 작업 순서 초안 작성 | Roadmap·TO-BE·ADR-001 기준 |

## 14. 바로 다음 작업

다음 작업은 `0-A. 현재 환경과 공식 자료 조사`다.

1. 로컬 Java와 빌드 도구 설치 상태 확인
2. 현재 Spring Boot 안정 버전과 Java 지원 범위 확인
3. Spring Modulith 호환 버전 확인
4. Supabase JWT 검증 방식과 PostgreSQL 연결 요구사항 확인
5. 기술 후보·추천·선택이 필요한 질문을 이 문서에 반영

조사 결과를 사용자와 검토한 뒤에만 Spring 프로젝트와 의존성을 생성한다.
