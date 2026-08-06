-- Phase 3 (Chat + Notification) 3-1: spring_app Role에 채팅 조회용 읽기 권한 추가
-- 3-1은 조회 API만 만들므로 SELECT만 부여한다. 쓰기 권한(3-2 이후 필요)은 별도 스크립트에서 추가한다.

grant select on table public.rooms to spring_app;
grant select on table public.room_participants to spring_app;
grant select on table public.messages to spring_app;
grant select on table public.chat_reactions to spring_app;
grant select on table public.read_receipts to spring_app;
grant select on table public.blocked_messages to spring_app;
grant select on table public.room_message_sequences to spring_app;

-- 롤백:
-- revoke select on table public.rooms, public.room_participants, public.messages,
--   public.chat_reactions, public.read_receipts, public.blocked_messages,
--   public.room_message_sequences from spring_app;
