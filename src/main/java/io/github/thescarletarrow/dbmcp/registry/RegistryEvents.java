package io.github.thescarletarrow.dbmcp.registry;

/**
 * Events published when the registry changes, so that connection pools can be refreshed.
 */
public sealed interface RegistryEvents {

    record DatabaseChanged(String name) implements RegistryEvents {
    }

    record DatabaseRemoved(String name) implements RegistryEvents {
    }

    record CredentialChanged(String alias) implements RegistryEvents {
    }
}
