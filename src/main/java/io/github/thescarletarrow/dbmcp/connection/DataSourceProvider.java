package io.github.thescarletarrow.dbmcp.connection;

import io.github.thescarletarrow.dbmcp.registry.DatabaseDefinition;

import javax.sql.DataSource;

/**
 * Resolves a registered database name to a pooled {@link DataSource}.
 */
public interface DataSourceProvider {

    DataSource dataSource(String databaseName);

    DatabaseDefinition definition(String databaseName);
}
