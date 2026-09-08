package io.github.thescarletarrow.dbmcp.registry;

/**
 * A registered database: a name the model refers to, an engine, a JDBC URL, the alias
 * of the credential set to authenticate with and whether writes are allowed on it.
 *
 * @param readOnly when true (the default) only {@code run_query} may touch this database, regardless of the
 *                 server-wide {@code db-mcp.query.allow-writes} switch. Declared as {@link Boolean} so that
 *                 vaults written before the flag existed (no such field) deserialize as read-only.
 */
public record DatabaseDefinition(String name, DatabaseType type, String url, String credentialAlias, String description,
                                 Boolean readOnly) {

    public DatabaseDefinition(String name, DatabaseType type, String url, String credentialAlias, String description) {
        this(name, type, url, credentialAlias, description, true);
    }

    public DatabaseDefinition {
        name = Names.normalize(name, "database name");
        if (type == null) {
            throw new IllegalArgumentException("database type must not be null");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("JDBC url must not be blank");
        }
        if (!type.matchesUrl(url)) {
            throw new IllegalArgumentException("JDBC url must start with '" + type.urlPrefix() + "' for " + type);
        }
        credentialAlias = Names.normalize(credentialAlias, "credential alias");
        description = description == null ? "" : description.strip();
        readOnly = readOnly == null || readOnly;
    }

    public DatabaseDefinition withReadOnly(boolean value) {
        return new DatabaseDefinition(name, type, url, credentialAlias, description, value);
    }
}
