# Udaadaa Monorepo

Udaadaa는 채팅과 챌린지를 통해 사용자가 건강 목표를 꾸준히 이어가도록 돕는 서비스입니다.

이 저장소는 기존 Flutter·Supabase 애플리케이션과 새 Spring 백엔드를 하나의 저장소에서 함께 관리하며, 서비스 중단과 데이터 유실 없이 백엔드를 점진적으로 마이그레이션하기 위한 모노레포입니다.

## 진행 현황

- [x] 마이그레이션 필요성과 기본 방향 정의
  - **기존 문제**
    1. Supabase 관리형 백엔드에 의존하여 실시간 연결, 재연결, 장애 복구, 로그와 모니터링을 서비스 요구사항에 맞게 세밀하게 제어하기 어렵습니다.
    2. UI와 비즈니스 로직이 Flutter, Edge Function, RLS와 Database Function에 분산되어 테스트, 트랜잭션 관리와 기능 확장이 어렵습니다.
  - **마이그레이션 목적**
    1. Spring에서 인증, 권한, 채팅 연결·복구와 모니터링을 통합 관리하여 백엔드 제어권을 확보합니다.
    2. Flutter는 화면과 사용자 입력에 집중하고 Spring은 비즈니스 규칙과 트랜잭션을 담당하도록 책임을 분리합니다.
  - 상세 문서: [Migration Overview](docs/migration/00-overview.md)
- [x] Flutter·Spring 모노레포 구성 및 기존 Flutter 이력 보존
  - 기존 Flutter `main` 이력을 보존하고 Flutter와 Spring을 하나의 저장소에서 관리합니다.
- [x] 현재 기능과 Supabase 의존성 목록 작성
  - Flutter 기능과 Supabase 자원을 연결하고 실제 배포 메타데이터와 교차 검증했습니다.
  - 상세 문서: [System Inventory](docs/migration/01-system-inventory.md)
- [x] AS-IS 데이터 흐름 및 시스템 구조 작성
  - 현재 구성 요소의 책임과 인증·채팅·미션·알림 등 핵심 기능의 처리 순서 및 실패 경계를 정리했습니다.
  - 상세 문서: [AS-IS Architecture](docs/migration/02-as-is-architecture.md)
- [x] 도메인과 모듈 경계 확정
  - Member, Chat, Challenge, Record, Social, Moderation과 Notification의 책임과 데이터 소유권을 정의했습니다.
  - 상세 문서: [Domain Boundaries](docs/migration/03-domain-boundaries.md)
- [x] 목표 아키텍처와 주요 기술 결정 확정
  - Spring 모듈형 모놀리스, REST·STOMP, 내부 이벤트와 점진적 Schema 전환 원칙을 확정했습니다.
  - 상세 문서: [TO-BE Architecture](docs/migration/04-to-be-architecture.md), [ADR-001](docs/migration/adr/ADR-001-initial-technical-decisions.md)
- [x] 도메인별 마이그레이션 로드맵 작성
  - 공통 기반부터 Member, Moderation, Chat·Notification, Challenge, Record, Social과 의존성 제거까지의 순서와 완료·롤백 기준을 정의했습니다.
  - 상세 문서: [Migration Roadmap](docs/migration/05-migration-roadmap.md)
- [ ] 기능 단위 Spring 이전 및 검증
- [ ] 운영 안정성 확인 및 Supabase 직접 의존성 제거
