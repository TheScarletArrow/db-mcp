package io.github.thescarletarrow.dbmcp.registry;

/**
 * A registered database: a name the model refers to, an engine, a JDBC URL and the alias
 * of the credential set to authenticate with.
 */
public record DatabaseDefinition(String name, DatabaseType type, String url, String credentialAlias, String description) {

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
    }
}
