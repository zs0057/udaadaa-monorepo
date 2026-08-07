-- Phase 4 (Challenge): 챌린지 참여 쓰기 + 진행 계산에 필요한 권한
-- CHA-04: feed/weight는 Record(Phase 5) 소관이지만, Record가 아직 Spring으로 넘어가지 않아
-- Challenge가 미션 진행·연속 성공 계산을 위해 임시로 읽기 전용 조회한다.
grant select, insert, update on table public.challenge to spring_app;
grant select on table public.feed to spring_app;
grant select on table public.weight to spring_app;
