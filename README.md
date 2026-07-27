# Udaadaa Monorepo

Udaadaa는 채팅과 챌린지를 통해 사용자가 건강 목표를 꾸준히 이어가도록 돕는 서비스입니다.

이 저장소는 기존 Flutter·Supabase 애플리케이션과 새 Spring 백엔드를 하나의 저장소에서 함께 관리하며, 서비스 중단과 데이터 유실 없이 백엔드를 점진적으로 마이그레이션하기 위한 모노레포입니다.

## 진행 현황

- [x] 마이그레이션 필요성과 기본 방향 정의
  - 백엔드 제어권을 확보하고 비즈니스 로직을 Spring으로 점진적으로 이전합니다.
  - 상세 문서: [Migration Overview](docs/migration/00-overview.md)
- [x] Flutter·Spring 모노레포 구성 및 기존 Flutter 이력 보존
  - 기존 Flutter `main` 이력을 보존하고 Flutter와 Spring을 하나의 저장소에서 관리합니다.
- [ ] 현재 기능과 Supabase 의존성 목록 작성
- [ ] AS-IS 데이터 흐름 및 시스템 구조 작성
- [ ] 도메인과 모듈 경계 확정
- [ ] 목표 아키텍처와 주요 기술 결정 확정
- [ ] 도메인별 마이그레이션 로드맵 작성
- [ ] 기능 단위 Spring 이전 및 검증
- [ ] 운영 안정성 확인 및 Supabase 직접 의존성 제거
