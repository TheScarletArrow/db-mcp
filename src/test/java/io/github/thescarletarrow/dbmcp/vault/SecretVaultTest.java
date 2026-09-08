package io.github.thescarletarrow.dbmcp.vault;

import io.github.thescarletarrow.dbmcp.registry.Credential;
import io.github.thescarletarrow.dbmcp.registry.DatabaseDefinition;
import io.github.thescarletarrow.dbmcp.registry.DatabaseType;
import io.github.thescarletarrow.dbmcp.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretVaultTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void emptyWhenNoFile(@TempDir Path dir) {
        SecretVault vault = new SecretVault(TestProperties.defaults(dir), mapper);
        assertThat(vault.load()).isEqualTo(VaultData.empty());
    }

    @Test
    void savesEncryptedAndLoadsBack(@TempDir Path dir) throws Exception {
        SecretVault vault = new SecretVault(TestProperties.defaults(dir), mapper);
        VaultData data = new VaultData(
                List.of(new Credential("dev", "app", "p@ss")),
                List.of(new DatabaseDefinition("orders", DatabaseType.POSTGRESQL, "jdbc:postgresql://h/orders", "dev", "dev db"),
                        new DatabaseDefinition("erp", DatabaseType.ORACLE, "jdbc:oracle:thin:@//h:1521/ERP", "dev", null)));

        vault.save(data);

        String onDisk = Files.readString(vault.location());
        assertThat(onDisk).doesNotContain("p@ss").doesNotContain("app").doesNotContain("jdbc:");
        assertThat(new SecretVault(TestProperties.defaults(dir), mapper).load()).isEqualTo(data);
        assertThat(dir.resolve("vault.enc.tmp")).doesNotExist();
    }
}
