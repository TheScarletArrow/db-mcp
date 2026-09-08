# db-mcp

MCP (Model Context Protocol) server that gives an AI assistant access to **several PostgreSQL and Oracle
databases at the same time**. Built with Spring Boot 4 / Spring AI 2 on Java 21.

* Register any number of databases; each gets its own small HikariCP pool.
* Credentials are stored **encrypted (AES-256-GCM)** in a local vault and reused across sessions, so the
  assistant asks for them only once. One login/password can be shared by many databases (typical for
  dev/test/stage environments): register the second database with the alias of the first.
* When the MCP client supports *elicitation*, the server asks the user for the login/password itself;
  otherwise the tool returns an instruction for the assistant to ask.
* Queries run read-only by default (read-only transaction, statement guard, row/cell limits, timeout).
  Writes are opt-in.
* Transports: **stdio** (default, for Claude Desktop / Claude Code / IDE clients) and **streamable HTTP**.

## Tools

| Tool | Purpose |
|------|---------|
| `list_databases` | Registered databases, their engine, URL and credential alias (never passwords). |
| `register_database` | Add/update a database. Credentials: reuse `credentialAlias`, pass `username`/`password`, or let the server elicit them. Tests the connection and reports the result. |
| `remove_database` | Unregister a database and close its pool. |
| `test_connection` | Product/version, user and current schema of a database. |
| `save_credentials` / `list_credentials` / `remove_credentials` | Manage reusable login/password sets. |
| `list_schemas`, `list_tables`, `describe_table` | Catalog exploration through JDBC metadata (works for both engines). |
| `run_query` | One read-only `SELECT`/`WITH`/`EXPLAIN` statement, capped rows, clipped long cells. |
| `execute_statement` | One DML/DDL statement, only when `DB_MCP_ALLOW_WRITES=true`. |

## Build & run

```bash
./mvnw package                      # unit + end-to-end MCP tests (H2, in-process HTTP client)
./mvnw verify                       # additionally runs Testcontainers tests if Docker is available
java -jar target/db-mcp-0.1.0-SNAPSHOT.jar                         # stdio transport
java -jar target/db-mcp-0.1.0-SNAPSHOT.jar --spring.profiles.active=http   # http://127.0.0.1:8080/mcp
```

### Claude Desktop / Claude Code (stdio)

```json
{
  "mcpServers": {
    "db-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/db-mcp-0.1.0-SNAPSHOT.jar"],
      "env": { "DB_MCP_MASTER_PASSWORD": "choose-a-strong-passphrase" }
    }
  }
}
```

For Claude Code: `claude mcp add db-mcp -e DB_MCP_MASTER_PASSWORD=... -- java -jar /path/to/db-mcp-0.1.0-SNAPSHOT.jar`.

### Typical conversation

1. "Connect to the orders DB at jdbc:postgresql://dev-host:5432/orders" → the assistant calls
   `register_database`; the server elicits (or the assistant asks for) login/password and stores them under
   alias `orders-dev`.
2. "Also add jdbc:oracle:thin:@//dev-host:1521/ERP, same login" → `register_database` with
   `credentialAlias=orders-dev`, nothing is asked again.
3. Next session: `list_databases` shows both, `run_query` works immediately.

## Configuration

| Environment variable / property | Default | Meaning |
|---------------------------------|---------|---------|
| `DB_MCP_HOME` / `db-mcp.vault.directory` | `~/.db-mcp` | Where `vault.enc` (and `vault.key`) live. |
| `DB_MCP_MASTER_PASSWORD` / `db-mcp.vault.master-password` | *(empty)* | If set, the vault key is derived with PBKDF2-HMAC-SHA256 (600k iterations, random salt). If empty, a random 256-bit key is generated once into `vault.key` (mode `0600`). Recommended: set it. |
| `DB_MCP_ALLOW_WRITES` / `db-mcp.query.allow-writes` | `false` | Enables `execute_statement`. |
| `db-mcp.query.default-max-rows` / `hard-max-rows` | `200` / `5000` | Row limits for `run_query`. |
| `db-mcp.query.timeout` | `30s` | Statement timeout. |
| `db-mcp.query.max-cell-length` | `2000` | Longer text cells are clipped in results. |
| `db-mcp.pool.max-size` / `connection-timeout` / `idle-timeout` | `4` / `10s` / `5m` | Per-database HikariCP settings. |
| `DB_MCP_PORT`, `DB_MCP_BIND` (http profile) | `8080`, `127.0.0.1` | HTTP transport binding. |

The vault file is a small JSON envelope (`kdf`, `salt`, `iv`, `ciphertext`); the whole payload (URLs,
usernames, passwords) is encrypted and authenticated, so a tampered file is rejected. Passwords never
appear in tool results or logs. Logging goes to stderr so that stdout stays a clean MCP channel.

## Project layout

```
config/      DbMcpProperties          typed settings (db-mcp.*)
vault/       VaultCipher, SecretVault  AES-GCM encryption, atomic owner-only file persistence
registry/    DatabaseRegistry          databases + credential sets, write-through to the vault
connection/  DataSourceManager         one lazily created HikariCP pool per database, evicted on change
sql/         SqlGuard, QueryExecutor   read-only guard, limits, JSON-friendly value mapping
metadata/    SchemaInspector           schemas / tables / columns / keys / indexes via DatabaseMetaData
tools/       *Tools                    @McpTool endpoints exposed to the assistant
```
