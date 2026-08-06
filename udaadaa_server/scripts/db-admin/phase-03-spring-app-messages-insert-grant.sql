-- Phase 3 (Chat + Notification) 3-2: spring_app Role에 messages 쓰기 권한 추가
-- 3-1은 SELECT만 부여했다. 3-2에서 메시지 저장 API를 만들면서 INSERT를 추가한다.
-- UPDATE(삭제 표시 is_deleted)·다른 테이블 쓰기는 3-3에서 추가한다.

grant insert on table public.messages to spring_app;

-- 롤백:
-- revoke insert on table public.messages from spring_app;
