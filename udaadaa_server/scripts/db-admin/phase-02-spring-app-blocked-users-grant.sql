-- Phase 2 (Moderation): spring_app Role에 blocked_users 권한 추가
-- 기존 phase-00-spring-app-role-create.sql로 만든 spring_app Role은 profiles만 권한이 있었다.
-- Moderation 모듈이 차단 생성/해제/조회를 처리하려면 blocked_users에 대한
-- SELECT, INSERT, DELETE가 필요하다 (UPDATE는 불필요 — 차단은 생성/삭제만 존재).

grant select, insert, delete on table public.blocked_users to spring_app;

-- 롤백:
-- revoke select, insert, delete on table public.blocked_users from spring_app;
