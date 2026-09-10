package io.github.thescarletarrow.dbmcp.tools;

import io.github.thescarletarrow.dbmcp.connection.DataSourceManager;
import io.github.thescarletarrow.dbmcp.metadata.SchemaInspector;
import io.github.thescarletarrow.dbmcp.registry.DatabaseRegistry;
import io.github.thescarletarrow.dbmcp.sql.QueryExecutor;
import io.github.thescarletarrow.dbmcp.sql.QueryResult;
import io.github.thescarletarrow.dbmcp.support.TestProperties;
import io.github.thescarletarrow.dbmcp.vault.SecretVault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL round trip. Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresContainerIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    static Path dir;

    static GenericApplicationContext context;
    static DataSourceManager dataSources;
    static ConnectionTools connectionTools;
    static QueryExecutor executor;
    static SchemaInspector inspector;

    @BeforeAll
    static void wire() {
        context = new GenericApplicationContext();
        context.refresh();
        var properties = TestProperties.defaults(dir);
        var registry = new DatabaseRegistry(new SecretVault(properties, JsonMapper.builder().build()), context);
        dataSources = new DataSourceManager(registry, properties);
        connectionTools = new ConnectionTools(registry, dataSources);
        executor = new QueryExecutor(dataSources, properties);
        inspector = new SchemaInspector(dataSources);
    }

    @AfterAll
    static void cleanup() {
        context.close();
    }

    @Test
    void registerAndQuery() throws Exception {
        ConnectionTools.RegistrationResult registration = connectionTools.registerDatabase(null, "pg", postgres.getJdbcUrl(),
                null, "pg-dev", postgres.getUsername(), postgres.getPassword(), "testcontainer", null);
        assertThat(registration.connection().ok()).isTrue();
        assertThat(registration.connection().product()).isEqualTo("PostgreSQL");

        QueryResult result = executor.query("pg", "SELECT 1 AS one, 'x' AS txt, now() AS ts", null);
        assertThat(result.rows().getFirst().get(0)).isEqualTo(1);
        assertThat(result.rows().getFirst().get(1)).isEqualTo("x");

        assertThat(inspector.listSchemas("pg", false)).extracting(SchemaInspector.SchemaInfo::name).contains("public");
        assertThat(connectionTools.testConnection("pg").ok()).isTrue();
    }

    @Test
    void queryAfterPoolValidation() throws Exception {
        connectionTools.registerDatabase(null, "pg-idle", postgres.getJdbcUrl(), null, "pg-dev", null, null, null, null);
        assertThat(connectionTools.testConnection("pg-idle").ok()).isTrue();
        // idle longer than Hikari's alive-bypass window, so the pool runs its test query before lending the connection
        Thread.sleep(1_000);

        QueryResult result = executor.query("pg-idle", "SELECT current_setting('transaction_read_only') AS ro", null);
        assertThat(result.rows().getFirst().get(0)).isEqualTo("on");
    }

    @Test
    void readOnlyDatabaseRefusesEveryWritePath() throws Exception {
        connectionTools.registerDatabase(null, "pg-ro", postgres.getJdbcUrl(), null, "pg-dev", null, null, null, true);

        // The guard stops the statement before it reaches the server ...
        assertThatThrownBy(() -> executor.query("pg-ro", "SELECT 1 AS id INTO guard_probe", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INTO");
        assertThatThrownBy(() -> executor.query("pg-ro", "SELECT nextval('guard_probe_seq')", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NEXTVAL");
        // ... and a read-only database refuses execute_statement even on a server started with allow-writes=true
        QueryExecutor writesEnabled = new QueryExecutor(dataSources, TestProperties.withWrites(dir));
        assertThatThrownBy(() -> writesEnabled.execute("pg-ro", "CREATE TABLE guard_probe (id int)"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");

        // The transaction the server opens for run_query would have refused a write anyway.
        assertThat(executor.query("pg-ro", "SELECT current_setting('transaction_read_only') AS ro", null)
                .rows().getFirst().getFirst()).isEqualTo("on");
        assertThat(executor.query("pg-ro", "SELECT to_regclass('guard_probe') AS created", null)
                .rows().getFirst().getFirst()).isNull();
    }
}
