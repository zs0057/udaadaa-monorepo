-- Phase 4 (Challenge) CHA-03: 방 기반 참여의 멱등성을 위해 challenge에 room_id를 추가한다 (Expand).
-- 기존 행은 어느 방에서 시작됐는지 100% 확정할 방법이 없어 NULL로 남긴다(백필하지 않음).
alter table public.challenge add column if not exists room_id uuid references public.rooms(id) on delete set null;

-- 같은 사용자가 같은 방에서 두 번 참여하지 못하도록 한다(room_id가 있는 경우만 — 일반 참여는 room_id가 NULL이라 이 제약의 영향을 받지 않는다).
create unique index if not exists challenge_user_id_room_id_key
    on public.challenge (user_id, room_id)
    where room_id is not null;

comment on column public.challenge.room_id is 'Phase 4 CHA-03: 챌린지 방 참가로 시작된 참여의 방 ID (Expand, 기존 행은 NULL).';
