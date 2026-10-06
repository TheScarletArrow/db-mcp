package io.github.thescarletarrow.dbmcp.registry;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Supported database engines and the driver specifics needed to talk to them.
 */
public enum DatabaseType {

    POSTGRESQL("org.postgresql.Driver", "SELECT 1", "SET TRANSACTION READ ONLY", true, true, "jdbc:postgresql:"),
    ORACLE("oracle.jdbc.OracleDriver", "SELECT 1 FROM DUAL", "SET TRANSACTION READ ONLY", true, true, "jdbc:oracle:"),
    CLICKHOUSE("com.clickhouse.jdbc.ClickHouseDriver", "SELECT 1", null, false, false, "jdbc:ch:", "jdbc:clickhouse:");

    private final List<String> urlPrefixes;
    private final String driverClassName;
    private final String validationQuery;
    private final String readOnlyTransactionStatement;
    private final boolean transactional;
    private final boolean configurePoolReadOnly;

    DatabaseType(String driverClassName, String validationQuery, String readOnlyTransactionStatement,
                 boolean transactional, boolean configurePoolReadOnly, String... urlPrefixes) {
        this.urlPrefixes = List.of(urlPrefixes);
        this.driverClassName = driverClassName;
        this.validationQuery = validationQuery;
        this.readOnlyTransactionStatement = readOnlyTransactionStatement;
        this.transactional = transactional;
        this.configurePoolReadOnly = configurePoolReadOnly;
    }

    public String urlPrefix() {
        return urlPrefixes.getFirst();
    }

    public String urlPrefixesDescription() {
        return String.join("' or '", urlPrefixes);
    }

    public String driverClassName() {
        return driverClassName;
    }

    public String validationQuery() {
        return validationQuery;
    }

    /**
     * Statement that turns the current transaction read-only on the server itself. {@code Connection.setReadOnly}
     * is only a hint - the Oracle driver ignores it outright - so queries ask the server for the guarantee too.
     */
    public String readOnlyTransactionStatement() {
        return readOnlyTransactionStatement;
    }

    public boolean transactional() {
        return transactional;
    }

    public boolean configurePoolReadOnly() {
        return configurePoolReadOnly;
    }

    public boolean matchesUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        String normalized = jdbcUrl.toLowerCase(Locale.ROOT);
        return urlPrefixes.stream().anyMatch(normalized::startsWith);
    }

    public static Optional<DatabaseType> fromUrl(String jdbcUrl) {
        return Arrays.stream(values()).filter(t -> t.matchesUrl(jdbcUrl)).findFirst();
    }

    public static Optional<DatabaseType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PG", "POSTGRES", "POSTGRESQL" -> Optional.of(POSTGRESQL);
            case "ORA", "ORACLE" -> Optional.of(ORACLE);
            case "CH", "CLICKHOUSE" -> Optional.of(CLICKHOUSE);
            default -> Optional.empty();
        };
    }
}
