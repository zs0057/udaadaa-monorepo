# ADR-003 Supabase·Spring 호환성 검토

> 상태: 채택  
> 작성일: 2026-07-28

## 1. 목적

대안 B의 1차 구조가 기존 사용자·데이터를 유지하면서 Spring을 도입할 수 있는지 확인한다.

```text
Flutter → Spring REST·STOMP → Supabase Auth·PostgreSQL·Storage
```

Supabase를 즉시 제거하지 않는다. Spring을 업무 규칙과 통신의 중심으로 먼저 전환하고, 인프라 교체는 2차로 분리한다.

## 2. 호환성 및 사용 경계

| 기존 Supabase 기술 | Spring에서 추가할 기술 | 1차 정책 | 호환성·주의점 |
|---|---|---|---|
| Auth·JWT | Spring Security | 유지 | Spring이 서명, 발급자, 만료와 subject를 검증한다. 현재 JWT 서명 방식과 공개 키 조회 방식을 구현 전에 확인한다. |
| PostgreSQL | JDBC·JPA·Flyway | 유지 | 표준 PostgreSQL 연결이므로 호환된다. 장기 실행 서버는 Direct Connection 또는 Session Pooler를 사용하고, Transaction Pooler는 JPA Prepared Statement 제약으로 사용하지 않는다. |
| Storage | Spring 업로드 승인 API | 유지 | Spring이 사용자·경로·파일 조건을 승인하고 Flutter가 제한적으로 직접 업로드한다. 기존 URL과 객체를 유지한다. |
| RLS Policy | Spring Security·DB Role | 과도기 유지 | Flutter 직접 접근이 남은 데이터는 RLS로 보호한다. Spring 전용 최소 권한 Role과 RLS 적용 범위는 Phase 0에서 검증한다. |
| Realtime | Spring STOMP WebSocket | 신규 사용 중단 | 기술적으로 병행 가능하지만, 채팅 전달·재연결·지표를 Spring이 제어하기 위해 전환 완료 기능부터 대체한다. |
| Edge Function·DB Trigger·RPC | Spring Application Service·내부 이벤트 | 기능별 대체 | Push·미션 등 부수효과의 쓰기 주체를 Spring으로 옮긴 뒤 기존 실행 경로를 제거한다. 중복 실행을 허용하지 않는다. |
| Data API·Flutter 직접 DB 호출 | Spring REST API | 기능별 제거 | 신규 업무 기능은 추가하지 않는다. Flutter는 전환이 끝난 기능부터 Spring API만 호출한다. |
| Supabase 전용 Extension | 표준 PostgreSQL 기능 | 신규 의존 금지 | 2차 독립 PostgreSQL 이전을 막는 새 의존성을 만들지 않는다. |

## 3. 결정

- 1차에서는 Auth·PostgreSQL·Storage를 Supabase 인프라로 유지한다.
- 채팅은 Supabase Realtime 대신 Spring STOMP와 REST 기반 누락 복구로 전환한다.
- 기존 RLS·Trigger·Edge Function은 해당 기능의 Spring 전환이 검증될 때까지 유지한다.
- Spring 전환 후에는 Flutter의 직접 DB 호출과 Supabase 전용 서버 로직을 제거한다.

## 4. 기존 RLS 대응 방안

Spring Security와 Supabase RLS는 자동으로 사용자 정보를 공유하지 않는다. Spring은 JDBC/JPA의 DB Role로 연결하므로 기존 `auth.uid()` 기반 RLS를 Spring 권한 검사에 재사용하지 않는다.

| 단계 | Flutter 직접 접근 | 업무 권한 기준 | RLS 처리 |
|---|---|---|---|
| 과도기 | 전환되지 않은 기능만 허용 | Spring 전환 기능은 Spring 내부 정책 | 기존 `authenticated` 정책 유지, `spring_app` 전용 정책 추가 |
| 기능 전환 완료 | 해당 기능 제거 | Spring 내부 정책 | 해당 기능의 Flutter용 RLS·Data API 권한 제거 |
| 2차 인프라 이전 | 없음 | Spring 내부 정책 | Supabase `auth.uid()` 기반 정책 제거 |

- `spring_app` Role은 서버 환경에서만 사용하며 Flutter에 노출하지 않는다.
- Spring은 방 참가, 차단, 데이터 소유권 같은 업무 권한을 직접 검사한다.
- `spring_app`의 RLS 정책은 서버 Role 경계만 확인하며, 사용자별 권한 규칙을 중복 구현하지 않는다.
- 기능별 쓰기 주체는 Flutter 또는 Spring 중 하나만 활성화한다.

## 5. 구현 전 확인

| 항목 | 확인 기준 |
|---|---|
| JWT | 실제 signing key 방식, issuer·audience·만료 검증과 key rotation 대응 |
| DB | Spring DB Role의 최소 권한, RLS와의 동작, Connection 수 제한 |
| Storage | 업로드 경로·MIME·크기 제한, 업로드 후 파일 참조 검증, 미완료 파일 정리 |
| 전환 | 기존 Realtime·Trigger·Edge Function과 Spring 경로가 동시에 실행되지 않는지 확인 |

## 6. 결론

Supabase와 Spring의 병행은 가능하다. 단, 이는 Supabase 기능을 그대로 사용하는 구조가 아니라 **Supabase는 초기 인프라 제공자**, **Spring은 서비스 제어권의 중심**으로 역할을 분리하는 과도기 구조다. 2차 이전 시 Auth·PostgreSQL·Storage를 독립 인프라로 교체한다.

출처: [Supabase Database 연결](https://supabase.com/docs/guides/database/connecting-to-postgres), [Supabase JWT](https://supabase.com/docs/guides/auth/jwts), [Supabase Storage Access Control](https://supabase.com/docs/guides/storage/security/access-control)
