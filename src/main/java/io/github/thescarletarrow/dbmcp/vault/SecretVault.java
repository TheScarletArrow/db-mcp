package io.github.thescarletarrow.dbmcp.vault;

import io.github.thescarletarrow.dbmcp.config.DbMcpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persists {@link VaultData} as an encrypted JSON file. Writes are atomic (temp file + move)
 * and the resulting file is readable by the owner only.
 */
@Component
public class SecretVault {

    private static final Logger log = LoggerFactory.getLogger(SecretVault.class);
    private static final String VAULT_FILE = "vault.enc";
    private static final String KEY_FILE = "vault.key";

    private final Path vaultFile;
    private final VaultCipher cipher;
    private final JsonMapper mapper;

    @Autowired
    public SecretVault(DbMcpProperties properties, JsonMapper mapper) {
        this(properties.vault().directory().resolve(VAULT_FILE), new VaultCipher(keySource(properties.vault())), mapper);
    }

    SecretVault(Path vaultFile, VaultCipher cipher, JsonMapper mapper) {
        this.vaultFile = vaultFile;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    private static KeySource keySource(DbMcpProperties.Vault vault) {
        if (vault.hasMasterPassword()) {
            return new KeySource.Password(vault.masterPassword().toCharArray(), vault.pbkdf2Iterations());
        }
        return new KeySource.KeyFile(vault.directory().resolve(KEY_FILE));
    }

    public Path location() {
        return vaultFile;
    }

    public synchronized VaultData load() {
        if (!Files.exists(vaultFile)) {
            return VaultData.empty();
        }
        try {
            EncryptedBlob blob = mapper.readValue(Files.readString(vaultFile, StandardCharsets.UTF_8), EncryptedBlob.class);
            byte[] plain = cipher.decrypt(blob);
            return mapper.readValue(plain, VaultData.class);
        } catch (IOException e) {
            throw new VaultException("Failed to read vault " + vaultFile, e);
        }
    }

    public synchronized void save(VaultData data) {
        try {
            Files.createDirectories(vaultFile.getParent());
            byte[] plain = mapper.writeValueAsBytes(data);
            EncryptedBlob blob = cipher.encrypt(plain);
            Path tmp = vaultFile.resolveSibling(VAULT_FILE + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(blob), StandardCharsets.UTF_8);
            VaultCipher.restrictToOwner(tmp);
            Files.move(tmp, vaultFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Vault saved to {}", vaultFile);
        } catch (IOException e) {
            throw new VaultException("Failed to write vault " + vaultFile, e);
        }
    }
}
