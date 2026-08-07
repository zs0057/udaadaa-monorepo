-- Phase 5 (Record + 미션 통합): spring_app이 feed/weight/report의 쓰기 소유자가 된다.
-- feed/weight의 SELECT는 Phase 4(CHA-04)에서 이미 부여됨 — 여기서는 쓰기 권한만 추가한다.
grant insert, delete on table public.feed to spring_app;
grant insert on table public.weight to spring_app;
grant insert, update on table public.report to spring_app;
grant select, insert on table public.record_mission_commits to spring_app;
