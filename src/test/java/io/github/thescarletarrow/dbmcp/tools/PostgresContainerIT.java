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
    static ConnectionTools connectionTools;
    static QueryExecutor executor;
    static SchemaInspector inspector;

    @BeforeAll
    static void wire() {
        context = new GenericApplicationContext();
        context.refresh();
        var properties = TestProperties.defaults(dir);
        var registry = new DatabaseRegistry(new SecretVault(properties, JsonMapper.builder().build()), context);
        var dataSources = new DataSourceManager(registry, properties);
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
        connectionTools.registerDatabase(null, "pg-idle", postgres.getJdbcUrl(), null, "pg-dev", null, null, null);
        assertThat(connectionTools.testConnection("pg-idle").ok()).isTrue();
        // idle longer than Hikari's alive-bypass window, so the pool runs its test query before lending the connection
        Thread.sleep(1_000);

        QueryResult result = executor.query("pg-idle", "SELECT current_setting('transaction_read_only') AS ro", null);
        assertThat(result.rows().getFirst().get(0)).isEqualTo("on");
    }
}
