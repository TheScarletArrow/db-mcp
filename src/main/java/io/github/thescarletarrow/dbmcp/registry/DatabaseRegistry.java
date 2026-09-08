package io.github.thescarletarrow.dbmcp.registry;

import io.github.thescarletarrow.dbmcp.vault.SecretVault;
import io.github.thescarletarrow.dbmcp.vault.VaultData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * In-memory view of registered databases and credentials, backed by the encrypted vault.
 * Every mutation is written through to disk immediately.
 */
@Service
public class DatabaseRegistry {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRegistry.class);

    private final SecretVault vault;
    private final ApplicationEventPublisher events;
    private final Map<String, Credential> credentials = new LinkedHashMap<>();
    private final Map<String, DatabaseDefinition> databases = new LinkedHashMap<>();

    public DatabaseRegistry(SecretVault vault, ApplicationEventPublisher events) {
        this.vault = vault;
        this.events = events;
        VaultData data = vault.load();
        data.credentials().forEach(c -> credentials.put(c.alias(), c));
        data.databases().forEach(d -> databases.put(d.name(), d));
        log.info("Loaded {} credential set(s) and {} database(s) from {}", credentials.size(), databases.size(), vault.location());
    }

    // ---- credentials -------------------------------------------------------------------------

    public synchronized Credential saveCredential(Credential credential) {
        credentials.put(credential.alias(), credential);
        persist();
        events.publishEvent(new RegistryEvents.CredentialChanged(credential.alias()));
        return credential;
    }

    public synchronized Optional<Credential> findCredential(String alias) {
        return Optional.ofNullable(credentials.get(Names.normalize(alias, "credential alias")));
    }

    public synchronized List<Credential.CredentialSummary> listCredentials() {
        return credentials.values().stream()
                .sorted(Comparator.comparing(Credential::alias))
                .map(Credential::summary)
                .toList();
    }

    /** Names of databases that authenticate with the given alias. */
    public synchronized List<String> databasesUsing(String alias) {
        String normalized = Names.normalize(alias, "credential alias");
        return databases.values().stream()
                .filter(d -> d.credentialAlias().equals(normalized))
                .map(DatabaseDefinition::name)
                .sorted()
                .toList();
    }

    public synchronized boolean removeCredential(String alias) {
        String normalized = Names.normalize(alias, "credential alias");
        List<String> users = databasesUsing(normalized);
        if (!users.isEmpty()) {
            throw new IllegalStateException("Credential '" + normalized + "' is still used by: " + users
                    + ". Remove those databases or point them to another credential first.");
        }
        boolean removed = credentials.remove(normalized) != null;
        if (removed) {
            persist();
            events.publishEvent(new RegistryEvents.CredentialChanged(normalized));
        }
        return removed;
    }

    // ---- databases ---------------------------------------------------------------------------

    public synchronized DatabaseDefinition saveDatabase(DatabaseDefinition definition) {
        if (!credentials.containsKey(definition.credentialAlias())) {
            throw new NoSuchElementException("Unknown credential alias '" + definition.credentialAlias()
                    + "'. Known aliases: " + credentials.keySet());
        }
        databases.put(definition.name(), definition);
        persist();
        events.publishEvent(new RegistryEvents.DatabaseChanged(definition.name()));
        return definition;
    }

    public synchronized Optional<DatabaseDefinition> findDatabase(String name) {
        return Optional.ofNullable(databases.get(Names.normalize(name, "database name")));
    }

    public synchronized DatabaseDefinition requireDatabase(String name) {
        return findDatabase(name).orElseThrow(() -> new NoSuchElementException(
                "Unknown database '" + name + "'. Registered databases: " + databases.keySet()
                        + ". Use register_database to add it."));
    }

    /** Resolves the credential a database authenticates with. */
    public synchronized Credential credentialFor(DatabaseDefinition definition) {
        Credential credential = credentials.get(definition.credentialAlias());
        if (credential == null) {
            throw new IllegalStateException("Database '" + definition.name() + "' references missing credential '"
                    + definition.credentialAlias() + "'. Call save_credentials to restore it.");
        }
        return credential;
    }

    public synchronized List<DatabaseDefinition> listDatabases() {
        return databases.values().stream().sorted(Comparator.comparing(DatabaseDefinition::name)).toList();
    }

    public synchronized boolean removeDatabase(String name) {
        String normalized = Names.normalize(name, "database name");
        boolean removed = databases.remove(normalized) != null;
        if (removed) {
            persist();
            events.publishEvent(new RegistryEvents.DatabaseRemoved(normalized));
        }
        return removed;
    }

    private void persist() {
        vault.save(new VaultData(new ArrayList<>(credentials.values()), new ArrayList<>(databases.values())));
    }
}
