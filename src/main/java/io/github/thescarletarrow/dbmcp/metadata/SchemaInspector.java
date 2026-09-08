package io.github.thescarletarrow.dbmcp.metadata;

import io.github.thescarletarrow.dbmcp.connection.DataSourceProvider;
import io.github.thescarletarrow.dbmcp.registry.DatabaseType;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads catalog information through {@link DatabaseMetaData}, which works uniformly for PostgreSQL and Oracle.
 */
@Service
public class SchemaInspector {

    private static final Set<String> PG_SYSTEM_SCHEMAS = Set.of("pg_catalog", "information_schema", "pg_toast");
    private static final Set<String> ORACLE_SYSTEM_SCHEMAS = Set.of("SYS", "SYSTEM", "XDB", "CTXSYS", "MDSYS", "OLAPSYS",
            "ORDSYS", "ORDDATA", "OUTLN", "WMSYS", "DBSNMP", "APPQOSSYS", "AUDSYS", "GSMADMIN_INTERNAL", "DVSYS", "LBACSYS",
            "OJVMSYS", "DBSFWUSER", "GGSYS", "ANONYMOUS", "REMOTE_SCHEDULER_AGENT", "SYS$UMF", "DIP", "ORACLE_OCM", "XS$NULL");

    private final DataSourceProvider dataSources;

    public SchemaInspector(DataSourceProvider dataSources) {
        this.dataSources = dataSources;
    }

    public record SchemaInfo(String name, boolean system) {
    }

    public record TableInfo(String schema, String name, String type, String remarks) {
    }

    public record ColumnInfo(String name, String type, Integer size, Integer scale, boolean nullable, String defaultValue,
                             boolean primaryKey, String remarks) {
    }

    public record ForeignKeyInfo(String constraint, List<String> columns, String referencedSchema, String referencedTable,
                                 List<String> referencedColumns) {
    }

    public record IndexInfo(String name, boolean unique, List<String> columns) {
    }

    public record TableDescription(String schema, String name, String type, String remarks, List<ColumnInfo> columns,
                                   List<String> primaryKey, List<ForeignKeyInfo> foreignKeys, List<IndexInfo> indexes) {
    }

    public record TableList(List<TableInfo> tables, boolean truncated) {
    }

    public List<SchemaInfo> listSchemas(String databaseName, boolean includeSystem) throws SQLException {
        DatabaseType type = dataSources.definition(databaseName).type();
        List<SchemaInfo> result = new ArrayList<>();
        try (Connection c = dataSources.dataSource(databaseName).getConnection();
             ResultSet rs = c.getMetaData().getSchemas()) {
            while (rs.next()) {
                String name = rs.getString("TABLE_SCHEM");
                boolean system = isSystemSchema(type, name);
                if (includeSystem || !system) {
                    result.add(new SchemaInfo(name, system));
                }
            }
        }
        return result;
    }

