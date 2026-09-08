package io.github.thescarletarrow.dbmcp.sql;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM users;",
            "  select 'drop table x' as note from dual",
            "WITH t AS (SELECT 1) SELECT * FROM t",
            "-- delete everything\nSELECT count(*) FROM orders /* update */",
            "EXPLAIN ANALYZE SELECT 1",
            "SELECT \"update\", created_at FROM audit",
            "select * from t where name = 'it''s; fine'"
    })
    void acceptsReadStatements(String sql) {
        assertThat(SqlGuard.requireReadOnly(sql)).doesNotEndWith(";");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM users",
            "update users set a = 1",
            "SELECT 1; DROP TABLE users",
            "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d",
            "SELECT * FROM t FOR UPDATE",
            "select pg_sleep(1); commit",
            "CREATE TABLE t (id int)",
            "begin dbms_output.put_line('x'); end",
            "",
            "-- only a comment"
    })
    void rejectsNonReadStatements(String sql) {
        assertThatThrownBy(() -> SqlGuard.requireReadOnly(sql)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT INTO t VALUES (1);", "CREATE TABLE t (id int)", "UPDATE t SET a = ';'"})
    void singleStatementAllowsWrites(String sql) {
        assertThat(SqlGuard.requireSingleStatement(sql)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT INTO t VALUES (1); DELETE FROM t", "  ", "/* x */"})
    void singleStatementRejectsBatchesAndEmpty(String sql) {
        assertThatThrownBy(() -> SqlGuard.requireSingleStatement(sql)).isInstanceOf(IllegalArgumentException.class);
    }
}
