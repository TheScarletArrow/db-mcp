package io.github.thescarletarrow.dbmcp.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultCipherTest {

    private static final byte[] SECRET = "{\"password\":\"s3cr3t\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void passwordRoundTrip() {
        VaultCipher cipher = new VaultCipher(new KeySource.Password("master".toCharArray(), 100_000));
        EncryptedBlob blob = cipher.encrypt(SECRET);

        assertThat(blob.kdf()).isEqualTo("pbkdf2");
        assertThat(blob.salt()).isNotEmpty();
        assertThat(new String(Base64.getDecoder().decode(blob.ciphertext()), StandardCharsets.ISO_8859_1)).doesNotContain("s3cr3t");
        assertThat(cipher.decrypt(blob)).isEqualTo(SECRET);
    }

    @Test
    void eachEncryptionUsesFreshSaltAndNonce() {
        VaultCipher cipher = new VaultCipher(new KeySource.Password("master".toCharArray(), 100_000));
        EncryptedBlob a = cipher.encrypt(SECRET);
        EncryptedBlob b = cipher.encrypt(SECRET);
        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.salt()).isNotEqualTo(b.salt());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }

    @Test
    void wrongPasswordIsRejected() {
        EncryptedBlob blob = new VaultCipher(new KeySource.Password("right".toCharArray(), 100_000)).encrypt(SECRET);
        VaultCipher wrong = new VaultCipher(new KeySource.Password("wrong".toCharArray(), 100_000));
        assertThatThrownBy(() -> wrong.decrypt(blob)).isInstanceOf(VaultException.class).hasMessageContaining("wrong master password");
    }

    @Test
    void tamperedCiphertextIsRejected() {
        VaultCipher cipher = new VaultCipher(new KeySource.Password("master".toCharArray(), 100_000));
        EncryptedBlob blob = cipher.encrypt(SECRET);
        byte[] ct = Base64.getDecoder().decode(blob.ciphertext());
        ct[0] ^= 0x01;
        EncryptedBlob tampered = new EncryptedBlob(blob.version(), blob.kdf(), blob.salt(), blob.iterations(), blob.iv(),
                Base64.getEncoder().encodeToString(ct));
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(VaultException.class);
    }

    @Test
    void keyFileIsGeneratedOnceWithOwnerOnlyPermissions(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("sub").resolve("vault.key");
        VaultCipher cipher = new VaultCipher(new KeySource.KeyFile(keyFile));

        EncryptedBlob blob = cipher.encrypt(SECRET);
        assertThat(keyFile).exists();
        assertThat(blob.kdf()).isEqualTo("keyfile");
        String key = Files.readString(keyFile);
        assertThat(Base64.getDecoder().decode(key.trim())).hasSize(32);
        if (Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(keyFile))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }

        // a second cipher instance reuses the same key file
        assertThat(new VaultCipher(new KeySource.KeyFile(keyFile)).decrypt(blob)).isEqualTo(SECRET);
        assertThat(Files.readString(keyFile)).isEqualTo(key);
    }

    @Test
    void mismatchedKeySourceGivesActionableError(@TempDir Path dir) {
        EncryptedBlob blob = new VaultCipher(new KeySource.Password("pw".toCharArray(), 100_000)).encrypt(SECRET);
        VaultCipher keyFile = new VaultCipher(new KeySource.KeyFile(dir.resolve("vault.key")));
        assertThatThrownBy(() -> keyFile.decrypt(blob)).hasMessageContaining("DB_MCP_MASTER_PASSWORD");
    }
}
