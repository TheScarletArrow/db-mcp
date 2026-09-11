package io.github.thescarletarrow.dbmcp.tools;

import io.github.thescarletarrow.dbmcp.sql.QueryExecutor;
import io.github.thescarletarrow.dbmcp.sql.QueryResult;
import io.github.thescarletarrow.dbmcp.sql.UpdateResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * SQL execution tools.
 */
@Component
public class QueryTools {

    private final QueryExecutor executor;

    public QueryTools(QueryExecutor executor) {
        this.executor = executor;
    }

    @McpTool(name = "run_query",
            description = "Run a single read-only SQL statement (SELECT / WITH / EXPLAIN) against a registered database "
                    + "inside a read-only transaction that is rolled back afterwards. Rows are capped by max_rows "
                    + "(default 200); 'truncated' is true when more rows exist - add WHERE/LIMIT/FETCH FIRST to narrow. "
                    + "Long text cells are clipped. Use the engine's SQL dialect (PostgreSQL or Oracle) of the target "
                    + "database. Anything that could write is rejected before the database sees it: DML/DDL, "
                    + "'SELECT ... INTO', locking clauses, a second statement after ';', and routines that write or "
                    + "reach outside the database (nextval, lo_import, pg_read_file, dblink, CSVWRITE, DBMS_*/UTL_*).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = true))
    public QueryResult runQuery(
            @McpToolParam(description = "Registered database name") String database,
            @McpToolParam(description = "One SELECT/WITH/EXPLAIN statement, without a trailing batch of other statements") String sql,
            @McpToolParam(required = false, description = "Maximum rows to return (default 200, hard cap configured on the server)") Integer maxRows) {
        try {
            return executor.query(database, sql, maxRows);
        } catch (SQLException e) {
            throw ToolSupport.sqlFailure("run query", database, e);
        }
    }

    @McpTool(name = "execute_statement",
            description = "Execute a single DML/DDL statement (INSERT/UPDATE/DELETE/CREATE/...) and commit it. Disabled "
                    + "unless the server runs with db-mcp.query.allow-writes=true AND the database is registered with "
                    + "readOnly=false; a read-only database refuses every write, there is no way around it from a query. "
                    + "Confirm destructive changes with the user before calling this.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false,
                    openWorldHint = true))
    public UpdateResult executeStatement(
            @McpToolParam(description = "Registered database name") String database,
            @McpToolParam(description = "One DML or DDL statement") String sql) {
        try {
            return executor.execute(database, sql);
        } catch (SQLException e) {
            throw ToolSupport.sqlFailure("execute statement", database, e);
        }
    }
}
