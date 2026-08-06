# 2026-08-06 Phase 3 3-3 구현 기록: 참가/나가기, 읽음, 반응, 삭제/숨김, 이미지 업로드 승인

> 관련 계획: [phase-03-chat-notification.md](../phases/phase-03-chat-notification.md) §3 (3-3 범위), CHT-06
> 선행 작업: [3-2 구현 기록](2026-08-06-phase-03-3-2-implementation.md)
> 이 작업부터는 `main`이 아니라 `feature/phase-3-3-chat` 브랜치에서 진행했다.

## 조사 결과 (구현 전 확인한 현재 Flutter 동작)

새 API를 설계하기 전에 Flutter 코드(`chat_cubit.dart`)를 다시 읽고 아래를 확인했다.

- **방 나가기 기능이 원래 없었다.** `leaveRoom()`은 `currentRoomId = null`만 할 뿐 `room_participants`를 건드리지 않는다. 이번에 만든 나가기 API는 기존 동작의 이식이 아니라 새 기능이다.
- **반응 삭제(un-react) 기능도 원래 없었다** — `chat_reactions`는 PK가 서로게이트 `id`뿐이라 같은 사용자가 같은 이모지를 여러 번 남길 수 있고, 지우는 UI 자체가 없다.
- **메시지 삭제는 소프트 삭제**(`is_deleted = true`)이고 UI에서 "내 메시지"일 때만 버튼이 보인다. 서버 쪽 소유자 검증은 RLS에 맡겨져 있었다(Spring은 `BYPASSRLS`라 직접 확인해야 한다).
- **메시지 숨김(`blocked_messages`)과 사용자 차단(Moderation)은 서로 다른 기능이다.** 전자는 메시지 하나씩 개인 화면에서 숨기는 것(멱등 upsert), 후자는 Phase 2에서 이미 옮긴 `POST /moderation/blocks`이고 `blocked_messages`를 전혀 건드리지 않는다.
- **이미지 업로드 정리 로직은 원래 없다.** 업로드 실패/부분 성공 시 스토리지에 파일이 남는 것을 막는 코드가 전혀 없었다(그대로 둠, 새로운 갭 아님).

## 무엇을 만들었나

DB Expand: `room_participants.last_read_sequence bigint default 0` 추가. `spring_app`에 참가/나가기(`room_participants` INSERT/DELETE/UPDATE), 반응(`chat_reactions` INSERT/DELETE), 메시지 소프트 삭제(`messages.is_deleted` UPDATE), 숨김(`blocked_messages` INSERT) 권한을 추가했다.

새 API 7개, 전부 `ChatController`에 추가:

| API | 동작 |
|---|---|
| `POST /rooms/{roomId}/participants` | 방 참가. 이미 참가 중이면 `409 ALREADY_JOINED`, 방이 없으면 `404 ROOM_NOT_FOUND` |
| `DELETE /rooms/{roomId}/participants/me` | 방 나가기. 멱등 — 원래 참가자가 아니어도 `204` |
| `PATCH /rooms/{roomId}/read-position` | `lastReadSequence` 갱신. 기존 값보다 작으면 무시(뒤로 안 감) |
| `POST /rooms/{roomId}/messages/{messageId}/reactions` | 반응 추가. 중복 허용(운영 DB와 동일) |
| `DELETE /rooms/{roomId}/reactions/{reactionId}` | 본인 반응만 삭제. 없거나 남의 것이면 조용히 `204` |
| `DELETE /rooms/{roomId}/messages/{messageId}` | 본인 메시지만 소프트 삭제. 아니면 `404 MESSAGE_NOT_FOUND` |
| `POST /rooms/{roomId}/messages/{messageId}/hide` | 내 화면에서만 숨김. 멱등 |
| `POST /rooms/{roomId}/image-uploads` | 업로드 가능한 경로 발급(아래 참고) |

## 핵심 코드

**참가는 원자적 insert-or-noop, 방 존재 여부는 별도 확인** (`ChatApplicationService.joinRoom`):

```java
if (!chatRepository.roomExists(roomId)) {
    throw new RoomNotFoundException();
}
boolean joined = chatRepository.addParticipantIfAbsent(roomId, memberId);
if (!joined) {
    throw new AlreadyParticipantException();
}
```

