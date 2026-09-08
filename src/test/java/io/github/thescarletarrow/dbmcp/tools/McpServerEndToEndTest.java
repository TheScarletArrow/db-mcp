package io.github.thescarletarrow.dbmcp.tools;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import io.github.thescarletarrow.dbmcp.config.DbMcpProperties;
import io.github.thescarletarrow.dbmcp.registry.DatabaseDefinition;
import io.github.thescarletarrow.dbmcp.registry.DatabaseRegistry;
import io.github.thescarletarrow.dbmcp.vault.SecretVault;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the server with the streamable-http transport and drives it with the official MCP client,
 * including the elicitation round trip for credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("http")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpServerEndToEndTest {

    static Path vaultDir;

    @DynamicPropertySource
    static void vault(DynamicPropertyRegistry registry) throws Exception {
        vaultDir = Files.createTempDirectory("db-mcp-e2e");
        registry.add("db-mcp.vault.directory", () -> vaultDir.toString());
        registry.add("db-mcp.pool.connection-timeout", () -> "1s");
    }

    @LocalServerPort
    int port;

    @Autowired
    DbMcpProperties properties;

    private final JsonMapper json = JsonMapper.builder().build();
    private McpSyncClient client;

    @BeforeAll
    void connect() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
                .elicitation(request -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                        Map.of("username", "elicited_user", "password", "elicited_pw")))
                .build();
        client.initialize();
    }

    @AfterAll
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void exposesAllTools() {
        List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
        assertThat(names).containsExactlyInAnyOrder("list_databases", "list_credentials", "save_credentials",
                "remove_credentials", "register_database", "remove_database", "set_read_only", "test_connection", "list_schemas",
                "list_tables", "describe_table", "run_query", "execute_statement");

        McpSchema.Tool runQuery = client.listTools().tools().stream().filter(t -> t.name().equals("run_query")).findFirst().orElseThrow();
        assertThat(runQuery.annotations().readOnlyHint()).isTrue();
        assertThat(runQuery.inputSchema().get("required")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("database", "sql");
    }

    @Test
    void registersDatabasesSharingCredentialsAndPersistsThem() throws Exception {
        McpSchema.CallToolResult first = client.callTool(new McpSchema.CallToolRequest("register_database", Map.of(
                "name", "orders-dev", "url", "jdbc:postgresql://127.0.0.1:1/orders",
                "credentialAlias", "dev", "username", "app", "password", "pw")));
        assertThat(first.isError()).isFalse();
        Map<?, ?> firstBody = json.readValue(text(first), Map.class);
        assertThat(firstBody.get("credentialsSource")).asString().contains("stored new credentials under alias 'dev'");
        assertThat(((Map<?, ?>) firstBody.get("connection")).get("ok")).isEqualTo(false);

        McpSchema.CallToolResult second = client.callTool(new McpSchema.CallToolRequest("register_database", Map.of(
                "name", "erp-dev", "url", "jdbc:oracle:thin:@//127.0.0.1:1/ERP", "credentialAlias", "dev")));
        assertThat(second.isError()).isFalse();
        assertThat(json.readValue(text(second), Map.class).get("credentialsSource")).asString().contains("reused");

        McpSchema.CallToolResult credentials = client.callTool(new McpSchema.CallToolRequest("list_credentials", Map.of()));
        assertThat(text(credentials)).contains("\"alias\":\"dev\"").contains("erp-dev").contains("orders-dev").doesNotContain("pw");

        assertThat(Files.readString(vaultDir.resolve("vault.enc"))).doesNotContain("pw").doesNotContain("jdbc:");
    }

    @Test
    void restartedServerReloadsUrlsLoginsAndPasswords() {
        client.callTool(new McpSchema.CallToolRequest("register_database", Map.of(
                "name", "restart-pg", "url", "jdbc:postgresql://127.0.0.1:1/restart",
                "credentialAlias", "restart-creds", "username", "keep_me", "password", "keep_me_too")));
        client.callTool(new McpSchema.CallToolRequest("register_database", Map.of(
                "name", "restart-ora", "url", "jdbc:oracle:thin:@//127.0.0.1:1/RESTART", "credentialAlias", "restart-creds")));

        // Simulates a process restart: a brand-new vault + registry over the same directory, nothing in memory.
        DatabaseRegistry reloaded = new DatabaseRegistry(new SecretVault(properties, json), event -> { });

        DatabaseDefinition pg = reloaded.requireDatabase("restart-pg");
        DatabaseDefinition ora = reloaded.requireDatabase("restart-ora");
        assertThat(pg.url()).isEqualTo("jdbc:postgresql://127.0.0.1:1/restart");
        assertThat(ora.url()).isEqualTo("jdbc:oracle:thin:@//127.0.0.1:1/RESTART");
        assertThat(reloaded.credentialFor(pg).username()).isEqualTo("keep_me");
        assertThat(reloaded.credentialFor(pg).password()).isEqualTo("keep_me_too");
        assertThat(reloaded.credentialFor(ora)).isEqualTo(reloaded.credentialFor(pg));
    }

    @Test
    void readOnlyFlagPerDatabase() throws Exception {
        McpSchema.CallToolResult writable = client.callTool(new McpSchema.CallToolRequest("register_database", Map.of(
                "name", "ro-test", "url", "jdbc:postgresql://127.0.0.1:1/ro", "username", "u", "password", "p",
                "readOnly", false)));
        assertThat(writable.isError()).isFalse();
        assertThat(((Map<?, ?>) json.readValue(text(writable), Map.class).get("database")).get("readOnly")).isEqualTo(false);

        // re-registering without the flag keeps the stored value
        McpSchema.CallToolResult again = client.callTool(new McpSchema.CallToolRequest("register_database", Map.of(
                "name", "ro-test", "url", "jdbc:postgresql://127.0.0.1:1/ro2", "credentialAlias", "ro-test")));
        assertThat(((Map<?, ?>) json.readValue(text(again), Map.class).get("database")).get("readOnly")).isEqualTo(false);

        McpSchema.CallToolResult toggled = client.callTool(new McpSchema.CallToolRequest("set_read_only", Map.of(
                "name", "ro-test", "readOnly", true)));
        assertThat(toggled.isError()).isFalse();
        assertThat(json.readValue(text(toggled), Map.class).get("readOnly")).isEqualTo(true);

        McpSchema.CallToolResult listed = client.callTool(new McpSchema.CallToolRequest("list_databases", Map.of()));
        assertThat(text(listed)).contains("\"name\":\"ro-test\"").contains("\"readOnly\":true");
    }

    @Test
    void elicitsCredentialsWhenNoneGiven() throws Exception {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("register_database", Map.of(
                "name", "reporting", "url", "jdbc:postgresql://127.0.0.1:1/reporting")));
        assertThat(result.isError()).isFalse();
        Map<?, ?> body = json.readValue(text(result), Map.class);
        assertThat(body.get("credentialsSource")).asString().contains("asked the user");
        assertThat(((Map<?, ?>) body.get("database")).get("username")).isEqualTo("elicited_user");
    }

    @Test
    void unknownDatabaseIsAToolError() {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("run_query", Map.of(
                "database", "missing", "sql", "select 1")));
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Unknown database 'missing'").contains("register_database");
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst()
                .orElseThrow();
    }
}
