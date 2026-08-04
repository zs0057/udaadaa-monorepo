-- Phase 0: spring_app DB Role 롤백
--
-- create.sql 적용 후 문제가 발생하면 이 스크립트로 되돌린다.
-- 활성 연결이 있으면 DROP ROLE이 실패할 수 있으니, 먼저 연결을 종료한다.

begin;

revoke all privileges on table public.profiles from spring_app;
revoke usage on schema public from spring_app;

commit;

-- spring_app으로 연결 중인 세션이 있으면 아래로 강제 종료 후 재시도한다.
-- select pg_terminate_backend(pid) from pg_stat_activity where usename = 'spring_app';

drop role if exists spring_app;

-- 롤백 확인:
--   select rolname from pg_roles where rolname = 'spring_app';  -- 결과 없어야 정상
