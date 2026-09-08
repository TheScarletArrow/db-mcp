package io.github.thescarletarrow.dbmcp.vault;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * AES-256-GCM encryption of the vault payload. The key is either derived from a master password
 * (PBKDF2-HMAC-SHA256, random salt per encryption) or read from a key file that is generated once.
 */
public final class VaultCipher {

    private static final String KDF_PBKDF2 = "pbkdf2";
    private static final String KDF_KEYFILE = "keyfile";
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64E = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final KeySource keySource;

    public VaultCipher(KeySource keySource) {
        this.keySource = keySource;
    }

    public EncryptedBlob encrypt(byte[] plaintext) {
        try {
            byte[] iv = randomBytes(IV_BYTES);
            return switch (keySource) {
                case KeySource.Password p -> {
                    byte[] salt = randomBytes(SALT_BYTES);
                    SecretKey key = deriveKey(p.password(), salt, p.iterations());
                    byte[] ct = runGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext);
                    yield new EncryptedBlob(EncryptedBlob.CURRENT_VERSION, KDF_PBKDF2, B64E.encodeToString(salt),
                            p.iterations(), B64E.encodeToString(iv), B64E.encodeToString(ct));
                }
                case KeySource.KeyFile f -> {
                    SecretKey key = loadOrCreateKeyFile(f.path());
                    byte[] ct = runGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext);
                    yield new EncryptedBlob(EncryptedBlob.CURRENT_VERSION, KDF_KEYFILE, "", 0,
                            B64E.encodeToString(iv), B64E.encodeToString(ct));
                }
            };
        } catch (GeneralSecurityException | IOException e) {
            throw new VaultException("Failed to encrypt vault", e);
        }
    }

    public byte[] decrypt(EncryptedBlob blob) {
        if (blob.version() != EncryptedBlob.CURRENT_VERSION) {
            throw new VaultException("Unsupported vault format version " + blob.version());
        }
        try {
            SecretKey key = switch (keySource) {
                case KeySource.Password p -> {
                    if (!KDF_PBKDF2.equals(blob.kdf())) {
                        throw new VaultException("Vault was encrypted with a key file, but a master password is configured");
                    }
                    yield deriveKey(p.password(), B64D.decode(blob.salt()), blob.iterations());
                }
                case KeySource.KeyFile f -> {
                    if (!KDF_KEYFILE.equals(blob.kdf())) {
                        throw new VaultException("Vault was encrypted with a master password; set DB_MCP_MASTER_PASSWORD");
                    }
                    yield loadOrCreateKeyFile(f.path());
                }
            };
            return runGcm(Cipher.DECRYPT_MODE, key, B64D.decode(blob.iv()), B64D.decode(blob.ciphertext()));
        } catch (AEADBadTagException e) {
            throw new VaultException("Vault cannot be decrypted: wrong master password/key file or the file was tampered with", e);
        } catch (GeneralSecurityException | IOException e) {
            throw new VaultException("Failed to decrypt vault", e);
        }
    }

    private static byte[] runGcm(int mode, SecretKey key, byte[] iv, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(ByteBuffer.allocate(4).putInt(EncryptedBlob.CURRENT_VERSION).array());
        return cipher.doFinal(input);
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(raw, "AES");
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static SecretKey loadOrCreateKeyFile(Path path) throws IOException {
        if (Files.exists(path)) {
            byte[] raw = B64D.decode(Files.readString(path, StandardCharsets.US_ASCII).trim());
            if (raw.length != KEY_BITS / 8) {
                throw new VaultException("Key file " + path + " does not contain a 256-bit key");
            }
            return new SecretKeySpec(raw, "AES");
        }
        Files.createDirectories(path.getParent());
        byte[] raw = randomBytes(KEY_BITS / 8);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, B64E.encodeToString(raw), StandardCharsets.US_ASCII);
        restrictToOwner(tmp);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new SecretKeySpec(raw, "AES");
    }

    static void restrictToOwner(Path path) throws IOException {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX file system (e.g. Windows): rely on the user's home directory ACLs
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
