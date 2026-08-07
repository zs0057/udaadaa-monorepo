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
  - 상세 문서: [TO-BE Architecture](docs/migration/04-to-be-architecture.md), [ADR-001 초기 기술 결정](docs/migration/adr/ADR-001-initial-technical-decisions.md), [ADR-002 인프라 기술 검토](docs/migration/adr/ADR-002-infrastructure-technical-review.md), [ADR-003 Supabase·Spring 호환성 검토](docs/migration/adr/ADR-003-supabase-spring-compatibility.md)
- [x] 도메인별 마이그레이션 로드맵 작성
  - 공통 기반부터 Member, Moderation, Chat·Notification, Challenge, Record, Social과 의존성 제거까지의 순서와 완료·롤백 기준을 정의했습니다.
  - 상세 문서: [Migration Roadmap](docs/migration/05-migration-roadmap.md)
- [x] Phase별 Before → After 변경 매핑 작성 (진행하면서 갱신)
  - 기존 Flutter/Supabase 코드가 어떤 Spring API·Flutter 코드로 바뀌었는지 기능 단위로 정리했습니다.
  - 상세 문서: [Migration Changelog](docs/migration/06-migration-changelog.md)
- [x] 구현 주의사항·추가 검토 필요 항목 추적 (진행하면서 갱신)
  - 완전히 해결되지 않았거나 후속 Phase에서 다시 봐야 하는 항목을 위험도와 함께 정리했습니다.
  - 상세 문서: [Implementation Watchlist](docs/migration/07-implementation-watchlist.md)
- [ ] 기능 단위 Spring 이전 및 검증
  - [x] **Phase 0 — Spring 공통 기반** · 완료
    - Spring 실행·인증·DB·모듈·오류·관찰·테스트 기반을 준비합니다.
    - 현재 상태: 로컬 공통 기반, 실제 Supabase JWT 검증, spring_app DB Role 연동 검증, 전체 자동 테스트 재실행까지 모두 완료. 검증 중 노출된 Secret 2건은 낮은 위험도로 판단해 로테이션을 별도 추적 항목으로 보류
    - 상세 문서: [Phase 0 Foundation](docs/migration/phases/phase-00-foundation.md), [Phase 0 Verification](docs/migration/phases/phase-00-verification.md)
  - [ ] **Phase 1 — Member** · 진행 중
    - Supabase JWT 사용자와 서비스 회원을 연결하고 프로필 조회·수정을 이전합니다.
    - 현재 상태: Spring API 검증 완료, 실제 Supabase 비교 완료, Flutter의 프로필 조회·닉네임 수정이 Spring API로 전환됨(코드 완료). 실기기 회귀 테스트·이메일/Apple 로그인 경로 확인은 전체 Phase 종료 후 일괄 진행 예정
    - 상세 문서: [Phase 1 Member](docs/migration/phases/phase-01-member.md), [2026-07-29 진행 기록](docs/migration/progress/2026-07-29-phase-01-progress.md), [2026-08-06 Flutter 전환 기록](docs/migration/progress/2026-08-06-phase-01-flutter-transition.md)
  - [ ] **Phase 2 — Moderation** · 구현 중
    - 사용자 차단과 공통 상호작용 허용 규칙을 Spring으로 이전합니다.
    - 현재 상태: Spring Moderation 모듈(차단 생성·해제·조회·양방향 상호작용 확인 API)과 Flutter 전환 완료, 로컬 `./gradlew test` 통과 확인. 실기기 테스트는 전체 Phase 종료 후 일괄 진행 예정
    - 상세 문서: [Phase 2 Moderation](docs/migration/phases/phase-02-moderation.md), [2026-08-06 구현 기록](docs/migration/progress/2026-08-06-phase-02-moderation-implementation.md)
  - [ ] **Phase 3 — Chat + Notification** · 구현 중 (Flutter 전환 D)
    - 채팅 저장·STOMP 전달·누락 복구·읽음·Push를 이전합니다.
    - 현재 상태: 서버 3-1~3-4(조회·저장+STOMP·참가/반응/삭제/숨김/이미지업로드·Notification) 코드 완료. Flutter 전환 A~D(읽기, 쓰기+STOMP, 참가/반응/삭제/숨김/이미지업로드, 읽음 위치+안읽음 배지) 완료 — A~C는 병합 완료, D는 로컬 빌드 확인 대기. D 작업 중 읽음 위치 실시간 브로드캐스트(`ReadPositionUpdated` + STOMP)를 계획에 없던 추가 범위로 구현했다. 기존 `message-push` DB 트리거는 Spring Push 검증 전까지 병행 유지 중. 조사 중 발견한 `service_role` 키 유출은 코드 수정 완료·로테이션 보류(Phase 0 Verification §7 참고)
    - 상세 문서: [Phase 3 Chat + Notification](docs/migration/phases/phase-03-chat-notification.md), [2026-08-06 3-1 구현 기록](docs/migration/progress/2026-08-06-phase-03-3-1-implementation.md)
  - [ ] **Phase 4 — Challenge** · 구현 중
    - 챌린지 참여·기간·미션 진행과 성공 판정을 이전합니다. 방 참가+챌린지 참여를 하나의 서버 트랜잭션으로 묶었습니다.
    - 상세 문서: [Phase 4 Challenge](docs/migration/phases/phase-04-challenge.md)
  - [ ] **Phase 5 — Record + 미션 통합** · 구현 중
    - 식단·운동·체중 기록과 `mission_complete` 흐름을 이전합니다. 미션 인증(Record 기록+리포트+Chat 메시지)을 하나의 서버 트랜잭션으로 묶었습니다.
    - 상세 문서: [Phase 5 Record + 미션 통합](docs/migration/phases/phase-05-record-mission-integration.md)
  - [ ] **Phase 6 — Social** · 예정
    - 공개 피드·반응·피드 숨김을 이전합니다.
  - [ ] **Phase 7 — 회원 탈퇴** · 예정
    - 상태 전환, 데이터 정리와 외부 Auth 삭제·재시도를 구현합니다.
- [ ] 운영 안정성 확인 및 Supabase 직접 의존성 제거
  - [ ] **Phase 8 — 운영 안정화·의존성 제거** · 예정
    - 운영 지표를 확인한 뒤 Flutter의 Supabase 직접 호출, Realtime, Trigger와 Edge Function을 순차적으로 제거합니다.
