package io.github.thescarletarrow.dbmcp.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import io.github.thescarletarrow.dbmcp.config.DbMcpProperties;
import io.github.thescarletarrow.dbmcp.registry.Credential;
import io.github.thescarletarrow.dbmcp.registry.DatabaseDefinition;
import io.github.thescarletarrow.dbmcp.registry.DatabaseRegistry;
import io.github.thescarletarrow.dbmcp.registry.RegistryEvents;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily creates one small HikariCP pool per registered database and keeps it for reuse,
 * so that several PostgreSQL and Oracle databases can be used side by side.
 */
@Component
public class DataSourceManager implements DataSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(DataSourceManager.class);

    private final DatabaseRegistry registry;
    private final DbMcpProperties.Pool poolProperties;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSourceManager(DatabaseRegistry registry, DbMcpProperties properties) {
        this.registry = registry;
        this.poolProperties = properties.pool();
    }

    @Override
    public DataSource dataSource(String databaseName) {
        DatabaseDefinition definition = registry.requireDatabase(databaseName);
        return pools.computeIfAbsent(definition.name(), n -> createPool(definition));
    }

    @Override
    public DatabaseDefinition definition(String databaseName) {
        return registry.requireDatabase(databaseName);
    }

    private HikariDataSource createPool(DatabaseDefinition definition) {
        Credential credential = registry.credentialFor(definition);
        try {
            return new HikariDataSource(hikariConfig(definition, credential));
        } catch (PoolInitializationException e) {
            throw new ConnectionFailedException(definition.name(), e);
        }
    }

    private HikariConfig hikariConfig(DatabaseDefinition definition, Credential credential) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("db-mcp-" + definition.name());
        config.setDriverClassName(definition.type().driverClassName());
        config.setJdbcUrl(definition.url());
        config.setUsername(credential.username());
        config.setPassword(credential.password());
        config.setConnectionTestQuery(definition.type().validationQuery());
        config.setMaximumPoolSize(poolProperties.maxSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(poolProperties.connectionTimeout().toMillis());
        config.setIdleTimeout(poolProperties.idleTimeout().toMillis());
        // Connect once while creating the pool so that a wrong URL/credential surfaces immediately with the
        // driver's own message instead of a generic "connection is not available" timeout later.
        config.setInitializationFailTimeout(1);
        config.setAutoCommit(false);
        config.setReadOnly(true);
        log.info("Creating connection pool for database '{}' ({})", definition.name(), definition.type());
        return config;
    }

    public void evict(String databaseName) {
        HikariDataSource pool = pools.remove(databaseName);
        if (pool != null) {
            log.info("Closing connection pool for database '{}'", databaseName);
            pool.close();
        }
    }

    @EventListener
    public void onDatabaseChanged(RegistryEvents.DatabaseChanged event) {
        evict(event.name());
    }

    @EventListener
    public void onDatabaseRemoved(RegistryEvents.DatabaseRemoved event) {
        evict(event.name());
    }

    @EventListener
    public void onCredentialChanged(RegistryEvents.CredentialChanged event) {
        registry.databasesUsing(event.alias()).forEach(this::evict);
    }

    @PreDestroy
    public void shutdown() {
        pools.keySet().forEach(this::evict);
    }
}
