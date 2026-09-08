package io.github.thescarletarrow.dbmcp.sql;

import io.github.thescarletarrow.dbmcp.support.H2DataSources;
import io.github.thescarletarrow.dbmcp.support.TestProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryExecutorTest {

    private static final H2DataSources DATA_SOURCES = new H2DataSources("qexec");

    @TempDir
    static Path dir;

    @BeforeAll
    static void schema() throws SQLException {
        try (Connection c = DATA_SOURCES.dataSource("qexec").getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(100), price NUMERIC(10,2), active BOOLEAN, note CLOB)");
            for (int i = 1; i <= 10; i++) {
                s.execute("INSERT INTO items VALUES (" + i + ", 'item " + i + "', " + i + ".50, " + (i % 2 == 0) + ", '" + "x".repeat(3000) + "')");
            }
            c.commit();
        }
    }

    @Test
    void queryMapsValuesAndTruncates() throws SQLException {
        QueryExecutor executor = new QueryExecutor(DATA_SOURCES, TestProperties.defaults(dir));

        QueryResult result = executor.query("qexec", "SELECT id, name, price, active, note FROM items ORDER BY id", 3);

        assertThat(result.columns()).containsExactly("id", "name", "price", "active", "note");
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
        List<Object> first = result.rows().getFirst();
        assertThat(first.get(0)).isEqualTo(1);
        assertThat(first.get(1)).isEqualTo("item 1");
        assertThat(first.get(2)).isEqualTo(new java.math.BigDecimal("1.50"));
        assertThat(first.get(3)).isEqualTo(false);
        assertThat((String) first.get(4)).hasSizeLessThan(2100).contains("[truncated");
    }

    @Test
    void queryWithoutLimitUsesDefault() throws SQLException {
        QueryExecutor executor = new QueryExecutor(DATA_SOURCES, TestProperties.defaults(dir));
        QueryResult result = executor.query("qexec", "SELECT id FROM items");
        assertThat(result.rowCount()).isEqualTo(10);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void writesAreRejectedInQueryAndDisabledByDefault() {
        QueryExecutor executor = new QueryExecutor(DATA_SOURCES, TestProperties.defaults(dir));
        assertThatThrownBy(() -> executor.query("qexec", "DELETE FROM items", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.execute("qexec", "DELETE FROM items")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-writes");
    }

    @Test
    void executeCommitsWhenWritesEnabled() throws SQLException {
        QueryExecutor executor = new QueryExecutor(DATA_SOURCES, TestProperties.withWrites(dir));
        UpdateResult update = executor.execute("qexec", "UPDATE items SET name = 'renamed' WHERE id = 1");
        assertThat(update.affectedRows()).isEqualTo(1);
        QueryResult check = executor.query("qexec", "SELECT name FROM items WHERE id = 1", null);
        assertThat(check.rows().getFirst().getFirst()).isEqualTo("renamed");
    }
}
