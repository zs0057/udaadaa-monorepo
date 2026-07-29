-- Local development only. This is not a production Flyway migration.
create role udaadaa login password 'local-udaadaa-password' nobypassrls;

grant connect on database udaadaa to udaadaa;
grant usage on schema public to udaadaa;

create table public.profiles (
    id uuid primary key,
    created_at timestamp with time zone not null default now(),
    nickname text not null unique,
    push_option boolean not null default true,
    fcm_token text,
    height numeric,
    weight numeric
);

grant select, insert, update, delete on public.profiles to udaadaa;
