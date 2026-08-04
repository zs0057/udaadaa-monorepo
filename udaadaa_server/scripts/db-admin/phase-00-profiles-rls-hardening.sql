-- Phase 0: profiles RLS 보완 (선택 사항, 별도 검토 후 적용)
--
-- 이 파일은 spring_app 생성과 무관하게 기존 Flutter/authenticated 경로의
-- 보안 허점을 보완하는 변경이다. Flutter가 여전히 profiles를 직접 쓰는 동안
-- 적용 대상이므로, spring_app 도입과 별개로 승인 후 적용 여부를 결정한다.
--
-- 발견된 문제 (phase-01-member.md, 01-system-inventory.md 근거):
--   1) UPDATE 정책에 WITH CHECK가 없어, auth.uid() = id인 행을 찾은 뒤
--      id 컬럼 자체를 다른 사용자 id로 바꿔치기하는 것을 막지 못한다.
--   2) INSERT/UPDATE/DELETE 정책 대상이 "public"으로 되어 있어 anon Role도
--      이론상 정책 평가 대상이 된다 (anon에게 direct grant가 없다면 실질
--      위험은 낮지만 의도가 불명확하다).
--
-- 적용 전 확인:
--   - Flutter 클라이언트가 정상적으로 본인 프로필만 수정하는지 회귀 테스트
--   - 최소 지원 버전 Flutter 앱이 이 정책 변경으로 깨지지 않는지 확인

begin;

drop policy if exists "Enable update for users based on user_id" on public.profiles;

create policy "Enable update for users based on user_id"
on public.profiles
as permissive
for update
to authenticated
using ((select auth.uid()) = id)
with check ((select auth.uid()) = id);

commit;

-- 롤백:
-- begin;
-- drop policy if exists "Enable update for users based on user_id" on public.profiles;
-- create policy "Enable update for users based on user_id"
-- on public.profiles
-- as permissive
-- for update
-- to public
-- using ((select auth.uid()) = id);
-- commit;
