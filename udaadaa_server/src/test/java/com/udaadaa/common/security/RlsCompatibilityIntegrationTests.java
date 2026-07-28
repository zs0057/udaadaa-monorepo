package com.udaadaa.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.udaadaa.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class RlsCompatibilityIntegrationTests extends AbstractIntegrationTest {

    private static final UUID USER_A = UUID.fromString("4fa5a560-d4d2-41f3-b218-c84ac2a2f847");
    private static final UUID USER_B = UUID.fromString("a6c4cda5-a044-4f44-a75c-434d2592551d");

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @BeforeAll
    void prepareRlsFixture() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    do $$
                    begin
                        if not exists (select 1 from pg_roles where rolname = 'spring_app') then
                            create role spring_app nologin nobypassrls;
                        end if;
                        if not exists (select 1 from pg_roles where rolname = 'authenticated') then
                            create role authenticated nologin nobypassrls;
                        end if;
                    end
                    $$
                    """);
            statement.execute("drop table if exists phase0_rls_probe");
            statement.execute("create table phase0_rls_probe (id bigint primary key, owner_id uuid not null)");
            statement.execute("insert into phase0_rls_probe values (1, '%s'), (2, '%s')"
                    .formatted(USER_A, USER_B));
            statement.execute("grant usage on schema public to spring_app, authenticated");
            statement.execute("grant select on phase0_rls_probe to spring_app, authenticated");
            statement.execute("alter table phase0_rls_probe enable row level security");
            statement.execute("create policy spring_server_access on phase0_rls_probe for select to spring_app using (true)");
            statement.execute("create policy flutter_user_access on phase0_rls_probe for select to authenticated "
                    + "using (owner_id::text = nullif(current_setting('request.jwt.claim.sub', true), ''))");
        }
    }

    @Test
    void springRoleCanReadRowsWhileFlutterRoleIsRestrictedByUserContext() throws Exception {
        assertThat(countAsRole("spring_app", null)).isEqualTo(2);
        assertThat(countAsRole("authenticated", USER_A.toString())).isEqualTo(1);
        assertThat(countAsRole("authenticated", null)).isZero();
    }

    private long countAsRole(String role, String subject) throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set local role " + role);
            if (subject != null) {
                statement.execute("select set_config('request.jwt.claim.sub', '%s', true)".formatted(subject));
            }
            try (ResultSet resultSet = statement.executeQuery("select count(*) from phase0_rls_probe")) {
                resultSet.next();
                return resultSet.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
