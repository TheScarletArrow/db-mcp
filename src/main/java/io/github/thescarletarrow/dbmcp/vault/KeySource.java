package io.github.thescarletarrow.dbmcp.vault;

import java.nio.file.Path;

/**
 * Where the vault encryption key comes from.
 */
public sealed interface KeySource {

    /** Key derived from a user supplied master password with PBKDF2-HMAC-SHA256. */
    record Password(char[] password, int iterations) implements KeySource {
    }

    /** Raw 256-bit key stored in a file with owner-only permissions; generated on first use. */
    record KeyFile(Path path) implements KeySource {
    }
}
