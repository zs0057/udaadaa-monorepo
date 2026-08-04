-- Phase 0: spring_app DB Role 생성 (설계 산출물, 운영 미적용)
--
-- 목적: Spring 백엔드 전용 최소 권한 PostgreSQL 로그인 Role을 만든다.
-- 원칙:
--   - Flutter가 쓰는 anon/authenticated와 분리된 별도 Role을 사용한다.
--   - Phase 1(Member)에 필요한 profiles 테이블 권한만 부여한다. 이후 Phase가
--     진행되며 필요한 테이블 권한을 그 Phase 문서에서 추가로 GRANT한다.
--   - spring_app은 BYPASSRLS로 설정한다. Supabase RLS는 auth.uid() 기반으로
--     동작해 Spring의 JWT 인증 컨텍스트와 맞지 않으므로, 행 단위 접근 통제는
--     Spring 애플리케이션 계층(JWT sub 검증)이 책임진다. 기존 authenticated
--     Role의 RLS는 그대로 유지되어 Flutter 직접 접근에는 계속 적용된다.
--   - 비밀번호는 이 파일에 넣지 않는다. 실행 시 별도 Secret 관리 도구에서
--     생성한 값으로 교체한다.
--
-- 실행 전 확인:
--   - Supabase SQL Editor(프로젝트 소유자 권한)에서 실행한다.
--   - CONNECTION LIMIT은 Spring HikariCP maximum-pool-size(기본 10)보다
--     여유 있게 설정한다.

begin;

create role spring_app with
  login
  password '__REPLACE_WITH_GENERATED_SECRET__'
  connection limit 15
  nosuperuser
  nocreatedb
  nocreaterole
  noinherit;

alter role spring_app bypassrls;

comment on role spring_app is 'Spring 백엔드 전용 서버 Role. RLS를 우회하며 애플리케이션 계층에서 권한을 검증한다.';

grant usage on schema public to spring_app;

-- Phase 1 Member 범위: profiles 조회·초기화·수정만 허용. 삭제 권한은 부여하지 않는다
-- (회원 탈퇴 실제 처리는 별도 Phase에서 재검토한다).
grant select, insert, update on table public.profiles to spring_app;

commit;

-- 적용 후 확인 쿼리 (읽기 전용):
--   select rolname, rolbypassrls, rolconnlimit from pg_roles where rolname = 'spring_app';
--   select table_name, privilege_type from information_schema.role_table_grants
--     where grantee = 'spring_app';
