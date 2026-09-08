package io.github.thescarletarrow.dbmcp.support;

import io.github.thescarletarrow.dbmcp.connection.DataSourceProvider;
import io.github.thescarletarrow.dbmcp.registry.DatabaseDefinition;
import io.github.thescarletarrow.dbmcp.registry.DatabaseType;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.util.NoSuchElementException;

/**
 * Test double: serves an in-memory H2 database (PostgreSQL compatibility mode) under a registered name.
 */
public final class H2DataSources implements DataSourceProvider {

    private final String name;
    private final JdbcDataSource dataSource;

    public H2DataSources(String name) {
        this.name = name;
        this.dataSource = new JdbcDataSource();
        // A fake PostgreSQL URL keeps DatabaseDefinition validation happy; H2 is what actually answers.
        this.dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        this.dataSource.setUser("sa");
        this.dataSource.setPassword("");
    }

    @Override
    public DataSource dataSource(String databaseName) {
        if (!name.equals(databaseName)) {
            throw new NoSuchElementException("Unknown database '" + databaseName + "'");
        }
        return dataSource;
    }

    @Override
    public DatabaseDefinition definition(String databaseName) {
        dataSource(databaseName);
        return new DatabaseDefinition(name, DatabaseType.POSTGRESQL, "jdbc:postgresql://localhost/" + name, "test", "");
    }
}
