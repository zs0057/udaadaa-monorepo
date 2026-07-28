# ADR-003 목표 인프라 선택

> 상태: 조건부 채택
> 작성일: 2026-07-28
> 가격 기준일: 2026-07-28
> 결정 여부: 대안 B 채택, 최종 독립 인프라는 부하 테스트 후 확정

## 1. 결정할 문제

Supabase를 제거한다면 Udaadaa의 PostgreSQL, Auth와 Object Storage를 어떤 인프라에서 운영할지 결정해야 한다.

이 문서는 다음 질문에 답하기 위한 조사 초안이다.

- Supabase Pro와 AWS 기반 인프라의 월 비용은 얼마나 다른가?
- Supabase PostgreSQL과 AWS RDS PostgreSQL의 사용 방식과 운영 책임은 어떻게 다른가?
- 누적 사용자 3,000명, DAU 1,000명 규모에서 비용 차이가 마이그레이션을 정당화할 만큼 큰가?
- 비용, 제어권, 가용성, 운영 부담 중 무엇을 우선해야 하는가?

Supabase 제거 시점은 [ADR-002](ADR-002-supabase-removal-timing.md)에서 별도로 결정한다.

## 2. 비교 가정

- 누적 가입자: 3,000명
- MAU: 2,500명
- DAU: 1,000명
- 평시 동시 접속: 500명
- 최대 WebSocket 연결: 1,000개
- 채팅 메시지: 일 10,000개
- 이미지 메시지: 사용자당 일 6개, 전체 일 6,000개
- 텍스트 메시지: 일 4,000개
- 평균 채팅방 인원: 10명, 발신자를 제외한 평균 수신자 9명
- 로그인 구성: Kakao 70%, Apple 29%, Email 1%
- 채팅 이미지 정책: 방에서는 14일간 조회, 이후 업로더만 조회, 업로드 6개월 후 삭제
- 이미지 평균 크기: 원본 압축본 400KB, 썸네일 80KB
- 텍스트와 메시지 메타데이터 보관: 3년
- 허용 가능한 일반 응답 지연: 약 1~2초
- AWS Region: Asia Pacific (Seoul), `ap-northeast-2`
- 월 사용 시간: 730시간
- PostgreSQL Storage: 초기 20GB 기준
- Object Storage: 정상 상태 약 439GB
- On-Demand 요금, 약정 할인과 신규 계정 크레딧 제외
- 세금, 환율, 운영 인건비 제외
- 실제 CPU, Memory, Cache Hit Ratio와 이미지 열람률은 미확인
- 가격 비교용 Storage CDN Cache Hit Ratio: 80%
- Database·Auth·Realtime 등 기타 Uncached Egress: 월 25GB
- 챌린지는 2주 동안 매일 진행되므로 서비스 운영 중 상시 중단은 허용하지 않음
- 계획된 전환은 이용량이 낮은 새벽 1시에만 수행
- 전환 시 짧은 쓰기 중단은 허용하되 진행 중인 챌린지와 제출 데이터가 유실되어서는 안 됨

사용자 수만으로 적정 DB 사양을 정할 수 없다. 쿼리 수, 동시 접속, 메시지 쓰기량, 인덱스와 이미지 트래픽을 부하 테스트한 뒤 최종 크기를 결정한다.

### 2.1 성능·사용량 계획 가정

현재 서비스가 운영 중이고 목표 사용량이 발생한다고 가정한다. 다음 수치는 실측값이 아니라 초기 인프라와 부하 테스트를 설계하기 위한 계획값이다.

| 항목 | 계획 가정 | 선정 이유 |
|---|---:|---|
| DB Compute | 2 vCPU | API, 채팅 저장과 배치 작업을 시작할 최소 기준 |
| DB Memory | 2GB | 초기 RDS Small·Supabase Small 비교 기준 |
| 일일 DB Query | 약 300,000건 | DAU 1,000명의 인증·챌린지·채팅·기록 조회와 일 10,000개 메시지 쓰기를 포함한 여유 추정 |
| 평시 DB QPS | 약 3.5 | 일일 Query를 균등 분산한 평균값 |
| 목표 Peak DB QPS | 50 | 특정 시간의 미션 제출과 채팅 집중을 고려한 시험 목표 |
| DB Connection | 평시 30, Peak 60 | Connection Pool을 사용한다는 초기 가정 |
| CPU 통과 기준 | 평균 40% 이하, p95 70% 이하 | 일시적 Spike와 장애 복구 여유 확보 |
| Memory 통과 기준 | 평균 70% 이하 | 캐시·Connection 증가와 배치 실행 여유 확보 |
| DB 응답 기준 | p95 200ms 이하 | 전체 API p95 1초 이내를 위한 DB 구간 예산 |

