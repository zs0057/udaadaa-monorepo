# ADR-002 인프라 기술 검토

> 상태: 채택
> 작성일: 2026-07-28
> 결정: 대안 B

## 1. 목적

Udaadaa의 두 가지 마이그레이션 목표를 달성할 인프라 전환 방식을 결정한다.

- **비즈니스 로직 분리:** Flutter의 데이터 접근과 업무 규칙을 Spring으로 이동한다.
- **운영 안정성 확보:** 채팅 연결·복구·모니터링을 Spring에서 제어하고 최종적으로 Supabase 인프라 의존성을 제거한다.

## 2. 비교 가정

- 누적 가입자 3,000명, MAU 2,500명, DAU 1,000명
- 평시 동시 접속 500명, 최대 WebSocket 연결 1,000개
- 채팅 메시지 일 10,000개
- 2주 챌린지 진행 중 평상시 서비스 중단 불가
- 불가피한 전환은 새벽 1시에 수행
- Spring 인스턴스 2대와 Load Balancer를 운영 기준으로 사용
- 비용은 2026-07-28 On-Demand 가격을 사용한 계획 추정치

## 3. 대안 비교

| 대안 | 방식 | 장점 | 단점 | 개발량 | 운영비 추정 |
|---|---|---|---|---:|---:|
| A. Supabase 영구 유지 | Spring을 도입하되 Auth·DB·Storage는 계속 Supabase 사용 | 가장 빠르고 기존 사용자·데이터 호환성이 높음 | Supabase 장애·정책 의존성과 인프라 제어권 문제가 남음 | 8~12인주 | 약 $105~113/월 |
| B. Spring 안정화 후 제거 | Spring을 기존 Supabase에 연결한 뒤 마지막에 독립 인프라로 이전 | 변경 변수를 분리하고 기능별 검증·롤백 가능 | 2차 마이그레이션과 중복 운영비 발생 | 16~26인주 | 중간 $105~113, 최종 약 $159/월 |
| C. 독립 DB로 도메인별 제거 | 이전한 도메인부터 Spring과 독립 PostgreSQL 사용 | Supabase 의존성과 DB 제어권을 빠르게 확보 | 이전 기간 두 DB의 데이터 소유권·정합성 관리가 어려움 | 14~24인주 | 약 $159 + Supabase 병행 비용 |
| D. 전체 재구축 후 일괄 전환 | 신규 시스템을 모두 만든 후 한 번에 교체 | 최종 구조가 단순하고 레거시 제약이 적음 | 장애·데이터·Auth 전환 위험이 전체 사용자에게 집중 | 17~29인주 | 개발 중 이중 환경, 최종 약 $159/월 |

### 대안별 판단

- A는 비즈니스 로직 분리는 달성하지만 Supabase 의존성 제거 목표를 충족하지 못한다.
- B는 총개발량이 늘지만 운영 중 장애 범위와 롤백 범위가 가장 작다.
- C는 최종 구조에 빨리 도달하지만 두 DB를 함께 운영할 역량이 필요하다.
- D는 충분한 개발·QA 인력이 없는 운영 서비스에서 위험이 가장 크다.

## 4. 대안 B와 C 추가 분석

| 판단 기준 | B가 유리 | C가 유리 |
|---|---|---|
| 초기 개발 속도 | 기존 Auth·DB·Storage를 유지하고 Spring에 집중 |  |
| 사용자 세션·UUID·이미지 URL | 기존 구조를 초기에는 그대로 유지 |  |
| 장애 원인 추적 | Spring 오류와 인프라 이전 오류를 단계별로 분리 |  |
| 기능별 롤백 | 기존 데이터 저장소를 유지하여 상대적으로 단순 |  |
| 2주 챌린지 연속성 | 변경 범위가 작아 운영 위험이 낮음 |  |
| Supabase 제거 속도 |  | 이전한 도메인부터 의존성 제거 |
| DB 제어권 확보 |  | 독립 PostgreSQL 도입 시점부터 확보 |
| 최종 구조 도달 |  | 신규 기능을 목표 DB에 바로 구현 |
| 전체 개발량 |  | B의 2차 이전이 없어 계획상 조금 작음 |

### B의 추가 비용

