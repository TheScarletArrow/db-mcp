package io.github.thescarletarrow.dbmcp.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Application settings, bound from the {@code db-mcp.*} namespace.
 */
@Validated
@ConfigurationProperties(prefix = "db-mcp")
public record DbMcpProperties(@NotNull Vault vault, @NotNull Query query, @NotNull Pool pool) {

    /**
     * @param directory      directory holding the encrypted vault and (optionally) the generated key file
     * @param masterPassword optional master password; when set, the vault key is derived from it with PBKDF2.
     *                       When empty, a random key is generated once and stored in {@code directory/vault.key}.
     * @param pbkdf2Iterations PBKDF2 iteration count used when a master password is configured
     */
    public record Vault(@NotNull Path directory,
                        String masterPassword,
                        @DefaultValue("600000") @Min(100_000) int pbkdf2Iterations) {

        public boolean hasMasterPassword() {
            return masterPassword != null && !masterPassword.isBlank();
        }
    }

    /**
     * @param allowWrites      whether {@code execute_statement} (DML/DDL) is enabled at all
     * @param defaultMaxRows   row limit applied when a tool call does not specify one
     * @param hardMaxRows      absolute upper bound on rows returned by a single call
     * @param timeout          statement timeout
     * @param maxCellLength    strings longer than this are truncated in results to protect the model context
     */
    public record Query(@DefaultValue("false") boolean allowWrites,
                        @DefaultValue("200") @Min(1) int defaultMaxRows,
                        @DefaultValue("5000") @Min(1) int hardMaxRows,
                        @DefaultValue("30s") Duration timeout,
                        @DefaultValue("2000") @Min(16) int maxCellLength) {
    }

    /**
     * Per-database HikariCP pool settings.
     */
    public record Pool(@DefaultValue("4") @Min(1) int maxSize,
                       @DefaultValue("10s") Duration connectionTimeout,
                       @DefaultValue("5m") Duration idleTimeout) {
    }
}
