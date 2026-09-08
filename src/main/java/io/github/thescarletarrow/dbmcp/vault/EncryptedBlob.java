package io.github.thescarletarrow.dbmcp.vault;

/**
 * Serialized form of an encrypted vault. All binary fields are Base64 encoded.
 *
 * @param version    format version, also used as GCM additional authenticated data
 * @param kdf        {@code pbkdf2} or {@code keyfile}
 * @param salt       PBKDF2 salt (empty for {@code keyfile})
 * @param iterations PBKDF2 iterations (0 for {@code keyfile})
 * @param iv         96-bit GCM nonce
 * @param ciphertext ciphertext including the GCM tag
 */
public record EncryptedBlob(int version, String kdf, String salt, int iterations, String iv, String ciphertext) {

    public static final int CURRENT_VERSION = 1;
}
