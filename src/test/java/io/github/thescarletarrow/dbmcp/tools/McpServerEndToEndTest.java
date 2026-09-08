package io.github.thescarletarrow.dbmcp.tools;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
                "remove_credentials", "register_database", "remove_database", "test_connection", "list_schemas",
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
