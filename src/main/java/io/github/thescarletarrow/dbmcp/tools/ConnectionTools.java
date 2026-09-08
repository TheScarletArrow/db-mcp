package io.github.thescarletarrow.dbmcp.tools;

import io.github.thescarletarrow.dbmcp.connection.ConnectionFailedException;
import io.github.thescarletarrow.dbmcp.connection.DataSourceManager;
import io.github.thescarletarrow.dbmcp.registry.Credential;
import io.github.thescarletarrow.dbmcp.registry.DatabaseDefinition;
import io.github.thescarletarrow.dbmcp.registry.DatabaseRegistry;
import io.github.thescarletarrow.dbmcp.registry.DatabaseType;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * Tools for registering databases and the credentials used to reach them.
 */
@Component
public class ConnectionTools {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTools.class);

    private final DatabaseRegistry registry;
    private final DataSourceManager dataSources;

    public ConnectionTools(DatabaseRegistry registry, DataSourceManager dataSources) {
        this.registry = registry;
        this.dataSources = dataSources;
    }

    public record DatabaseView(String name, DatabaseType type, String url, String credentialAlias, String username,
                               String description, boolean readOnly) {
    }

    public record RegistrationResult(DatabaseView database, String credentialsSource, ConnectionStatus connection) {
    }

    public record ConnectionStatus(boolean ok, String product, String version, String user, String currentSchema,
                                   String error) {
    }

    /** Fields requested from the user when the client supports MCP elicitation. */
    public record CredentialsForm(String username, String password) {
    }

    @McpTool(name = "list_databases",
            description = "List all registered databases (PostgreSQL and Oracle) with their JDBC URL and the credential "
                    + "alias they use. Passwords are never returned. Call this first to see which database names can be "
                    + "used with the other tools.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public List<DatabaseView> listDatabases() {
        return registry.listDatabases().stream().map(this::view).toList();
    }

    @McpTool(name = "list_credentials",
            description = "List stored credential sets: alias, username and the databases that use them. Passwords are "
                    + "never returned. Use an existing alias in register_database when a new database shares the same "
                    + "login/password (typical for several dev/test environments).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public List<CredentialView> listCredentials() {
        return registry.listCredentials().stream()
                .map(c -> new CredentialView(c.alias(), c.username(), registry.databasesUsing(c.alias())))
                .toList();
    }

    public record CredentialView(String alias, String username, List<String> usedBy) {
    }

    @McpTool(name = "save_credentials",
            description = "Store (or update) a login/password pair under an alias in the encrypted local vault so it can be "
                    + "reused by one or more databases. ALWAYS ask the user for the username and password; never guess or "
                    + "invent them. Updating an alias re-connects every database that uses it.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public CredentialView saveCredentials(
            @McpToolParam(description = "Alias for this credential set, e.g. 'dev' or 'app-user' (a-z, 0-9, '.', '_', '-')") String alias,
            @McpToolParam(description = "Database login") String username,
            @McpToolParam(description = "Database password") String password) {
        Credential saved = registry.saveCredential(new Credential(alias, username, password));
        log.info("Stored credentials '{}' for user '{}'", saved.alias(), saved.username());
        return new CredentialView(saved.alias(), saved.username(), registry.databasesUsing(saved.alias()));
    }

    @McpTool(name = "remove_credentials",
            description = "Delete a stored credential set. Fails if any registered database still uses it.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    public String removeCredentials(@McpToolParam(description = "Alias of the credential set") String alias) {
        return registry.removeCredential(alias)
                ? "Credentials '" + alias + "' removed."
                : "No credentials with alias '" + alias + "' were stored.";
    }

    @McpTool(name = "register_database",
            description = """
                    Register a PostgreSQL or Oracle database (or update an existing one) and store it in the encrypted vault \
                    so it can be used in later sessions. Credentials can be supplied in three ways, in this order of \
                    preference:
                    1. credential_alias of an already stored set (see list_credentials) when this database shares the \
                    login/password with others - usual for several dev/test databases;
                    2. username + password, which are stored under credential_alias (defaults to the database name);
                    3. neither: the server asks the user directly if the client supports elicitation, otherwise the tool \
                    returns an error telling you to ask the user.
                    Never invent credentials. Databases are read-only by default (only run_query works); pass \
                    read_only=false only when the user explicitly wants execute_statement on this database. \
                    The connection is tested after registering and the result is returned.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = true))
    public RegistrationResult registerDatabase(
            McpSyncRequestContext context,
            @McpToolParam(description = "Short name to refer to this database, e.g. 'orders-dev' (a-z, 0-9, '.', '_', '-')") String name,
            @McpToolParam(description = "JDBC URL, e.g. jdbc:postgresql://host:5432/db or jdbc:oracle:thin:@//host:1521/service") String url,
            @McpToolParam(required = false, description = "Engine: 'postgresql' or 'oracle'. Inferred from the URL when omitted") String type,
            @McpToolParam(required = false, description = "Alias of stored credentials to reuse, or the alias to store new credentials under") String credentialAlias,
            @McpToolParam(required = false, description = "Database login (only when not reusing an alias)") String username,
            @McpToolParam(required = false, description = "Database password (only when not reusing an alias)") String password,
            @McpToolParam(required = false, description = "Free-text note, e.g. 'DEV environment of the billing service'") String description,
            @McpToolParam(required = false, description = "true (default): SELECT only. false: allow execute_statement on this "
                    + "database (server must also run with db-mcp.query.allow-writes=true). Omitted on re-registration keeps the current value") Boolean readOnly) {

        DatabaseType dbType = resolveType(type, url);
        String alias = credentialAlias == null || credentialAlias.isBlank() ? name : credentialAlias;
        String source;

        boolean hasInlineCredentials = username != null && !username.isBlank();
        if (hasInlineCredentials) {
            registry.saveCredential(new Credential(alias, username, password == null ? "" : password));
            source = "stored new credentials under alias '" + alias + "'";
        } else if (registry.findCredential(alias).isPresent()) {
            source = "reused stored credentials '" + alias + "'";
        } else if (context != null && context.elicitEnabled()) {
            Credential elicited = elicitCredentials(context, alias, name, url);
            registry.saveCredential(elicited);
            source = "asked the user and stored credentials under alias '" + alias + "'";
        } else {
            throw new IllegalArgumentException("No credentials for database '" + name + "'. Ask the user for the username "
                    + "and password (or which stored alias to reuse: " + registry.listCredentials().stream()
                    .map(Credential.CredentialSummary::alias).toList() + ") and call register_database again.");
        }

        Boolean effectiveReadOnly = readOnly != null ? readOnly
                : registry.findDatabase(name).map(DatabaseDefinition::readOnly).orElse(true);
        DatabaseDefinition saved = registry.saveDatabase(
                new DatabaseDefinition(name, dbType, url, alias, description, effectiveReadOnly));
        log.info("Registered database '{}' ({}) with credentials '{}'", saved.name(), saved.type(), saved.credentialAlias());
        return new RegistrationResult(view(saved), source, probe(saved.name()));
    }

    @McpTool(name = "set_read_only",
            description = "Switch a registered database between read-only (default, only run_query) and writable "
                    + "(execute_statement allowed, if the server runs with db-mcp.query.allow-writes=true). "
                    + "Confirm with the user before making a database writable; prefer keeping production read-only.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public DatabaseView setReadOnly(
            @McpToolParam(description = "Registered database name") String name,
            @McpToolParam(description = "true = SELECT only, false = writes allowed") boolean readOnly) {
        DatabaseDefinition updated = registry.setReadOnly(name, readOnly);
        log.info("Database '{}' is now {}", updated.name(), updated.readOnly() ? "read-only" : "writable");
        return view(updated);
    }

    @McpTool(name = "remove_database",
            description = "Unregister a database and close its connection pool. Stored credentials are kept unless "
                    + "remove_credentials is called.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    public String removeDatabase(@McpToolParam(description = "Registered database name") String name) {
        return registry.removeDatabase(name)
                ? "Database '" + name + "' removed."
                : "No database named '" + name + "' is registered.";
    }

    @McpTool(name = "test_connection",
            description = "Open a connection to a registered database and report the server product/version, the "
                    + "authenticated user and the current schema. Use it to diagnose connectivity or credential problems.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = true))
    public ConnectionStatus testConnection(@McpToolParam(description = "Registered database name") String name) {
        registry.requireDatabase(name);
        return probe(name);
    }

    private ConnectionStatus probe(String name) {
        DatabaseDefinition definition = registry.requireDatabase(name);
        try (Connection c = dataSources.dataSource(definition.name()).getConnection();
             Statement s = c.createStatement()) {
            s.setQueryTimeout(10);
            s.execute(definition.type().validationQuery());
            DatabaseMetaData md = c.getMetaData();
            return new ConnectionStatus(true, md.getDatabaseProductName(), md.getDatabaseProductVersion(),
                    md.getUserName(), c.getSchema(), null);
        } catch (SQLException e) {
            dataSources.evict(definition.name());
            return new ConnectionStatus(false, null, null, null, null, ToolSupport.sqlFailure("connect", name, e).getMessage());
        } catch (ConnectionFailedException e) {
            return new ConnectionStatus(false, null, null, null, null, e.getMessage());
        }
    }

    private Credential elicitCredentials(McpSyncRequestContext context, String alias, String dbName, String url) {
        StructuredElicitResult<CredentialsForm> result = context.elicit(spec -> spec.message(
                "Enter the login and password for database '" + dbName + "' (" + url + "). "
                        + "They will be stored encrypted under alias '" + alias + "'."), CredentialsForm.class);
        if (result.action() != McpSchema.ElicitResult.Action.ACCEPT || result.structuredContent() == null) {
            throw new IllegalStateException("The user did not provide credentials for '" + dbName + "' (" + result.action()
                    + "). Ask them for the username/password and call register_database again.");
        }
        CredentialsForm form = result.structuredContent();
        return new Credential(alias, form.username(), form.password() == null ? "" : form.password());
    }

    private static DatabaseType resolveType(String type, String url) {
        Optional<DatabaseType> explicit = DatabaseType.parse(type);
        Optional<DatabaseType> fromUrl = DatabaseType.fromUrl(url);
        if (type != null && !type.isBlank() && explicit.isEmpty()) {
            throw new IllegalArgumentException("Unsupported database type '" + type + "'. Supported: postgresql, oracle.");
        }
        return explicit.or(() -> fromUrl).orElseThrow(() -> new IllegalArgumentException(
                "Cannot infer database type from URL '" + url + "'. Expected a jdbc:postgresql: or jdbc:oracle: URL."));
    }

    private DatabaseView view(DatabaseDefinition d) {
        String username = registry.findCredential(d.credentialAlias()).map(Credential::username).orElse(null);
        return new DatabaseView(d.name(), d.type(), d.url(), d.credentialAlias(), username, d.description(), d.readOnly());
    }
}