### 2.2 가용성과 전환 가정

- 챌린지 진행 중 API·채팅·미션 제출은 평상시에 중단하지 않는다.
- 배포는 Rolling 또는 Blue-Green 방식으로 Spring 인스턴스를 한 대씩 교체한다.
- Schema 변경은 이전 버전과 새 버전이 동시에 동작할 수 있는 Expand–Migrate–Contract 방식으로 수행한다.
- 불가피한 데이터 전환은 새벽 1시에 수행하고, 시작 전에 백업과 롤백 지점을 만든다.
- 전환 중 요청을 잃지 않도록 일시적인 읽기 전용 또는 쓰기 차단 여부를 기능별로 결정한다.
- 점검 시간의 정확한 상한은 리허설 결과로 정하며, 검증하지 않은 상태에서 무중단을 보장하지 않는다.

## 3. Supabase PostgreSQL과 AWS RDS PostgreSQL 차이

| 관점 | Supabase Pro | AWS RDS PostgreSQL |
|---|---|---|
| 제품 성격 | PostgreSQL과 Auth·Data API·Realtime·Storage·Functions를 묶은 관리형 플랫폼 | PostgreSQL 운영에 집중한 관리형 데이터베이스 |
| 애플리케이션 접근 | Flutter가 SDK·Data API·RPC·Realtime으로 직접 접근 가능 | 일반적으로 Spring이 JDBC·JPA로 접근하고 API를 제공 |
| 권한 | RLS, `auth.uid()`와 Supabase 역할이 강하게 결합 | PostgreSQL 역할, Spring 권한 검증과 네트워크 접근 제어를 직접 설계 |
| API | REST Data API와 Realtime이 기본 제공 | REST·WebSocket 서버를 별도로 구현·운영 |
| Auth | 기본 포함 | Cognito, Keycloak 또는 자체 인증이 별도 필요 |
| 파일 | Storage와 정책이 기본 포함 | S3 등 Object Storage가 별도 필요 |
| 백업 | Pro에 일일 백업과 7일 보관 포함 | 자동 백업 보관 기간과 Snapshot을 직접 구성 |
| 고가용성 | 플랫폼이 제공하는 범위와 요금제에 의존 | Single-AZ 또는 Multi-AZ를 직접 선택 |
| 모니터링 | Dashboard와 제한된 Log 보관을 기본 제공 | CloudWatch, Performance Insights와 경보를 직접 구성 |
| 제어권 | 빠르고 단순하지만 플랫폼 구성에 영향받음 | 네트워크, DB Parameter, 가용성과 운영 정책 선택 범위가 넓음 |
| 운영 부담 | 낮음 | Supabase보다 높지만 직접 PostgreSQL 설치보다는 낮음 |

Supabase는 단순히 PostgreSQL 서버만 판매하는 서비스가 아니다. 따라서 Supabase Pro $25와 RDS 인스턴스 가격만 비교하면 AWS 비용이 실제보다 낮게 보인다.

## 4. Supabase Pro 가격

공식 가격 기준:

- Pro Plan: 월 $25
- 유료 조직의 Compute Credit: 월 $10
- Micro Compute: 월 약 $10
- Small Compute: 월 약 $15
- Database Disk: 프로젝트당 8GB 포함, 초과분 GB당 월 $0.125
- Monthly Active User: 100,000명 포함
- File Storage: 100GB 포함
- Egress와 Cached Egress: 각각 250GB 포함
- 일일 백업: 7일 보관

### 월 예상 비용

| 구성 | 계산 | 월 비용 |
|---|---:|---:|
| Pro + Micro 1개 프로젝트 | $25 + $10 - $10 Credit | **$25** |
| Pro + Small 1개 프로젝트 | $25 + $15 - $10 Credit | **$30** |
| Pro + Medium 1개 프로젝트 | $25 + $60 - $10 Credit | **$75** |

목표 MAU 2,500명은 Pro의 100,000 MAU 포함량보다 작다. Auth 자체의 MAU 초과 요금은 발생하지 않지만 Storage와 Egress는 목표 사용량에 따라 초과 요금이 발생한다.

주의할 점:

- 개발·Staging 프로젝트를 추가하면 Compute 비용이 늘어난다.
- PITR, Log Drain, 초과 Disk·Egress·Storage는 별도 비용이다.
- Micro와 Small이 채팅 부하를 감당하는지는 별도 부하 테스트가 필요하다.