- 1차 Spring 전환: 8~12인주
- 2차 Auth·DB·Storage 이전과 검증: **8~14인주**
- 2차 이전 기간에는 기존 환경과 신규 AWS 환경을 함께 운영한다.
- 병행 운영비는 약 $264~272/월이며 1~3개월 기준 약 $264~816이다.

## 5. Spring과 Supabase PostgreSQL 연결 검토

Spring이 Supabase PostgreSQL을 사용하는 것은 기술적으로 가능하다. Supabase Database는 PostgreSQL이므로 JDBC, JPA와 Flyway를 사용할 수 있다.

초기 연결은 다음 규칙을 지킨다.

- 장기 실행 Spring 서버는 Direct Connection을 우선 사용하고 IPv4 환경에서는 Session Pooler를 검토한다.
- Transaction Pooler는 Prepared Statement 제약이 있어 일반적인 JPA 연결에 사용하지 않는다.
- Spring 인스턴스당 Hikari Connection을 10~15개로 제한한다.
- Spring 전용 DB Role에 최소 권한만 부여한다.
- 신규 Schema는 표준 PostgreSQL과 Flyway로 관리한다.
- 신규 로직에서 `auth.uid()`, Data API, Realtime과 Supabase 전용 Extension 의존성을 추가하지 않는다.
- Supabase 사용자 ID는 외부 식별자로 매핑하여 최종 Auth 이전 가능성을 보존한다.

DB 연결 자체에는 별도 과금이 없지만 Connection 고갈이나 부하로 Compute 등급을 높이면 비용이 증가한다. 또한 1차 단계에서는 Supabase DB 장애가 Spring에도 영향을 주므로 인프라 안정성 목표는 아직 완료되지 않는다.

출처: [Supabase Database 연결](https://supabase.com/docs/guides/database/connecting-to-postgres), [Supabase Pricing](https://supabase.com/pricing)

## 6. 비용 요약

| 구성 | 현재 이미지 방식 | 이미지 최적화 후 |
|---|---:|---:|
| 기존 Supabase 중심 | 약 $57/월 | 약 $49/월 |
| Spring + Supabase 고가용성 | 약 $113/월 | 약 $105/월 |
| Spring + AWS 고가용성 | 약 $159/월 | 약 $159/월 |

Supabase 비용은 Storage CDN Cache Hit Ratio 80%를 가정했다.

- 현재 원본 자동 로딩: Storage Egress 약 648GB, 초과 비용 약 $8.05/월
- 썸네일 우선·원본 지연 로딩: 약 259GB, 예상 초과 비용 $0
- 실제 비용은 운영 환경의 Cache Hit Ratio, DB·Auth·Realtime Egress와 이미지 열람률로 다시 계산한다.

출처: [Supabase Egress](https://supabase.com/docs/guides/platform/manage-your-usage/egress), [Amazon RDS Pricing](https://aws.amazon.com/rds/postgresql/pricing/), [Amazon S3 Pricing](https://aws.amazon.com/s3/pricing/)

## 7. 결정

**대안 B를 채택한다.**

```text
1차: Flutter → Spring REST·STOMP → 기존 Supabase Auth·DB·Storage
2차: Flutter → Spring REST·STOMP → 독립 Auth·PostgreSQL·Storage
```

선택 이유는 비용이 아니라 운영 위험이다.

- 1차에서 비즈니스 로직 분리와 채팅 저장·전달·복구 제어를 달성한다.
- Spring 기능과 인프라 이전을 분리하여 장애 원인을 좁힌다.
- 기존 사용자 세션과 데이터 경로를 유지하여 기능별 전환과 롤백을 단순화한다.
- 진행 중인 2주 챌린지의 데이터 유실과 전체 장애 가능성을 줄인다.
- 2차에서 Supabase Auth·DB·Storage를 제거하여 인프라 제어권을 확보한다.

## 8. 종료 조건

대안 B는 영구 구조가 아닌 과도기 구조다. 다음 조건을 충족하면 2차 이전을 시작한다.

- Flutter의 Supabase Database 직접 쓰기 제거
- Spring REST·STOMP 기능별 전환 완료
- STOMP 1,000 Connection과 메시지 누락·재연결 테스트 통과
- Auth 사용자·세션 이전 POC 통과
- PostgreSQL 백업·복원과 데이터 정합성 검증 통과
- Storage 객체·URL·checksum 이전 리허설 통과
- 새벽 1시 전환·롤백 Runbook 검증 완료

