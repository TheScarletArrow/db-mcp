package io.github.thescarletarrow.dbmcp.support;

import io.github.thescarletarrow.dbmcp.config.DbMcpProperties;

import java.nio.file.Path;
import java.time.Duration;

public final class TestProperties {

    private TestProperties() {
    }

    public static DbMcpProperties defaults(Path dir) {
        return new DbMcpProperties(
                new DbMcpProperties.Vault(dir, null, 100_000),
                new DbMcpProperties.Query(false, 200, 5000, Duration.ofSeconds(30), 2000),
                new DbMcpProperties.Pool(2, Duration.ofSeconds(1), Duration.ofMinutes(1)));
    }

    public static DbMcpProperties withWrites(Path dir) {
        DbMcpProperties base = defaults(dir);
        return new DbMcpProperties(base.vault(),
                new DbMcpProperties.Query(true, 200, 5000, Duration.ofSeconds(30), 2000), base.pool());
    }
}
