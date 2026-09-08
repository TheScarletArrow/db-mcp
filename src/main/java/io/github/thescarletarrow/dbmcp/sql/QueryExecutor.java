package io.github.thescarletarrow.dbmcp.sql;

import io.github.thescarletarrow.dbmcp.config.DbMcpProperties;
import io.github.thescarletarrow.dbmcp.connection.DataSourceProvider;
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

/**
 * Runs SQL against a registered database with row limits, timeouts and JSON-friendly value mapping.
 */
@Service
public class QueryExecutor {

    private final DataSourceProvider dataSources;
    private final DbMcpProperties.Query settings;

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
        String statement = SqlGuard.requireReadOnly(sql);
        int limit = effectiveLimit(maxRows);
        long started = System.nanoTime();
        try (Connection connection = dataSources.dataSource(databaseName).getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
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
     * Executes a DML/DDL statement and commits. Only available when writes are enabled in configuration.
     */
    public UpdateResult execute(String databaseName, String sql) throws SQLException {
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

    private void configure(Statement stmt, int limit) throws SQLException {
        stmt.setQueryTimeout((int) Math.max(1, settings.timeout().toSeconds()));
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