출처: [Supabase Pricing](https://supabase.com/pricing), [Supabase Compute Usage](https://supabase.com/docs/guides/platform/manage-your-usage/compute)

## 5. AWS RDS PostgreSQL 가격

AWS 공식 Price List의 서울 리전 On-Demand 가격을 사용했다.

### DB 인스턴스와 20GB gp3

| 구성 | Compute | Storage | 월 예상 비용 |
|---|---:|---:|---:|
| `db.t4g.micro` Single-AZ, 1GiB | $0.025 × 730 = $18.25 | 20GB × $0.131 = $2.62 | **$20.87** |
| `db.t4g.small` Single-AZ, 2GiB | $0.051 × 730 = $37.23 | 20GB × $0.131 = $2.62 | **$39.85** |
| `db.t4g.micro` Multi-AZ, 1GiB | $0.051 × 730 = $37.23 | 20GB × $0.262 = $5.24 | **$42.47** |
| `db.t4g.small` Multi-AZ, 2GiB | $0.102 × 730 = $74.46 | 20GB × $0.262 = $5.24 | **$79.70** |

포함하지 않은 항목:

- 할당량을 초과한 Backup·Snapshot Storage
- Internet·AZ 간 데이터 전송
- CloudWatch Logs와 추가 모니터링
- RDS Proxy
- Spring 서버, Load Balancer와 NAT Gateway
- Auth, Object Storage와 WebSocket 인프라
- 도메인, 인증서와 비밀 관리

Multi-AZ는 다른 가용 영역에 Standby를 두고 장애 시 자동 전환하기 때문에 Single-AZ보다 비용이 높다. 운영 안정성을 공정하게 비교하려면 가장 저렴한 Single-AZ뿐 아니라 Multi-AZ 비용도 함께 봐야 한다.

출처: [Amazon RDS for PostgreSQL Pricing](https://aws.amazon.com/rds/postgresql/pricing/), [AWS RDS 공식 Price List 서울 리전](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonRDS/current/ap-northeast-2/index.json)

## 6. AWS의 추가 서비스 비용

RDS는 Supabase Pro에 포함된 Auth, Storage와 Realtime을 제공하지 않는다.

### Auth

- Amazon Cognito는 기본 기능 기준 일정 MAU까지 무료 구간이 있다.
- 2,500 MAU는 기본 인증의 무료 구간 안에 들어갈 수 있다.
- Advanced Security, SMS, Email, 추가 요청 용량과 일부 외부 사용자 유형은 별도 비용이 발생할 수 있다.
- 기존 Supabase Auth 사용자·OAuth 연결·세션 이전 작업은 서비스 요금과 별도의 개발 리소스다.

출처: [Amazon Cognito Pricing](https://aws.amazon.com/cognito/pricing/)

### Object Storage

서울 리전 S3 Standard의 첫 50TB 구간은 GB당 월 $0.025다.

| 저장량 | Storage만 계산한 월 비용 |
|---|---:|
| 10GB | 약 $0.25 |
| 50GB | 약 $1.25 |
| 100GB | 약 $2.50 |

GET·PUT 요청, 데이터 전송, CDN과 이미지 변환 비용은 별도다. Supabase Pro에는 File Storage 100GB와 기본 Egress 할당량이 포함되어 있으므로 저장 용량만 비교하면 안 된다.

출처: [Amazon S3 Pricing](https://aws.amazon.com/s3/pricing/), [AWS S3 공식 Price List 서울 리전](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/ap-northeast-2/index.json)

### Spring과 실시간 통신

- Spring 서버 Compute 비용은 Supabase 유지 여부와 관계없이 새 아키텍처에 필요하다.
- 비교 가능한 초기 기준으로 AWS Lightsail Linux 2 vCPU·4GB 인스턴스 월 $24를 사용한다.
- 고가용성 구성은 인스턴스 2대 월 $48와 Load Balancer 월 $18를 사용한다.
- 자체 STOMP WebSocket을 사용하면 1,000개 동시 연결과 메시지 전달을 부하 테스트해야 한다.
- 실제 운영에서는 로그, 백업, 알림과 이미지 처리 비용이 추가될 수 있다.

출처: [Amazon Lightsail Bundles](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html), [Lightsail Billing FAQ](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html)

## 7. 목표 사용량 기준 가격 비교

### 7.1 Storage와 Egress 산정

이미지를 영구 누적하지 않고 정해진 보관 정책에 따라 삭제한다.

```text
원본 저장량 = 일 6,000개 × 400KB × 180일 = 432GB
썸네일 저장량 = 일 6,000개 × 80KB × 14일 = 6.72GB
정상 상태 저장량 = 약 439GB
```

월 Egress는 현재 방식과 최적화 이후 방식을 나누어 계산한다.

#### 현재 방식: 원본 자동 로딩

기존 Flutter는 채팅·피드 이미지를 고정된 Public URL로 직접 표시한다. 같은 URL은 CDN Cache에 유리하지만, 썸네일 없이 원본을 수신자에게 표시하므로 전송량이 크다.

```text
월 Storage Egress
= 일 6,000개 × 400KB × 수신자 9명 × 30일
= 약 648GB
```

#### 최적화 이후: 썸네일 우선, 원본 지연 로딩

```text
썸네일 Egress = 일 6,000개 × 80KB × 수신자 9명 × 30일 = 129.6GB
원본 Egress = 일 6,000개 × 400KB × 열람률 20% × 수신자 9명 × 30일 = 129.6GB
월 합계 = 약 259GB
```

따라서 썸네일 우선 표시, 원본 지연 로딩과 CDN Cache가 비용 절감의 전제다.

### 7.2 Cached·Uncached Egress 구분

Supabase는 CDN Cache Hit를 Cached Egress로, Cache Miss와 Database·Auth·Realtime 등의 전송을 Uncached Egress로 계산한다. 두 사용량은 Pro에서 각각 250GB를 제공하며 초과 단가는 Cached $0.03/GB, Uncached $0.09/GB다.

80% Cache Hit와 기타 Uncached Egress 25GB를 기준으로 계산한다.

| 이미지 처리 방식 | Storage 전체 | Cached 80% | Uncached 20% + 기타 25GB | 월 초과 비용 |
|---|---:|---:|---:|---:|
| 현재 원본 자동 로딩 | 648GB | 518.4GB | 154.6GB | **약 $8.05** |
| 썸네일 최적화 | 259.2GB | 207.4GB | 76.8GB | **$0** |

현재 방식의 Egress 초과 비용은 다음과 같다.

```text
Cached 초과 = (518.4GB - 250GB) × $0.03 = $8.05
Uncached 초과 = max(154.6GB - 250GB, 0) × $0.09 = $0
```

Cache Hit Ratio에 따른 현재 방식의 범위는 다음과 같다.

| Cache Hit Ratio | Cached Egress | Uncached Egress + 기타 | 월 초과 비용 |
|---:|---:|---:|---:|
| 0% | 0GB | 673GB | 약 $38.07 |
| 50% | 324GB | 349GB | 약 $11.13 |
| 80% | 518.4GB | 154.6GB | 약 $8.05 |
| 100% | 648GB | 25GB | 약 $11.94 |

80%보다 100% Cache Hit의 비용이 큰 이유는 100%일 때 사용량이 Cached 무료 한도 한쪽에만 몰리기 때문이다. 실제 Cache Hit Ratio는 Logs Explorer의 `cf-cache-status`를 기준으로 측정해야 한다.

출처: [Supabase Egress Usage](https://supabase.com/docs/guides/platform/manage-your-usage/egress), [Supabase Cache Metrics](https://supabase.com/docs/guides/storage/cdn/metrics), [Supabase Smart CDN](https://supabase.com/docs/guides/storage/cdn/smart-cdn)

### 7.3 Supabase 예상 비용

Supabase Pro와 Small Compute를 사용하고 PostgreSQL 20GB, Storage 439GB, 현재 원본 자동 로딩과 Cache Hit Ratio 80%를 가정한다.

| 항목 | 계산 | 월 예상 |
|---|---:|---:|
| Pro + Small Compute | $25 + $15 - $10 Credit | $30.00 |
| Database Disk | (20GB - 8GB) × $0.125 | $1.50 |
| File Storage | (439GB - 100GB) × $0.0213 | $7.22 |
| Cached·Uncached Egress | 현재 방식, Cache Hit Ratio 80% | $8.05 |
| Realtime 메시지 | 월 270만, 포함량 500만 이하 | $0.00 |
| Peak Connection | 1,000개, 포함량 500개 초과 | 약 $10.00 |
| **기존 Supabase 중심 구조 합계** |  | **약 $57** |

Spring STOMP로 Realtime을 대체하면 Supabase의 Auth·Database·Storage 예상 비용은 현재 이미지 방식에서 약 $47이다. 여기에 Spring 인스턴스 1대를 더하면 약 $71, 인스턴스 2대와 Load Balancer를 사용하면 약 $113이다. 썸네일 최적화 이후에는 Egress 초과 비용이 사라져 각각 약 $63와 $105로 낮아진다.

### 7.4 AWS 예상 비용

S3 서울 리전 Standard는 저장량 439GB에 약 $10.98이고, 일 12,000건의 원본·썸네일 업로드를 고려한 요청 비용은 월 약 $2로 추정한다. CloudFront의 기본 무료 사용량 안에서 월 259GB를 전송한다고 가정한다. Cognito는 MAU 2,500명의 기본 인증이 무료 구간 안에 있다고 가정하며 SMS와 고급 보안 기능은 제외한다.

| 구성 | Spring | Database | Storage·요청 | Auth·CDN | 월 예상 |
|---|---:|---:|---:|---:|---:|
| AWS 단일 구성 | Lightsail $24 | RDS Small Single-AZ $39.85 | S3 약 $13 | 약 $0 | **약 $77** |
| AWS 고가용성 구성 | Lightsail 2대 + LB $66 | RDS Small Multi-AZ $79.70 | S3 약 $13 | 약 $0 | **약 $159** |

CloudFront 무료 사용량, Cognito 정책과 실제 Cache Hit Ratio에 따라 비용은 달라질 수 있다.

출처: [Amazon S3 Pricing](https://aws.amazon.com/s3/pricing/), [Amazon CloudFront FAQ](https://aws.amazon.com/cloudfront/faqs/), [Amazon Cognito Pricing](https://aws.amazon.com/cognito/pricing/)

### 7.5 전체 비교

| 대안 | 초기 단일 구성 | 고가용성 구성 | 해석 |
|---|---:|---:|---|
| 기존 Supabase 중심 구조 | 약 $57 | 별도 산정 필요 | 가장 저렴하지만 비즈니스 로직 분리 목적을 충족하지 못함 |
| B. Spring + Supabase | **약 $71** | **약 $113** | 이미지 최적화 후 약 $63·$105로 감소 |
| C. Spring + AWS | **약 $77** | **약 $159** | 제어권은 높지만 Auth·DB·Storage 이전 작업과 운영 부담 추가 |
| D. AWS 전면 재구축 | C와 유사 | C와 유사 | 월 운영비보다 초기 마이그레이션 리소스와 사용자 영향이 큼 |

현재 이미지 방식의 고가용성 구성에서 B와 C의 차이는 월 약 $46다. 이미지 최적화 후에는 B가 약 $105로 낮아져 차이는 약 $54가 된다. 비용 차이보다 기존 사용자 Auth·데이터·파일을 한 번에 옮기는 개발 비용과 장애 위험도 함께 판단해야 한다.

### 7.6 마이그레이션 목표·개발비·운영비 비교

개발비는 개발자 1명을 기준으로 한 인주다. 실제 금액은 `예상 인주 × 조직의 1인주 단가`로 계산한다. 운영 서비스는 2주 챌린지의 연속성을 고려하여 이중 Spring 인스턴스와 Load Balancer를 둔 구성을 기준으로 비교한다.

| 판단 기준 | B. Spring 전환 후 제거 | C. 독립 DB로 도메인별 제거 | D. 전체 재구축 후 일괄 제거 |
|---|---|---|---|
| 초기 목표 | 기존 Auth·DB·Storage를 유지하고 Spring으로 로직과 채팅 제어권부터 이동 | 신규 도메인부터 독립 DB에 구현 | 전체 목표 시스템을 별도 구축 |
| 최종 목표 | 마지막 단계에서 Supabase 완전 제거 | 도메인별 제거 후 Auth·Storage 제거 | 일괄 전환 시 완전 제거 |
| 예상 개발량 | 16~26인주 | 14~24인주 | 17~29인주 |
| 개발비 특성 | 총량은 클 수 있지만 Spring 전환과 인프라 이전의 실패 원인을 분리 | 총량은 비교적 작지만 두 DB의 데이터 소유권 관리 비용 발생 | 전체 E2E·이전·롤백 검증 비용이 한 시점에 집중 |
| 초기 운영비 | **현재 약 $113/월, 이미지 최적화 후 약 $105/월** | **약 $159/월 + 전환 기간 Supabase 비용** | 신규 AWS 약 $159 + 전환 전 기존 Supabase 병행 비용 |
| 운영 복잡성 | 초기 낮음, 최종 이전 시 일시 증가 | 이전 기간 가장 높음 | 개발 중 이중 환경, 전환 후 낮음 |
| 2주 챌린지 영향 | 기능별 전환과 롤백이 가능하여 가장 낮음 | 도메인 간 참조가 남으면 장애·정합성 위험 | 최종 장애가 전체 사용자와 챌린지에 집중 |
| 새벽 1시 전환 적합성 | 기능별 작은 전환으로 적합 | 도메인별 전환 가능하나 두 DB 검증 필요 | 전체 데이터·Auth·Storage를 한 번에 바꾸기 어려움 |
| 초기 마이그레이션 목표 부합 | **높음** | 중간 | 낮음 |
| 최종 제거 목표 부합 | 높음 | 높음 | 높음 |
| 결론 | **채택** | 보류 | 제외 |

대안 B를 선택한 가장 큰 이유는 월 비용이 가장 낮아서만이 아니다. 챌린지가 진행되는 동안 Spring 기능 오류와 인프라 이전 오류를 동시에 만들지 않고, 기능별로 검증·롤백할 수 있기 때문이다.

### 7.7 채팅 Realtime 시나리오

Supabase Realtime 메시지는 DB 변경 1건이 아니라 **그 변경을 수신한 클라이언트 수만큼** 계산된다.

```text
월 Realtime 메시지
= 일 메시지 10,000개 × 30일 × 메시지당 평균 수신 연결 수
```

Pro에는 월 500 Peak Connection과 500만 Realtime 메시지가 포함된다. 초과 Peak Connection은 1,000개 묶음당 $10, 초과 Realtime 메시지는 100만 건당 $2.50이다.

목표 Peak Connection 1,000개를 허용하려면 기본 Pro 한도 500개를 넘기 때문에 Spend Cap을 해제하고 프로젝트 Realtime 한도를 조정해야 한다. Peak Connection 초과 예상 비용은 월 $10이다.

| 메시지당 평균 수신자 | 월 Realtime 메시지 | 메시지 초과 비용 | 전체 Supabase 예상 |
|---:|---:|---:|---:|
| 5명 | 150만 | $0 | 약 $57 |
| 10명 | 300만 | $0 | 약 $57 |
| 20명 | 600만 | 약 $2.50 | 약 $59.50 |
| 50명 | 1,500만 | 약 $25 | 약 $82 |
| 100명 | 3,000만 | 약 $62.50 | 약 $119.50 |
| 1,000명 | 3억 | 약 $737.50 | 약 $794.50 |

현재 Flutter는 모든 방의 채팅 이벤트를 필터 없이 하나의 채널에서 구독한다. 목표 시점에도 1,000개 연결이 모든 메시지를 받는다면 비용뿐 아니라 초당 메시지 한도에도 부담이 된다.

따라서 Supabase를 유지하더라도 다음 중 하나는 반드시 필요하다.

- 사용자가 참여한 방만 구독하도록 Realtime 구독을 분리한다.
- 서버가 수신자를 계산하고 필요한 사용자에게만 전달하도록 Spring STOMP로 전환한다.
- 실제 평균 동시 연결과 메시지당 수신자 수로 부하·비용 테스트를 수행한다.

출처: [Supabase Realtime Pricing](https://supabase.com/docs/guides/realtime/pricing), [Realtime Limits](https://supabase.com/docs/guides/realtime/limits), [Realtime Messages Usage](https://supabase.com/docs/guides/platform/manage-your-usage/realtime-messages)

## 8. 비용 외 비교

| 판단 기준 | Supabase 유지 | AWS 관리형 인프라 |
|---|---|---|
| 초기 구축 속도 | 유리 | 불리 |
| 월 최소 비용 | 목표 사용량에서도 경쟁력 있음 | 단일 구성은 유사하지만 Multi-AZ는 더 높음 |
| 백엔드 제어권 | 제한적 | 높음 |
| 운영 난이도 | 낮음 | 높음 |
| 장애 원인 추적 | 플랫폼 경계의 제약 | 자체 관찰 도구 구성 가능 |
| Auth·Storage 통합 | 기본 제공 | 서비스별 설계 필요 |
| 공급자 종속성 | Supabase SDK·RLS·Realtime 결합 | AWS 서비스 결합 가능성 존재 |
| 이식성 | Supabase 전용 요소를 제거해야 함 | Spring과 표준 PostgreSQL 중심이면 비교적 높음 |
| 고가용성 선택 | 플랫폼 제공 범위 | Multi-AZ·Backup 정책 직접 선택 |
| 운영 인력 요구 | 낮음 | 상대적으로 높음 |

AWS로 이전해도 외부 플랫폼 의존성이 사라지는 것은 아니다. Supabase 단일 플랫폼 의존성을 AWS의 RDS, Cognito, S3와 실행 인프라 의존성으로 바꾸는 것이다. 목표는 외부 서비스를 전혀 사용하지 않는 것이 아니라, 서비스에 필요한 제어권과 운영 투명성을 확보하는 것이어야 한다.

## 9. 보증금 기능의 Supabase 구현 가능성

### 결론

**Supabase에서도 보증금 MVP를 구현할 수 있다.** Supabase PostgreSQL은 transaction, unique constraint, row lock과 Function을 제공하고 Edge Function은 결제대행사 API와 Webhook을 처리할 수 있다.

다만 Supabase가 실제 돈을 보관하는 것은 아니다. 결제 승인·취소·환급·지급은 토스페이먼츠 같은 결제대행사가 수행하고, Udaadaa는 결제 상태와 내부 원장을 안전하게 관리한다.

보증금이 단순 결제 후 전액 환급인지, 실패 금액을 서비스가 보유하거나 다른 사용자에게 지급하는 구조인지에 따라 PG 계약, 지급대행과 법률·회계 검토 범위가 달라진다. 이 부분은 기술 구현과 별도로 확인해야 한다.

### 반드시 필요한 안전장치

- Flutter가 결제·원장 테이블을 직접 insert·update하지 못하게 한다.
- 결제 승인 전에 서버가 DB에 저장한 주문 금액과 클라이언트가 보낸 금액을 비교한다.
- PG Secret Key는 Spring 또는 Edge Function Secret에만 둔다.
- `provider_event_id`, `payment_key`, `idempotency_key`에 unique constraint를 둔다.
- 결제·환급·몰수 상태는 명시적인 상태 전환 규칙으로만 변경한다.
- 금액 변경은 append-only 원장에 기록하고 기존 원장 행을 수정·삭제하지 않는다.
- Webhook 중복 수신과 순서 역전을 처리한다.
- DB transaction과 row lock으로 중복 환급·중복 몰수를 막는다.
- 실패 작업을 재시도하고 PG 거래 내역과 내부 원장을 주기적으로 대사한다.
- 관리자 변경, 수동 환급과 실패 복구에 감사 로그를 남긴다.

토스페이먼츠도 Secret Key의 서버 전용 사용, 서버의 결제 금액 재검증과 멱등키 사용을 요구한다.

출처: [토스페이먼츠 API 키](https://docs.tosspayments.com/reference/using-api/api-keys), [토스페이먼츠 서버 금액 검증](https://docs.tosspayments.com/guides/v2/get-started/llms-quick-reference), [토스페이먼츠 멱등키](https://docs.tosspayments.com/reference/using-api/authorization), [Supabase Edge Functions](https://supabase.com/docs/guides/functions)

### 구현 대안 비교

| 방식 | MVP 속도 | 금융 로직 안전성·테스트 | 장기 이식성 | 판단 |
|---|---|---|---|---|
| Flutter + RLS·RPC 중심 | 빠름 | 규칙이 Flutter·RLS·Function에 다시 분산될 위험 | 낮음 | 금융 기능에는 부적합 |
| Edge Function + Supabase PostgreSQL | 빠름 | 서버 경계를 만들 수 있지만 Deno·Function·DB 로직에 분산 | 중간 | 작은 검증용 MVP는 가능 |
| Spring + Supabase PostgreSQL | 중간 | Spring transaction·테스트·관찰과 PostgreSQL 원장을 통합 | 높음 | 현재 가장 균형적인 후보 |
| Spring + AWS RDS | 초기 느림 | 제어권과 운영 구성이 가장 명확 | 높음 | 인프라까지 즉시 교체할 근거 확인 필요 |

### Spring + Supabase PostgreSQL을 사용할 때의 이식성 규칙

Spring을 먼저 도입하고 Supabase DB를 임시 유지해도 다음 규칙을 지키면 나중의 RDS 이전이 애플리케이션 재작성으로 이어지는 것을 줄일 수 있다.

- 신규 보증금 테이블은 Flutter에 노출하지 않는 전용 Schema에 둔다.
- 신규 금융 Schema에서는 `auth.uid()`, Data API, Realtime과 Supabase 전용 Extension을 사용하지 않는다.
- 모든 금융 접근은 Spring API를 통한다.
- JPA·JDBC와 Flyway로 표준 PostgreSQL Schema를 관리한다.
- Supabase Auth ID는 외부 식별자로만 매핑하고 원장의 핵심 키와 분리한다.
- 결제대행사 연동은 Port·Adapter로 분리하여 공급자 교체와 테스트가 가능하게 한다.
- DB 이전 리허설에서 원장 합계, 결제 상태와 감사 로그를 전수 검증한다.

이 방식은 Supabase를 영구 유지한다는 뜻이 아니다. **MVP 속도와 현재 비용 이점을 사용하면서 새 비즈니스 로직이 Supabase에 다시 종속되지 않도록 경계를 만드는 방식**이다.

## 10. 결정

**대안 B: Spring을 먼저 도입하고 마지막 안정화 단계에서 Supabase를 제거한다.**

- Phase 0부터 Spring 코드는 표준 PostgreSQL, JPA·JDBC와 Flyway를 사용한다.
- Flutter의 DB 직접 접근, 분산된 비즈니스 로직과 Realtime을 기능별로 Spring REST·STOMP로 전환한다.
- 초기에는 기존 Auth·Database·Storage를 유지하여 사용자 세션, 데이터와 이미지 경로의 동시 변경을 피한다.
- Spring 기능이 운영 기준을 통과한 뒤 마지막 안정화 단계에서 Auth·PostgreSQL·Storage를 독립 인프라로 이전한다.
- 운영 구성은 2주 챌린지의 연속성을 위해 Spring 인스턴스 2대와 Load Balancer를 기준으로 하며, 현재 이미지 방식에서 약 $113/월로 추정한다.
- 배포는 Rolling 또는 Blue-Green으로 수행하고, 불가피한 데이터 전환은 새벽 1시에만 진행한다.

### 선택 이유

- 초기 마이그레이션의 핵심은 인프라 교체가 아니라 백엔드 제어권과 비즈니스 로직 분리다.
- B는 Spring 오류와 인프라 이전 오류를 분리하여 검증할 수 있다.
- B는 기능별 롤백이 가능하여 진행 중인 챌린지 전체가 멈출 위험이 가장 낮다.
- C는 장기 개발량이 조금 작을 수 있지만 이전 기간에 두 DB를 함께 운영해야 한다.
- D는 개발이 완료될 때까지 실제 사용자 검증이 늦고 최종 전환 위험이 전체 서비스에 집중된다.
- B의 Supabase 사용은 임시 단계이며 최종 제거 목표를 변경하지 않는다.

### 포기한 장점

- C의 최종 DB 구조에 바로 구현하는 장점
- D의 전환 후 임시 구조가 가장 적다는 장점
- B는 인프라 이전을 나중에 한 번 더 수행하므로 전체 개발 기간이 길어질 수 있음

## 11. 남은 검증과 완료 기준

현재 Supabase Pro 환경을 사용할 수 없으므로 가격과 성능은 실측 결과가 아니라 계획 가정이다. 지금 확정할 수 없는 검증을 완료된 것처럼 기록하지 않는다.

### 11.1 비용·사용량

- [x] 학습용 목표 사용량 확정: 누적 3,000명, MAU 2,500명, DAU 1,000명, Peak 1,000명
- [x] DB 초기 가정 확정: 2 vCPU, Memory 2GB, 일 300,000 Query, Peak 50 QPS
- [x] Storage 정상 상태 추정: 약 439GB
- [x] Egress 계산 모델 작성: Cache Hit Ratio 80% 기준 현재 약 648GB·$8.05/월, 최적화 후 약 259GB·$0/월
- [ ] 구현 후 이미지 평균 크기, 원본 열람률과 CDN Cache Hit Ratio 측정
- [ ] 운영 전 Supabase·AWS 예상 청구액을 가격 계산기로 다시 확인

### 11.2 STOMP 1,000 Connection 부하 테스트

Spring 채팅 서버가 구현되면 Supabase Pro와 무관하게 별도의 테스트 클라이언트로 다음 시험을 수행한다.

| 시험 | 시나리오 | 통과 기준 |
|---|---|---|
| 연결 | 10분 동안 1,000 Connection까지 점진 증가 | 연결 성공률 99.9% 이상 |
| 정상 메시지 | 1,000 Connection 유지 상태에서 방별 메시지 송수신 | 서버 오류율 0.1% 이하, 전달 p95 1초 이하 |
| 집중 부하 | 미션 제출 시간대를 가정해 5분간 초당 50개 메시지 | 저장 누락 0건, 중복 처리 0건 |
| 재연결 | 연결 30%를 동시에 끊고 Jitter를 적용해 재연결 | 60초 안에 99% 복구, 서버 장애 없음 |
| 인스턴스 교체 | 이중 인스턴스 중 한 대를 배포·종료 | 다른 인스턴스로 복구되고 저장 메시지 유실 0건 |
| DB 장애 | DB 연결을 일시 차단한 뒤 복구 | 실패가 명시적으로 반환되고 중복 저장 없이 재시도 가능 |
| 장시간 유지 | 1,000 Connection을 최소 2시간 유지 | 지속적인 Memory 증가 없음, CPU p95 70% 이하 |

테스트 도구는 STOMP WebSocket을 지원하는 k6 Extension 또는 Gatling을 POC에서 비교한다. 실제 구현 전에는 도구를 확정하거나 결과를 만들지 않는다.

### 11.3 운영 전환 리허설

- [ ] 새벽 1시 전환 절차와 담당 작업을 Runbook으로 작성
- [ ] 전환 직전 백업, 쓰기 차단, 데이터 검증과 롤백을 Staging에서 반복
- [ ] 진행 중인 챌린지·미션 제출·채팅 송수신 E2E 검증
- [ ] 이전 버전 Flutter와 새 Spring API의 호환성 검증
- [ ] 전환 실패 시 이전 쓰기 주체로 복구하는 데 걸리는 시간 측정

이 검증은 대안 B의 선택을 취소하기 위한 선행조건이 아니라, 각 기능을 운영으로 전환하기 위한 완료 조건이다.
