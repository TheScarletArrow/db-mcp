package io.github.thescarletarrow.dbmcp.vault;

import io.github.thescarletarrow.dbmcp.registry.Credential;
import io.github.thescarletarrow.dbmcp.registry.DatabaseDefinition;

import java.util.List;

/**
 * Plaintext content of the vault. Serialized to JSON, then encrypted as a whole so that
 * usernames and connection URLs are protected as well as passwords.
 */
public record VaultData(List<Credential> credentials, List<DatabaseDefinition> databases) {

    public static VaultData empty() {
        return new VaultData(List.of(), List.of());
    }

    public VaultData {
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
        databases = databases == null ? List.of() : List.copyOf(databases);
    }
}
