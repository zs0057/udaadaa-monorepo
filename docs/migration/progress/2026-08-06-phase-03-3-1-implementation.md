# 2026-08-06 Phase 3 (3-1: 채팅 조회와 복구) 구현 기록

## 1. 현재 상태

- DB Expand 완료: `messages`에 `sequence`(방별 순번)·`client_message_id` 컬럼 추가, 기존 4,053건 백필, 신규 insert 자동 채번 트리거 적용 — 운영 DB에 직접 반영.
- `spring_app` Role에 채팅 관련 테이블 6개 SELECT 권한 추가 — 운영 DB에 직접 반영.
- Spring `chat` 모듈(조회 전용) 신규 구현: 방 목록, 순번 기준 메시지 조회 API 2개.
- **Flutter는 아직 이 API를 호출하지 않는다.** 기존 Edge Function(`post-initial-chat-data`) 조회 경로가 그대로 살아있다. 이번 작업은 서버 쪽만 준비한 것이고, Flutter 전환은 3-1 검증 이후 별도로 진행한다.
- 이 세션 샌드박스에 Java 21이 없어 `./gradlew test`를 직접 못 돌렸다. 로컬 확인 필요.

## 2. DB 변경 핵심 내용

### `messages.sequence` — 방별 단조 증가 순번

```sql
-- 방별 마지막 순번을 추적하는 카운터 테이블
create table room_message_sequences (
    room_id uuid primary key references rooms(id) on delete cascade,
    last_sequence bigint not null default 0
);

-- insert 시 원자적으로 다음 번호를 채번하는 트리거 함수
create function assign_message_sequence() returns trigger as $$
declare next_seq bigint;
begin
  if new.sequence is not null then return new; end if;
  insert into room_message_sequences (room_id, last_sequence)
  values (new.room_id, 1)
  on conflict (room_id) do update
    set last_sequence = room_message_sequences.last_sequence + 1
  returning last_sequence into next_seq;
  new.sequence := next_seq;
  return new;
end;
$$ language plpgsql security definer;

create trigger messages_assign_sequence
  before insert on messages
  for each row execute function assign_message_sequence();
```

**꼭 알아야 할 점**: 이 트리거는 **Flutter가 지금처럼 `messages`에 직접 insert하는 동안에도** 계속 sequence를 채운다. 즉 3-2에서 쓰기 주체를 Spring으로 옮기기 전까지도 sequence는 정상적으로 쌓인다 — 나중에 백필이 또 필요하지 않다는 뜻. `ON CONFLICT DO UPDATE ... RETURNING`은 Postgres에서 원자적으로 동작하므로 같은 방에 동시에 메시지가 여러 개 들어와도 순번이 겹치지 않는다.

기존 4,053개 메시지는 방별로 `created_at` 순서대로 1부터 채번했고, 검증 쿼리로 "방마다 순번이 1부터 count(*)까지 빈틈·중복 없이 이어지는지" 확인 완료.

### `messages.client_message_id`

지금은 컬럼만 추가된 상태(전부 NULL). 3-2에서 Flutter가 메시지 전송 시 UUID를 생성해 이 필드에 채워 보내면, `(room_id, client_message_id)` 부분 유니크 인덱스가 중복 전송을 막아준다. **지금 당장은 아무 동작도 하지 않는다** — 3-2 전까지는 그냥 빈 컬럼.

## 3. Spring 코드 핵심

### 새 모듈: `com.udaadaa.chat`

```
chat/
├─ RoomId.java                              방 ID 값 객체
├─ domain/
│   ├─ RoomSummary.java, MessageSummary.java   조회 응답용 도메인 모델
│   └─ ChatRepository.java                     조회 인터페이스
├─ application/
│   ├─ ChatApplicationService.java             getRooms(), getMessages()
│   └─ RoomNotFoundException.java
├─ infrastructure/
│   ├─ RoomJpaEntity, RoomParticipantJpaEntity, MessageJpaEntity
│   ├─ SpringData*Repository (3개)
│   └─ JpaChatRepository                       도메인 인터페이스 구현체
└─ presentation/
    ├─ ChatController.java                     GET /api/v1/chat/rooms, GET .../messages
    └─ ChatExceptionHandler.java
```

### 권한 체크가 핵심 — 꼭 알아야 할 부분

`spring_app` DB Role은 Phase 0에서 `BYPASSRLS`로 만들었다. 즉 **Postgres의 RLS가 Spring 쪽 요청은 전혀 막아주지 않는다.** 그래서 `ChatApplicationService.getMessages()`는 매번 명시적으로 이 체크를 한다:

```java
public List<MessageSummary> getMessages(MemberId memberId, RoomId roomId, long afterSequence, int limit) {
    if (!chatRepository.isParticipant(roomId, memberId)) {
        throw new RoomNotFoundException();
    }
    ...
}
```

방이 아예 없는 경우와 "방은 있지만 내가 참가자가 아닌" 경우를 **똑같이 404 `ROOM_NOT_FOUND`**로 응답한다. 둘을 구분해서 응답하면 존재하지 않는 방 ID인지 아닌지를 비참가자가 추측할 수 있게 되기 때문이다(방 존재 여부 자체가 정보 노출).

### API

| Method | Path | 응답 |
|---|---|---|
| `GET` | `/api/v1/chat/rooms` | 내가 참가한 방 목록 + 방별 마지막 메시지 |
| `GET` | `/api/v1/chat/rooms/{roomId}/messages?after=0&limit=30` | `sequence > after`인 메시지를 오름차순 최대 `limit`(기본 30, 최대 50)개 |

## 4. 검증한 것 / 안 한 것

검증함:
- 백필 정합성(운영 DB SQL로 직접 확인: 방마다 순번 1..count(*) 빈틈·중복 없음)
- `spring_app` 권한 부여 결과(`information_schema.role_table_grants` 조회)

검증 못 함 (다음 세션 확인 필요):
- `ChatIntegrationTests` 로컬 `./gradlew test` 통과 여부 (Java 21 없어서 이 세션에서 미실행)
- Flutter 연동 (아직 전환 안 함)

## 5. 다음 작업

1. `cd udaadaa_server && ./gradlew test --tests "com.udaadaa.chat.*"` 로컬 확인
2. 통과하면 3-2(메시지 저장 API + STOMP) 착수 여부 결정
3. Flutter를 새 조회 API로 전환하는 시점은 3-1 검증 이후 별도 판단 (지금은 서버만 준비된 상태)
