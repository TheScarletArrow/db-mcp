package io.github.thescarletarrow.dbmcp.sql;

import io.github.thescarletarrow.dbmcp.config.DbMcpProperties;
import io.github.thescarletarrow.dbmcp.connection.DataSourceProvider;
import io.github.thescarletarrow.dbmcp.registry.DatabaseDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs SQL against a registered database with row limits, timeouts and JSON-friendly value mapping.
 *
 * <p>Reads are fenced three times over, because no single fence holds on every engine: {@link SqlGuard} rejects
 * anything that is not plainly a single read, the transaction is declared read-only on the server, and the JDBC
 * connection is put in read-only mode and always rolled back. Writes go through {@link #execute} only, which
 * additionally requires the server-wide switch and a database that is not registered as read-only.
 */
@Service
public class QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);

    private final DataSourceProvider dataSources;
    private final DbMcpProperties.Query settings;
    private final Set<String> withoutReadOnlyTransactions = ConcurrentHashMap.newKeySet();

    public QueryExecutor(DataSourceProvider dataSources, DbMcpProperties properties) {
        this.dataSources = dataSources;
        this.settings = properties.query();
    }

    public DbMcpProperties.Query settings() {
        return settings;
    }

    /**
     * Executes a read-only statement inside a read-only transaction that is always rolled back.
     */
    public QueryResult query(String databaseName, String sql) throws SQLException {
        return query(databaseName, sql, null);
    }

    public QueryResult query(String databaseName, String sql, Integer maxRows) throws SQLException {
        DatabaseDefinition definition = dataSources.definition(databaseName);
        String statement = SqlGuard.requireReadOnly(sql);
        int limit = effectiveLimit(maxRows);
        long started = System.nanoTime();
        try (Connection connection = dataSources.dataSource(databaseName).getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            beginReadOnlyTransaction(connection, definition);
            try (Statement stmt = connection.createStatement()) {
                configure(stmt, limit);
                try (ResultSet rs = stmt.executeQuery(statement)) {
                    return readRows(rs, limit, started);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Asks the server to make this transaction read-only, so that a statement the guard did not recognise as a
     * write is still refused by the database. Best effort: engines that do not know the statement (or a driver
     * that already opened the transaction read-only) leave the guard and the rollback as the remaining fences.
     */
    private void beginReadOnlyTransaction(Connection connection, DatabaseDefinition definition) throws SQLException {
        String statement = definition.type().readOnlyTransactionStatement();
        if (statement == null || withoutReadOnlyTransactions.contains(definition.name())) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(timeoutSeconds());
            stmt.execute(statement);
        } catch (SQLException e) {
            withoutReadOnlyTransactions.add(definition.name());
            log.warn("Database '{}' rejected '{}' ({}); read-only queries now rely on the SQL guard and the JDBC "
                    + "read-only flag only", definition.name(), statement, e.getMessage());
            connection.rollback(); // a failed statement can leave the transaction aborted (PostgreSQL)
        }
    }

    /**
     * Executes a DML/DDL statement and commits. Only available when writes are enabled in configuration.
     */
    public UpdateResult execute(String databaseName, String sql) throws SQLException {
        // Both switches are checked before the statement is even looked at: on a read-only database no write
        // statement is ever sent, so nothing can slip through on a parsing quirk.
        if (!settings.allowWrites()) {
            throw new IllegalStateException("Write statements are disabled. Start the server with db-mcp.query.allow-writes=true to enable execute_statement.");
        }
        if (dataSources.definition(databaseName).readOnly()) {
            throw new IllegalStateException("Database '" + databaseName + "' is registered as read-only. "
                    + "Call set_read_only with readOnly=false (after confirming with the user) to allow writes on it.");
        }
        String statement = SqlGuard.requireSingleStatement(sql);
        long started = System.nanoTime();
        try (Connection connection = dataSources.dataSource(databaseName).getConnection()) {
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                configure(stmt, 0);
                int affected = stmt.executeUpdate(statement);
                connection.commit();
                return new UpdateResult(affected, elapsedMillis(started));
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private int effectiveLimit(Integer requested) {
        int limit = requested == null || requested <= 0 ? settings.defaultMaxRows() : requested;
        return Math.min(limit, settings.hardMaxRows());
    }

    private int timeoutSeconds() {
        return (int) Math.max(1, settings.timeout().toSeconds());
    }

    private void configure(Statement stmt, int limit) throws SQLException {
        stmt.setQueryTimeout(timeoutSeconds());
        if (limit > 0) {
            stmt.setMaxRows(limit + 1); // one extra row tells us whether the result was truncated
            stmt.setFetchSize(Math.min(limit + 1, 500));
        }
    }

    private QueryResult readRows(ResultSet rs, int limit, long started) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<String> columns = new ArrayList<>(count);
        List<String> types = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(meta.getColumnLabel(i));
            types.add(meta.getColumnTypeName(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= limit) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                row.add(toJsonValue(rs.getObject(i)));
            }
            rows.add(row);
        }
        return new QueryResult(columns, types, rows, rows.size(), truncated, elapsedMillis(started));
    }

    Object toJsonValue(Object value) throws SQLException {
        return switch (value) {
            case null -> null;
            case Boolean b -> b;
            case Integer i -> i;
            case Long l -> l;
            case Short s -> s.intValue();
            case Byte b -> b.intValue();
            case Double d -> d;
            case Float f -> f;
            case BigDecimal d -> d.stripTrailingZeros().scale() <= 0 && d.precision() <= 18 ? d.longValueExact() : d;
            case Number n -> n.toString();
            case String s -> clip(s);
            case Clob clob -> clip(clob.getSubString(1, (int) Math.min(clob.length(), settings.maxCellLength() + 1L)));
            case Blob blob -> "<binary " + blob.length() + " bytes>";
            case byte[] bytes -> "<binary " + bytes.length + " bytes>";
            case java.sql.Array array -> clip(String.valueOf(array.getArray() instanceof Object[] o ? List.of(o) : array.getArray()));
            default -> clip(String.valueOf(value));
        };
    }

    private String clip(String s) {
        int max = settings.maxCellLength();
        return s.length() <= max ? s : s.substring(0, max) + "…[truncated " + (s.length() - max) + " chars]";
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
