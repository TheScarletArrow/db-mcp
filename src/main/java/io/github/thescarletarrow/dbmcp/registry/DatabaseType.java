package io.github.thescarletarrow.dbmcp.registry;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Supported database engines and the driver specifics needed to talk to them.
 */
public enum DatabaseType {

    POSTGRESQL("jdbc:postgresql:", "org.postgresql.Driver", "SELECT 1"),
    ORACLE("jdbc:oracle:", "oracle.jdbc.OracleDriver", "SELECT 1 FROM DUAL");

    private final String urlPrefix;
    private final String driverClassName;
    private final String validationQuery;

    DatabaseType(String urlPrefix, String driverClassName, String validationQuery) {
        this.urlPrefix = urlPrefix;
        this.driverClassName = driverClassName;
        this.validationQuery = validationQuery;
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
