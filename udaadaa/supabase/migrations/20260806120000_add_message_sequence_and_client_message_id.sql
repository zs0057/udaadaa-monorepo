-- Phase 3 (Chat + Notification) 3-1: 채팅 조회와 복구 준비
-- CHT-02: messages에 방별 단조 증가 sequence 컬럼 추가 (Expand — 기존 created_at 정렬은 그대로 유지)
-- CHT-03: 클라이언트 중복 전송 방지를 위한 client_message_id 컬럼 추가 (3-2에서 Flutter가 채워 보냄)

-- 방별 마지막 sequence를 추적하는 카운터 테이블.
-- ON CONFLICT DO UPDATE ... RETURNING 패턴으로 원자적으로 증가시켜 동시 insert에도 안전하다.
create table if not exists public.room_message_sequences (
    room_id uuid primary key references public.rooms(id) on delete cascade,
    last_sequence bigint not null default 0
);

alter table public.messages add column if not exists sequence bigint;
alter table public.messages add column if not exists client_message_id uuid;

-- 기존 데이터 백필: 방별로 created_at 순서대로 1부터 채번한다.
with ordered as (
    select
        id,
        room_id,
        row_number() over (partition by room_id order by created_at, id) as rn
    from public.messages
)
update public.messages m
set sequence = ordered.rn
from ordered
where m.id = ordered.id
  and m.sequence is null;

-- 카운터 테이블을 백필 결과와 맞춘다.
insert into public.room_message_sequences (room_id, last_sequence)
select room_id, max(sequence)
from public.messages
where sequence is not null
group by room_id
on conflict (room_id) do update set last_sequence = excluded.last_sequence;

-- 신규 insert 시 sequence를 자동 채번하는 트리거.
-- Flutter가 여전히 messages에 직접 insert하는 3-2 전환 전 구간에도 sequence가 비지 않도록
-- 지금 바로 활성화한다 (읽기 API가 3-1부터 sequence를 기준으로 정렬하기 위함).
create or replace function public.assign_message_sequence()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    next_seq bigint;
begin
    if new.sequence is not null then
        return new;
    end if;

    insert into public.room_message_sequences (room_id, last_sequence)
    values (new.room_id, 1)
    on conflict (room_id) do update
        set last_sequence = public.room_message_sequences.last_sequence + 1
    returning last_sequence into next_seq;

    new.sequence := next_seq;
    return new;
end;
$$;

drop trigger if exists messages_assign_sequence on public.messages;
create trigger messages_assign_sequence
    before insert on public.messages
    for each row
    execute function public.assign_message_sequence();

-- client_message_id 중복 방지: 같은 방에서 같은 client_message_id는 한 번만 허용.
-- NULL은 유니크 제약에서 서로 다른 값으로 취급되므로 client_message_id를 아직 안 보내는
-- 기존 Flutter(3-2 전환 전)의 insert는 영향받지 않는다.
create unique index if not exists messages_room_client_message_id_key
    on public.messages (room_id, client_message_id)
    where client_message_id is not null;

comment on column public.messages.sequence is 'Phase 3 CHT-02: 방별 단조 증가 순번. 정렬 기준을 created_at에서 이 컬럼으로 전환 예정.';
comment on column public.messages.client_message_id is 'Phase 3 CHT-03: 클라이언트 중복 전송 방지용 ID. 3-2부터 Flutter가 채워 보냄.';