`addParticipantIfAbsent`는 `insert ... on conflict (user_id, room_id) do nothing`으로 구현해, "이미 참가 중" 여부를 별도 조회 없이 한 번의 쓰기로 판정한다(경쟁 상태에도 안전).

**읽음 위치는 DB에서 원자적으로 GREATEST 처리** — "더 작은 값으로 갱신 시도"가 애초에 반영될 수 없다:

```sql
update public.room_participants
set last_read_sequence = greatest(last_read_sequence, :lastReadSequence)
where room_id = :roomId and user_id = :userId
```

**메시지 삭제·숨김·반응은 항상 "이 방에 이 메시지가 실제로 있는가"부터 확인**한다 (`ChatApplicationService.requireMessageInRoom`) — `messageId`만 보고 `roomId`를 안 보면 다른 방의 메시지 ID를 넣어 정보를 캐낼 수 있어서다. 메시지 삭제는 한 걸음 더 나가 발신자 일치까지 SQL 조건에 넣어 원자적으로 처리한다:

```sql
update public.messages
set is_deleted = true
where id = :messageId and room_id = :roomId and user_id = :senderId and coalesce(is_deleted, false) = false
```

영향받은 행이 0이면 "없음/다른 방/내 메시지 아님/이미 삭제됨"을 전부 같은 `404 MESSAGE_NOT_FOUND`로 응답한다 — `RoomNotFoundException`과 같은 정보 비노출 원칙.

**이미지 업로드는 "경로만 발급"으로 결정** (CHT-06의 두 옵션 중 선택, 사용자 확인 완료):

```java
@Transactional(readOnly = true)
public String approveImageUpload(MemberId memberId, RoomId roomId) {
    requireParticipant(roomId, memberId);
    return "%s/%s.jpg".formatted(roomId.value(), UUID.randomUUID());
}
```

Spring은 참가자인지만 확인하고 방 스코프 경로를 내려준다. 실제 업로드는 지금처럼 Flutter가 자기 Supabase 세션으로 Storage에 직접 하고, 기존 Storage RLS(`is_room_participant(folder[1])`)가 여전히 실질적 방어선이다. 업로드가 끝나면 이 경로를 `imagePath`로 하는 기존 `POST /rooms/{roomId}/messages` 호출로 메시지를 만든다(3-2에서 이미 구현됨).

## 꼭 알아야 할 것

- **Flutter는 아직 이 API들을 하나도 호출하지 않는다.** 3-1·3-2와 마찬가지로 백엔드만 먼저 만들었다.
- **방 나가기·반응 삭제는 기존 앱에 없던 새 기능이다.** UI에 노출할지, 어떤 화면에 붙일지는 별도 제품 결정이 필요하다 — 이 세션에서는 API만 준비했다.
- **"경로만 발급" 방식을 선택했다.** 서명된 업로드 URL을 Spring이 직접 발급하는 대안도 있었지만, 그러려면 Spring이 Storage API를 호출하기 위한 service_role(또는 별도 관리형 키)을 새로 다뤄야 해서, 지금 진행 중인 service_role 키 유출 정리 방향과 반대로 가는 셈이었다. 그래서 기존 Storage RLS를 그대로 신뢰하는 더 가벼운 방식을 선택했다.
- **미완료 이미지 업로드 정리는 이번에도 안 만들었다.** 승인만 되고 메시지로 이어지지 않은 업로드가 스토리지에 남는 문제는 지금 앱에도 이미 있는 갭이라 새로운 회귀는 아니지만, 여전히 해결되지 않은 채로 남아 있다.
- 이번에도 나는 Java 21을 실행할 수 없어 로컬 `./gradlew test` 확인이 필요하다. 이번엔 `feature/phase-3-3-chat` 브랜치에서 작업했으니, 커밋·푸시도 이 브랜치로 해야 한다.
- 테스트 스키마(`ChatIntegrationTests`)에 `chat_reactions`, `blocked_messages` 테이블과 `room_participants.last_read_sequence` 컬럼을 추가했다 — 3-2 때 겪었던 "테스트 클래스 간 공유 테이블 스키마 불일치" 문제가 재발하지 않도록, 새로 추가한 컬럼·테이블이 다른 모듈 테스트와 이름이 겹치지 않는지 다시 확인했다(겹치지 않음).