    public TableList listTables(String databaseName, String schema, String namePattern, boolean includeViews, int limit)
            throws SQLException {
        String[] types = includeViews ? new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"} : new String[]{"TABLE"};
        List<TableInfo> tables = new ArrayList<>();
        boolean truncated = false;
        try (Connection c = dataSources.dataSource(databaseName).getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String effectiveSchema = resolveSchema(md, dataSources.definition(databaseName).type(), schema);
            try (ResultSet rs = md.getTables(null, effectiveSchema, blankToWildcard(namePattern), types)) {
                while (rs.next()) {
                    if (tables.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    tables.add(new TableInfo(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"),
                            rs.getString("TABLE_TYPE"), rs.getString("REMARKS")));
                }
            }
        }
        return new TableList(tables, truncated);
    }

    public TableDescription describeTable(String databaseName, String schema, String table) throws SQLException {
        DatabaseType type = dataSources.definition(databaseName).type();
        try (Connection c = dataSources.dataSource(databaseName).getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String effectiveSchema = resolveSchema(md, type, schema);
            String effectiveTable = normalizeIdentifier(md, table);

            TableInfo tableInfo = null;
            try (ResultSet rs = md.getTables(null, effectiveSchema, effectiveTable, null)) {
                if (rs.next()) {
                    tableInfo = new TableInfo(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"),
                            rs.getString("TABLE_TYPE"), rs.getString("REMARKS"));
                }
            }
            if (tableInfo == null) {
                throw new IllegalArgumentException("Table '" + table + "' not found"
                        + (effectiveSchema == null ? "" : " in schema '" + effectiveSchema + "'")
                        + ". Use list_tables to find the exact name.");
            }
            String resolvedSchema = tableInfo.schema();
            String resolvedTable = tableInfo.name();

            List<String> primaryKey = new ArrayList<>();
            try (ResultSet rs = md.getPrimaryKeys(null, resolvedSchema, resolvedTable)) {
                Map<Short, String> ordered = new java.util.TreeMap<>();
                while (rs.next()) {
                    ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                }
                primaryKey.addAll(ordered.values());
            }

            List<ColumnInfo> columns = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, resolvedSchema, resolvedTable, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    int scale = rs.getInt("DECIMAL_DIGITS");
                    boolean scaleNull = rs.wasNull();
                    columns.add(new ColumnInfo(name, rs.getString("TYPE_NAME"), size == 0 ? null : size,
                            scaleNull ? null : scale, "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                            rs.getString("COLUMN_DEF"), primaryKey.contains(name), rs.getString("REMARKS")));
                }
            }

            Map<String, ForeignKeyInfo> fks = new LinkedHashMap<>();
            try (ResultSet rs = md.getImportedKeys(null, resolvedSchema, resolvedTable)) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    String pkSchema = rs.getString("PKTABLE_SCHEM");
                    String pkTable = rs.getString("PKTABLE_NAME");
                    String fkColumn = rs.getString("FKCOLUMN_NAME");
                    String pkColumn = rs.getString("PKCOLUMN_NAME");
                    ForeignKeyInfo fk = fks.computeIfAbsent(fkName, k -> new ForeignKeyInfo(k, new ArrayList<>(),
                            pkSchema, pkTable, new ArrayList<>()));
                    fk.columns().add(fkColumn);
                    fk.referencedColumns().add(pkColumn);
                }
            }

            Map<String, IndexInfo> indexes = new LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(null, resolvedSchema, resolvedTable, false, true)) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (indexName == null || column == null) {
                        continue;
                    }
                    boolean unique = !rs.getBoolean("NON_UNIQUE");
                    indexes.computeIfAbsent(indexName, k -> new IndexInfo(k, unique, new ArrayList<>())).columns().add(column);
                }
            }

            return new TableDescription(resolvedSchema, resolvedTable, tableInfo.type(), tableInfo.remarks(), columns,
                    primaryKey, List.copyOf(fks.values()), List.copyOf(indexes.values()));
        }
    }

    private static boolean isSystemSchema(DatabaseType type, String schema) {
        return switch (type) {
            case POSTGRESQL -> PG_SYSTEM_SCHEMAS.contains(schema) || schema.startsWith("pg_");
            case ORACLE -> ORACLE_SYSTEM_SCHEMAS.contains(schema) || schema.startsWith("APEX_") || schema.startsWith("FLOWS_");
        };
    }

    /**
     * Uses the connection's current schema when none is given; Oracle stores unquoted identifiers upper-cased,
     * PostgreSQL lower-cased, so the caller can pass names as they appear in SQL.
     */
    private static String resolveSchema(DatabaseMetaData md, DatabaseType type, String schema) throws SQLException {
        if (schema == null || schema.isBlank()) {
            String current = md.getConnection().getSchema();
            if (current != null && !current.isBlank()) {
                return current;
            }
            return switch (type) {
                case POSTGRESQL -> "public";
                case ORACLE -> md.getUserName();
            };
        }
        return normalizeIdentifier(md, schema.strip());
    }

    private static String normalizeIdentifier(DatabaseMetaData md, String identifier) throws SQLException {
        if (identifier.startsWith("\"") && identifier.endsWith("\"") && identifier.length() >= 2) {
            return identifier.substring(1, identifier.length() - 1);
        }
        if (md.storesUpperCaseIdentifiers()) {
            return identifier.toUpperCase(Locale.ROOT);
        }
        if (md.storesLowerCaseIdentifiers()) {
            return identifier.toLowerCase(Locale.ROOT);
        }
        return identifier;
    }

    private static String blankToWildcard(String pattern) {
        return pattern == null || pattern.isBlank() ? "%" : pattern.strip();
    }
}
