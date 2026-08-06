# 2026-08-06 Phase 2 Moderation 구현 기록

## 1. 현재 상태

- Spring `moderation` 모듈 신규 구현 (domain/application/infrastructure/presentation 4계층, Member 모듈과 동일한 구조)
- `spring_app` DB Role에 `blocked_users` SELECT/INSERT/DELETE 권한 추가, 운영 DB에 직접 적용·확인 완료
- Flutter `chat_cubit.dart`의 `fetchBlockedUsers()`, `blockUser()`를 Spring Moderation API 호출로 전환
- **이 세션 샌드박스에 Java 21이 없어 Spring 코드는 컴파일·테스트를 로컬에서 아직 못 돌렸다.** 사용자 로컬 터미널에서 `./gradlew test` 확인 필요

## 2. 배경

Phase 1(Member) 완료 후 로드맵 순서대로 Phase 2(Moderation)를 진행했다. 기존 코드 조사 결과, 차단 관련 로직이 Flutter(`chat_cubit.dart`)와 Edge Function 2개(`post-initial-chat-data`, `message-push`)에 나뉘어 있고 방향(내가 차단 vs 상대가 나를 차단)도 기능마다 다르게 처리되고 있었다. 계획 문서(`phase-02-moderation.md`)에서 이 문제를 양방향 조회 API로 해결하기로 결정하고 구현했다.

## 3. 변경 사항

### DB

| 항목 | 내용 |
|---|---|
| `udaadaa_server/scripts/db-admin/phase-02-spring-app-blocked-users-grant.sql` | `spring_app`에 `blocked_users` SELECT/INSERT/DELETE 권한 부여. Supabase MCP로 운영 DB(`ccpcclfqofyvksajnrpg`)에 직접 적용하고 `information_schema.role_table_grants`로 확인 완료 |

### Spring 신규 파일 (`udaadaa_server/src/main/java/com/udaadaa/moderation/`)

| 파일 | 역할 |
|---|---|
| `ModerationReader.java` | 다른 모듈(Chat·Social)이 쓸 공개 조회 API. `canInteractWith(memberId, targetIds)` — 양방향 차단 여부 반환 |
| `domain/BlockRelation.java`, `domain/BlockRepository.java` | 도메인 모델과 Repository 인터페이스 |
| `application/ModerationApplicationService.java` | 차단 생성(자기 차단·존재하지 않는 회원 검증 포함)·해제·목록 조회·양방향 상호작용 조회 |
| `application/SelfBlockNotAllowedException.java`, `application/BlockedMemberNotFoundException.java` | 오류 타입 |
| `infrastructure/BlockedUserJpaEntity.java`, `BlockedUserId.java`, `SpringDataBlockRepository.java`, `JpaBlockRepository.java` | 기존 `blocked_users` 테이블(복합키 `user_id`+`block_user_id`) JPA 연결. 생성은 `on conflict do nothing`으로 멱등 처리 |
| `presentation/ModerationController.java`, `ModerationExceptionHandler.java`, `CreateBlockRequest.java`, `BlockedMembersResponse.java`, `InteractionStatusResponse.java` | REST API 4개 |

### API

| Method | Path | 비고 |
|---|---|---|
| `POST` | `/api/v1/moderation/blocks` | 차단 생성, 멱등, 204 |
| `DELETE` | `/api/v1/moderation/blocks/{blockedMemberId}` | 차단 해제, 멱등, 204. Flutter UI 미연결(API만 존재) |
| `GET` | `/api/v1/moderation/blocks` | 내가 차단한 목록 |
| `GET` | `/api/v1/moderation/interaction-status?targetIds=...` | 양방향 상호작용 가능 여부 일괄 조회. 아직 어떤 모듈도 호출하지 않음(Phase 3 이후 Chat·Social이 사용할 예정) |

### Flutter 신규·수정 파일

| 파일 | 변경 |
|---|---|
| `lib/data/moderation_api_client.dart` (신규) | Spring Moderation API 전용 Dio 클라이언트. `getBlockedMemberIds()`, `blockMember()` |
| `lib/cubit/chat_cubit.dart` | `fetchBlockedUsers()`: `supabase.from('blocked_users').select()` → `moderationApiClient.getBlockedMemberIds()`. `blockUser()`: `supabase.from('blocked_users').upsert()` → `moderationApiClient.blockMember()`. 로컬 상태 갱신(`blockedUsers.add()`, 메시지 필터링) 로직은 그대로 유지 |

### 건드리지 않은 것 (계획대로)

- `supabase/functions/post-initial-chat-data/index.ts`, `supabase/functions/message-push/index.ts` — 여전히 `blocked_users`를 직접 읽음. Phase 3(Chat)에서 정리
- `lib/view/chat/profile_view.dart`의 차단 버튼 — 원래도 미사용 죽은 코드라 손대지 않음
- 차단 해제(unblock) UI — API는 만들었지만 화면에 노출하지 않음

## 4. 검증한 것 / 안 한 것

검증함:
- Modulith 모듈 경계 규칙에 맞게 Moderation이 Member의 root package 공개 타입(`MemberId`, `MemberReader`)만 참조하도록 설계 (도메인·애플리케이션 내부 타입은 참조하지 않음)
- `spring_app` Role의 `blocked_users` 권한이 운영 DB에 실제로 부여됐는지 `information_schema.role_table_grants` 조회로 확인

검증 못 함 (다음 세션 확인 필요):
- Spring 코드 컴파일·`./gradlew test` 실행 (이 세션 샌드박스에 Java 21이 없어서 못 돌림)
- 실기기에서 실제 차단 동작 (Phase 1과 동일하게 전체 Phase 종료 후 일괄 테스트로 미룸)
- `flutter analyze`

## 5. 다음 작업

1. 사용자 로컬 터미널에서 `cd udaadaa_server && ./gradlew test --tests "com.udaadaa.moderation.*"` 실행해 신규 테스트(`ModerationIntegrationTests`) 통과 확인
2. 전체 `./gradlew test`로 기존 테스트(Member 등) 회귀 확인
3. 안정성 확인되면 Phase 2 2-D~2-F를 완료로 전환하고 `phase-02-moderation.md`, README 갱신
4. Phase 3(Chat + Notification) 계획 착수
