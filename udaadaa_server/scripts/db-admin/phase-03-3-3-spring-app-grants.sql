-- Phase 3 3-3: 방 참가/나가기, 읽음 위치, 반응, 메시지 삭제/숨김에 필요한 쓰기 권한
grant insert, delete on table public.room_participants to spring_app;
grant update (last_read_sequence) on table public.room_participants to spring_app;
grant insert, delete on table public.chat_reactions to spring_app;
grant update (is_deleted) on table public.messages to spring_app;
grant insert on table public.blocked_messages to spring_app;
