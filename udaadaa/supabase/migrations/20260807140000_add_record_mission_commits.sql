create table if not exists public.record_mission_commits (
  client_request_id uuid primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  room_id uuid references public.rooms(id) on delete set null,
  feed_id uuid,
  weight_id uuid,
  created_at timestamptz not null default now()
);

comment on table public.record_mission_commits is
  'Phase 5 REC-04: 미션 커밋 요청의 멱등 원장. Spring이 clientRequestId로 재시도를 감지해
   feed/weight/report/messages를 중복 기록하지 않도록 한다. RLS 없음 — spring_app 전용 테이블.';
