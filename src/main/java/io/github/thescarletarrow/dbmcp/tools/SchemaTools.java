package io.github.thescarletarrow.dbmcp.tools;

import io.github.thescarletarrow.dbmcp.metadata.SchemaInspector;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;

/**
 * Read-only catalog exploration tools.
 */
@Component
public class SchemaTools {

    private static final int DEFAULT_TABLE_LIMIT = 200;
    private static final int MAX_TABLE_LIMIT = 2000;

    private final SchemaInspector inspector;

    public SchemaTools(SchemaInspector inspector) {
        this.inspector = inspector;
    }

    @McpTool(name = "list_schemas",
            description = "List schemas of a registered database. System schemas (pg_catalog, SYS, ...) are hidden unless "
                    + "include_system is true.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = true))
    public List<SchemaInspector.SchemaInfo> listSchemas(
            @McpToolParam(description = "Registered database name") String database,
            @McpToolParam(required = false, description = "Include system schemas (default false)") Boolean includeSystem) {
        try {
            return inspector.listSchemas(database, Boolean.TRUE.equals(includeSystem));
        } catch (SQLException e) {
            throw ToolSupport.sqlFailure("list schemas", database, e);
        }
    }

    @McpTool(name = "list_tables",
            description = "List tables (and optionally views) in a schema of a registered database. Defaults to the "
                    + "connection's current schema. name_pattern uses SQL LIKE syntax, e.g. 'ord%'. Results are capped; "
                    + "'truncated' tells you to narrow the pattern.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = true))
    public SchemaInspector.TableList listTables(
            @McpToolParam(description = "Registered database name") String database,
            @McpToolParam(required = false, description = "Schema name; current schema when omitted") String schema,
            @McpToolParam(required = false, description = "LIKE pattern for table names, e.g. 'user%'") String namePattern,
            @McpToolParam(required = false, description = "Include views and materialized views (default true)") Boolean includeViews,
            @McpToolParam(required = false, description = "Maximum number of tables to return (default 200, max 2000)") Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_TABLE_LIMIT : Math.min(limit, MAX_TABLE_LIMIT);
        try {
            return inspector.listTables(database, schema, namePattern, includeViews == null || includeViews, effectiveLimit);
        } catch (SQLException e) {
            throw ToolSupport.sqlFailure("list tables", database, e);
        }
    }

    @McpTool(name = "describe_table",
            description = "Describe a table or view: columns with types/nullability/defaults, primary key, foreign keys and "
                    + "indexes. Identifiers may be given as written in SQL (case is normalized per engine) or double-quoted "
                    + "for an exact match.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = true))
    public SchemaInspector.TableDescription describeTable(
            @McpToolParam(description = "Registered database name") String database,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(required = false, description = "Schema name; current schema when omitted") String schema) {
        try {
            return inspector.describeTable(database, schema, table);
        } catch (SQLException e) {
            throw ToolSupport.sqlFailure("describe table " + table, database, e);
        }
    }
}
