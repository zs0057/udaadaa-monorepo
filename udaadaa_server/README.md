# Udaadaa Server

Udaadaa의 Spring 기반 모듈형 모놀리스 백엔드다. Phase 0의 공통 기반 위에서 도메인 기능을 단계적으로 이전한다.

## 기술 기준

- Java 21
- Spring Boot 4.1.0
- Spring Modulith 2.1.0
- Gradle Kotlin DSL과 Gradle Wrapper
- Spring MVC, Security, JPA, Flyway와 Actuator
- PostgreSQL 15 기반 테스트

## 환경 변수

`.env.example`을 기준으로 로컬 환경에 값을 주입한다. `.env` 파일과 실제 비밀값은 커밋하지 않는다.

- 현재 Supabase 프로젝트가 레거시 대칭키를 사용하면 `SUPABASE_JWT_MODE=hmac`과 `SUPABASE_JWT_SECRET`을 사용한다.
- 비대칭 signing key로 전환한 뒤에는 `SUPABASE_JWT_MODE=jwks`와 `SUPABASE_JWT_JWK_SET_URI`를 사용한다.
- `DB_MIGRATION_ENABLED`와 `DB_MIGRATION_BASELINE_ON_MIGRATE`는 Flyway 기준선 검토 전에는 `false`로 둔다.
- Actuator는 외부에 `health`만 노출한다. 내부 지표는 운영자 인증 또는 별도 관리망이 준비된 뒤 추가한다.
- correlation ID는 모든 환경의 로그 level 패턴에 포함한다.

## 로컬 개발 환경

로컬 실행은 운영 Supabase와 분리된 Docker PostgreSQL을 사용한다. 데이터는 Docker 볼륨에 유지되며 Testcontainers의 일회성 테스트 DB와 별개다.

```bash
docker compose -f compose.local.yml up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

IntelliJ에서는 `UdaadaaApplication` 실행 구성의 Active profiles에 `local`을 지정한다. 별도 DB 환경 변수는 필요하지 않다.

기본 서버 포트 `8080`이 이미 사용 중이면 실행 옵션에 `--server.port=18080`을 추가하고 아래 URL의 포트도 함께 변경한다.

서버 실행 확인:

```bash
curl http://localhost:8080/actuator/health
```

로컬 JWT를 생성하고 Member API를 호출할 수 있다. 이 JWT와 비밀키는 로컬 전용이며 운영 환경에서 사용하지 않는다.

```bash
TOKEN=$(python3 scripts/generate-local-jwt.py)

curl -X POST http://localhost:8080/api/v1/members/me/initialize \
  -H "Authorization: Bearer $TOKEN"

curl http://localhost:8080/api/v1/members/me \
  -H "Authorization: Bearer $TOKEN"

curl -X PATCH http://localhost:8080/api/v1/members/me \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"로컬 사용자","height":170.5,"weight":65.2}'
```

로컬 DB 중지와 재시작:

```bash
docker compose -f compose.local.yml stop
docker compose -f compose.local.yml start
```

`docker compose down`은 컨테이너만 제거하고 데이터 볼륨은 유지한다. 볼륨 삭제는 로컬 데이터를 잃으므로 별도 판단 후 수행한다.

## 검증

```bash
./gradlew clean test
```

통합 테스트는 Docker에서 격리된 PostgreSQL 컨테이너를 사용하며 운영 DB에 접근하지 않는다.

Docker를 사용할 수 없는 로컬 환경에서는 별도로 만든 테스트 PostgreSQL에 다음 환경 변수를 주입한다.

```bash
TEST_DATABASE_CONTAINER_ENABLED=false \
DB_URL=jdbc:postgresql://localhost:5432/udaadaa_test \
DB_USERNAME=udaadaa_test \
DB_PASSWORD=replace-with-test-password \
./gradlew clean test
```

Phase 0의 실행 결과와 운영 연동 대기 항목은 [Phase 0 Verification](../docs/migration/phases/phase-00-verification.md)에 기록한다.
