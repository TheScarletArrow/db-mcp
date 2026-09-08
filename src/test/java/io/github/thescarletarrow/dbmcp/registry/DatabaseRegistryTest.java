package io.github.thescarletarrow.dbmcp.registry;

import io.github.thescarletarrow.dbmcp.support.TestProperties;
import io.github.thescarletarrow.dbmcp.vault.SecretVault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseRegistryTest {

    @TempDir
    Path dir;

    private final List<Object> events = new ArrayList<>();
    private final ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    };

    private DatabaseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = newRegistry();
    }

    private DatabaseRegistry newRegistry() {
        return new DatabaseRegistry(new SecretVault(TestProperties.defaults(dir), JsonMapper.builder().build()), publisher);
    }

    @Test
    void sharedCredentialsAcrossDatabasesAndPersistence() {
        registry.saveCredential(new Credential("Dev", "app", "pw"));
        registry.saveDatabase(new DatabaseDefinition("orders-dev", DatabaseType.POSTGRESQL, "jdbc:postgresql://a/orders", "dev", ""));
        registry.saveDatabase(new DatabaseDefinition("erp-dev", DatabaseType.ORACLE, "jdbc:oracle:thin:@//b:1521/ERP", "dev", ""));

        assertThat(registry.databasesUsing("dev")).containsExactly("erp-dev", "orders-dev");
        assertThat(registry.listCredentials()).extracting(Credential.CredentialSummary::alias).containsExactly("dev");

        DatabaseRegistry reloaded = newRegistry();
        assertThat(reloaded.listDatabases()).extracting(DatabaseDefinition::name).containsExactly("erp-dev", "orders-dev");
        assertThat(reloaded.credentialFor(reloaded.requireDatabase("ERP-DEV")).password()).isEqualTo("pw");
        assertThat(events).containsExactly(
                new RegistryEvents.CredentialChanged("dev"),
                new RegistryEvents.DatabaseChanged("orders-dev"),
                new RegistryEvents.DatabaseChanged("erp-dev"));
    }

    @Test
    void readOnlyByDefaultAndToggleIsPersisted() {
        registry.saveCredential(new Credential("dev", "app", "pw"));
        registry.saveDatabase(new DatabaseDefinition("x", DatabaseType.POSTGRESQL, "jdbc:postgresql://a/x", "dev", ""));
        assertThat(registry.requireDatabase("x").readOnly()).isTrue();

        registry.setReadOnly("x", false);
        assertThat(newRegistry().requireDatabase("x").readOnly()).isFalse();
        assertThatThrownBy(() -> registry.setReadOnly("missing", false)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void vaultsWrittenBeforeTheFlagExistedLoadAsReadOnly() {
        String legacyJson = "{\"name\":\"old\",\"type\":\"ORACLE\",\"url\":\"jdbc:oracle:thin:@//h/S\",\"credentialAlias\":\"dev\",\"description\":\"\"}";
        DatabaseDefinition legacy = JsonMapper.builder().build().readValue(legacyJson, DatabaseDefinition.class);
        assertThat(legacy.readOnly()).isTrue();
    }

    @Test
    void databaseNeedsExistingCredential() {
        assertThatThrownBy(() -> registry.saveDatabase(
                new DatabaseDefinition("x", DatabaseType.POSTGRESQL, "jdbc:postgresql://a/x", "nope", "")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void credentialInUseCannotBeRemoved() {
        registry.saveCredential(new Credential("dev", "app", "pw"));
        registry.saveDatabase(new DatabaseDefinition("x", DatabaseType.POSTGRESQL, "jdbc:postgresql://a/x", "dev", ""));

        assertThatThrownBy(() -> registry.removeCredential("dev")).isInstanceOf(IllegalStateException.class).hasMessageContaining("x");
        assertThat(registry.removeDatabase("x")).isTrue();
        assertThat(registry.removeCredential("dev")).isTrue();
        assertThat(registry.removeCredential("dev")).isFalse();
    }

    @Test
    void urlMustMatchEngine() {
        assertThatThrownBy(() -> new DatabaseDefinition("x", DatabaseType.ORACLE, "jdbc:postgresql://a/x", "dev", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbc:oracle:");
        assertThatThrownBy(() -> new Credential("bad alias!", "u", "p")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typeParsingAndInference() {
        assertThat(DatabaseType.parse("pg")).contains(DatabaseType.POSTGRESQL);
        assertThat(DatabaseType.parse("Oracle")).contains(DatabaseType.ORACLE);
        assertThat(DatabaseType.parse("mysql")).isEmpty();
        assertThat(DatabaseType.fromUrl("jdbc:oracle:thin:@//h:1521/S")).contains(DatabaseType.ORACLE);
        assertThat(DatabaseType.fromUrl("jdbc:postgresql://h/d")).contains(DatabaseType.POSTGRESQL);
        assertThat(DatabaseType.fromUrl("jdbc:mysql://h/d")).isEmpty();
    }
}
