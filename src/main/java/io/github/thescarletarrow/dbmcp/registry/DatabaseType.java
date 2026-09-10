package io.github.thescarletarrow.dbmcp.registry;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Supported database engines and the driver specifics needed to talk to them.
 */
public enum DatabaseType {

    POSTGRESQL("jdbc:postgresql:", "org.postgresql.Driver", "SELECT 1", "SET TRANSACTION READ ONLY"),
    ORACLE("jdbc:oracle:", "oracle.jdbc.OracleDriver", "SELECT 1 FROM DUAL", "SET TRANSACTION READ ONLY");

    private final String urlPrefix;
    private final String driverClassName;
    private final String validationQuery;
    private final String readOnlyTransactionStatement;

    DatabaseType(String urlPrefix, String driverClassName, String validationQuery, String readOnlyTransactionStatement) {
        this.urlPrefix = urlPrefix;
        this.driverClassName = driverClassName;
        this.validationQuery = validationQuery;
        this.readOnlyTransactionStatement = readOnlyTransactionStatement;
    }

    public String urlPrefix() {
        return urlPrefix;
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

    public boolean matchesUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.toLowerCase(Locale.ROOT).startsWith(urlPrefix);
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
            default -> Optional.empty();
        };
    }
}
