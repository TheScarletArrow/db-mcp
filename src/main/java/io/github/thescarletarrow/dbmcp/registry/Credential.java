package io.github.thescarletarrow.dbmcp.registry;

/**
 * A reusable login/password pair. Several databases (e.g. dev/test/stage) can share one alias.
 */
public record Credential(String alias, String username, String password) {

    public Credential {
        alias = Names.normalize(alias, "credential alias");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }
    }

    /** Representation that is safe to return to the model: no password. */
    public CredentialSummary summary() {
        return new CredentialSummary(alias, username);
    }

    public record CredentialSummary(String alias, String username) {
    }
}
