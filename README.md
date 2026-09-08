# db-mcp

MCP (Model Context Protocol) server that gives an AI assistant access to **several PostgreSQL and Oracle
databases at the same time**. Built with Spring Boot 4 / Spring AI 2 on Java 21.

* Register any number of databases; each gets its own small HikariCP pool.
* Credentials are stored **encrypted (AES-256-GCM)** in a local vault and reused across sessions, so the
  assistant asks for them only once. One login/password can be shared by many databases (typical for
  dev/test/stage environments): register the second database with the alias of the first.
* When the MCP client supports *elicitation* (Claude Code does), the server asks the user for the
  login/password itself; otherwise the tool tells the assistant to ask.
* Every database is **read-only by default** (read-only transaction, statement guard, row/cell limits,
  timeout). Writes are opt-in twice: server-wide (`DB_MCP_ALLOW_WRITES=true`) and per database
  (`readOnly=false`), so production stays SELECT-only while a dev database accepts changes.
* Transports: **stdio** (default, for Claude Desktop / Claude Code / IDE clients) and **streamable HTTP**.

Russian cheat sheet: see [Шпаргалка](#шпаргалка-ru) at the end.

## Quick start (5 minutes)

```bash
git clone https://github.com/TheScarletArrow/db-mcp && cd db-mcp
./mvnw package -DskipTests                       # -> target/db-mcp-0.1.0-SNAPSHOT.jar
export DB_MCP_MASTER_PASSWORD='choose-a-strong-passphrase'   # put it in ~/.zshrc or ~/.bashrc too

# make it available in EVERY project (scope "user"); the default scope "local" is one directory only
claude mcp add --scope user db-mcp \
  -e DB_MCP_MASTER_PASSWORD="$DB_MCP_MASTER_PASSWORD" \
  -- java -jar "$PWD/target/db-mcp-0.1.0-SNAPSHOT.jar"

claude mcp list                                  # db-mcp should be listed as connected
```

Then, inside any Claude Code session:

> Register database orders-dev: jdbc:postgresql://localhost:5432/orders

Claude Code shows a form for login/password (elicitation) or asks in chat; the server tests the
connection, stores everything encrypted in `~/.db-mcp/vault.enc`, and from now on
"how many orders were created yesterday in orders-dev?" just works, also after restarts.

## Registering databases

Registration is done **by talking to the assistant**, never by editing config files. All examples are
sentences you type in the chat; the tool the assistant calls is in brackets.

| You say | What happens |
|---------|--------------|
| "Register jdbc:postgresql://localhost:5432/orders as orders-dev" | `register_database` → login/password requested once → stored under alias `orders-dev` → connection tested. |
| "Add jdbc:oracle:thin:@//localhost:1521/ERP as erp-dev, same login as orders-dev" | `register_database` with `credentialAlias=orders-dev` → nothing is asked. Engine is inferred from the URL. |
| "Save credentials alias `dev`: user app" | `save_credentials` (password asked) → later databases can reuse `dev`. |
| "Register prod-orders at jdbc:postgresql://prod/orders, read-only" | Default anyway; `readOnly=true` is stored with the database. |
| "Allow writes on orders-dev" | `set_read_only(orders-dev, false)`. Server must also run with `DB_MCP_ALLOW_WRITES=true`. |
| "Which databases are connected?" | `list_databases` (URLs, aliases, read-only flag; never passwords). |
| "Which credentials are stored?" | `list_credentials` (alias, username, databases using it). |
| "Password for alias dev changed" | `save_credentials` overwrites it; every database using `dev` reconnects. |
| "Test the connection to erp-dev" | `test_connection` → product/version/user/schema or the driver's error. |
| "Remove erp-dev" / "Remove credentials dev" | `remove_database` / `remove_credentials` (refused while a database still uses the alias). |

URL formats:

* PostgreSQL: `jdbc:postgresql://host:5432/dbname` (`?sslmode=require` etc. as usual)
* Oracle: `jdbc:oracle:thin:@//host:1521/service_name` or `jdbc:oracle:thin:@host:1521:SID`
* From Docker, a database on your machine is `host.docker.internal`, not `localhost`.

Names and aliases: 1-64 chars of `a-z 0-9 . _ -`, case-insensitive.

## Working with data

* "Show tables in schema billing of orders-dev" → `list_tables`; "describe orders" → `describe_table`
  (columns, PK, FKs, indexes). Identifiers are case-normalized per engine; double-quote for exact match.
* "How many orders per status this month?" → `run_query`. One SELECT/WITH/EXPLAIN per call, executed in a
  read-only transaction that is rolled back, 200 rows by default (`maxRows` up to 5000), long cells
  clipped. Anything else (INSERT, DDL, `FOR UPDATE`, second statement after `;`) is rejected before it
  reaches the database.
* Writes: `execute_statement` runs one DML/DDL statement and commits, only when **both** hold:
  server started with `DB_MCP_ALLOW_WRITES=true` **and** the database has `readOnly=false`.

## Tools

| Tool | Purpose |
|------|---------|
| `list_databases` | Registered databases, engine, URL, credential alias, read-only flag (never passwords). |
| `register_database` | Add/update a database. Credentials: reuse `credentialAlias`, pass `username`/`password`, or let the server elicit them. `readOnly` (default `true`); omitted on re-registration keeps the stored value. Tests the connection. |
| `set_read_only` | Flip a database between read-only and writable without re-registering it. |
| `remove_database` | Unregister a database and close its pool. |
| `test_connection` | Product/version, user and current schema of a database. |
| `save_credentials` / `list_credentials` / `remove_credentials` | Manage reusable login/password sets. |
| `list_schemas`, `list_tables`, `describe_table` | Catalog exploration through JDBC metadata (works for both engines). |
| `run_query` | One read-only `SELECT`/`WITH`/`EXPLAIN` statement, capped rows, clipped long cells. |
| `execute_statement` | One DML/DDL statement, only when `DB_MCP_ALLOW_WRITES=true` **and** the database is not read-only. |

## Build & run

```bash
./mvnw package                      # unit + end-to-end MCP tests (H2, in-process HTTP client)
./mvnw verify                       # additionally runs Testcontainers tests if Docker is available
java -jar target/db-mcp-0.1.0-SNAPSHOT.jar                                 # stdio transport
java -jar target/db-mcp-0.1.0-SNAPSHOT.jar --spring.profiles.active=http   # http://127.0.0.1:8080/mcp
```

Requirements: Java 21+. Maven is bundled via `./mvnw`.

### Claude Code

```bash
# stdio, all projects (recommended)
claude mcp add --scope user db-mcp -e DB_MCP_MASTER_PASSWORD='...' -- java -jar /path/to/db-mcp-0.1.0-SNAPSHOT.jar
# HTTP (server or container already running)
claude mcp add --scope user --transport http db-mcp http://127.0.0.1:8080/mcp

claude mcp list                         # status
claude mcp get db-mcp                   # details
claude mcp remove --scope user db-mcp   # remove (use the scope it was added with)
```

Scopes: `local` (default) = current directory only; `user` = every project on this machine;
`project` = `.mcp.json` committed to the repo for the whole team (do **not** put the master password
there; use the HTTP variant). A `local` entry shadows a `user` entry with the same name.

### Claude Desktop (stdio)

`claude_desktop_config.json`:

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

Use the absolute path to `java` if it is not on Desktop's PATH (`which java`).

### Docker

```bash
export DB_MCP_MASTER_PASSWORD='choose-a-strong-passphrase'
docker compose up -d --build          # MCP endpoint: http://127.0.0.1:8080/mcp, health: /actuator/health
docker compose logs -f                # logs
docker compose restart                # vault (URLs, logins, passwords) is still there
docker compose down                   # stops; the volume db-mcp-data keeps the vault
docker compose down -v                # !!! also deletes the vault
```

Connect the client to the container:

```bash
claude mcp add --scope user --transport http db-mcp http://127.0.0.1:8080/mcp
```

or let the client start a container per session over stdio (same volume, so the same vault):

```bash
docker build -t db-mcp:local .
claude mcp add --scope user db-mcp -- docker run -i --rm \
  -v db-mcp-data:/data -e SPRING_PROFILES_ACTIVE= \
  -e DB_MCP_MASTER_PASSWORD='choose-a-strong-passphrase' \
  --add-host host.docker.internal:host-gateway db-mcp:local
```

Details: the image runs as uid `10001` with `DB_MCP_HOME=/data`; `compose.yaml` mounts the named volume
`db-mcp-data` there, so `vault.enc` (and `vault.key` when no master password is set) outlive container
restarts, recreation and image upgrades. For a bind mount, `chown 10001` the directory. Databases on the
Docker host are reachable as `host.docker.internal`. Enable writes with `DB_MCP_ALLOW_WRITES=true` in the
environment. Use the **same** master password for every way you start the server, they share one vault.

## Configuration

| Environment variable / property | Default | Meaning |
|---------------------------------|---------|---------|
| `DB_MCP_HOME` / `db-mcp.vault.directory` | `~/.db-mcp` (Docker: `/data`) | Where `vault.enc` (and `vault.key`) live. |
| `DB_MCP_MASTER_PASSWORD` / `db-mcp.vault.master-password` | *(empty)* | If set, the vault key is derived with PBKDF2-HMAC-SHA256 (600k iterations, random salt). If empty, a random 256-bit key is generated once into `vault.key` (mode `0600`). Recommended: set it. |
| `DB_MCP_ALLOW_WRITES` / `db-mcp.query.allow-writes` | `false` | Server-wide switch for `execute_statement`. |
| `db-mcp.query.default-max-rows` / `hard-max-rows` | `200` / `5000` | Row limits for `run_query`. |
| `db-mcp.query.timeout` | `30s` | Statement timeout. |
| `db-mcp.query.max-cell-length` | `2000` | Longer text cells are clipped in results. |
| `db-mcp.pool.max-size` / `connection-timeout` / `idle-timeout` | `4` / `10s` / `5m` | Per-database HikariCP settings. |
| `DB_MCP_PORT`, `DB_MCP_BIND` (http profile) | `8080`, `127.0.0.1` | HTTP transport binding. |
| `SPRING_PROFILES_ACTIVE=http` | *(unset = stdio)* | Selects the HTTP transport. |

Properties can be passed as `--db-mcp.query.timeout=60s` on the command line or as environment variables
in Spring's relaxed form (`DB_MCP_QUERY_TIMEOUT=60s`).

## Persistence, backup, security

* Every `register_database` / `save_credentials` / `set_read_only` call is written through to
  `vault.enc` immediately; the registry is rebuilt from that file on start-up.
* **Backup** = copy `~/.db-mcp/` (or the Docker volume: `docker run --rm -v db-mcp-data:/data -v "$PWD":/b alpine tar czf /b/db-mcp-vault.tgz -C /data .`).
  With a master password the file alone is enough; without one you also need `vault.key`.
* Losing the master password or `vault.key` makes the vault unreadable: delete `vault.enc` and register
  the databases again.
* The vault is a small JSON envelope (`kdf`, `salt`, `iv`, `ciphertext`); the whole payload (URLs,
  usernames, passwords) is encrypted and authenticated, so a tampered file is rejected. Passwords never
  appear in tool results or logs. Logging goes to stderr so that stdout stays a clean MCP channel.
* Best practice for production: create a database user with SELECT-only grants and register it under its
  own alias (e.g. `prod-ro`); the server-side read-only mode is defence in depth, not a substitute.

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `Vault cannot be decrypted: wrong master password/key file or the file was tampered with` | Different `DB_MCP_MASTER_PASSWORD` than the one the vault was written with. Use the original, or delete `vault.enc` and re-register. |
| `Vault was encrypted with a master password; set DB_MCP_MASTER_PASSWORD` | The server was started without the password the vault expects (e.g. env var missing in the client config). |
| `Vault was encrypted with a key file, but a master password is configured` | You added a master password later. Either unset it, or delete `vault.enc` and re-register. |
| `Cannot connect to database 'x': Connection refused` / `UnknownHostException` | Wrong host/port, or `localhost` used from inside Docker (use `host.docker.internal`). |
| `FATAL: password authentication failed` / `ORA-01017` | Wrong credentials: "update the password for alias dev". |
| `No credentials for database 'x'. Ask the user ...` | The assistant called `register_database` without credentials and the client has no elicitation. Tell it the login/password or which alias to reuse. |
| `Write statements are disabled ...` | Start the server with `DB_MCP_ALLOW_WRITES=true`. |
| `Database 'x' is registered as read-only ...` | "Allow writes on x" (`set_read_only`). |
| `Only read-only statements ... are allowed in run_query` | The statement is not a SELECT/WITH/EXPLAIN, or contains a second statement. Use `execute_statement` for writes. |
| Claude Code does not see the server | `claude mcp list`; check the scope (`local` vs `user`) and that `java` is on PATH. Logs go to stderr: run the jar manually to see start-up errors. |
| Query result says `truncated: true` | More rows than `maxRows`; add WHERE/LIMIT or ask for a bigger `maxRows` (max 5000). |

## Upgrading

Rebuild the jar (or `docker compose up -d --build`); the vault format is versioned and older vaults load
unchanged (databases registered before the read-only flag existed are treated as read-only).

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

## Шпаргалка (RU)

**Установка один раз**

```bash
./mvnw package -DskipTests
export DB_MCP_MASTER_PASSWORD='моя-фраза'        # добавить в ~/.zshrc или ~/.bashrc
claude mcp add --scope user db-mcp -e DB_MCP_MASTER_PASSWORD="$DB_MCP_MASTER_PASSWORD" \
  -- java -jar "$PWD/target/db-mcp-0.1.0-SNAPSHOT.jar"
claude mcp list
```

`--scope user` = во всех проектах. Без него сервер виден только в текущей папке.

**Docker вместо jar**: `docker compose up -d --build`, затем
`claude mcp add --scope user --transport http db-mcp http://127.0.0.1:8080/mcp`.
Базы на своей машине указывать как `host.docker.internal`. Данные живут в volume `db-mcp-data`,
`docker compose down -v` их удалит.

**Регистрация БД** (говорить ассистенту в чате):

* «Зарегистрируй orders-dev: jdbc:postgresql://localhost:5432/orders» → спросит логин/пароль один раз.
* «Добавь jdbc:oracle:thin:@//localhost:1521/ERP как erp-dev, креды те же, что у orders-dev» → ничего не спросит.
* «Какие базы подключены?» / «Какие креды сохранены?» / «Проверь подключение к erp-dev».
* «Пароль для алиаса orders-dev поменялся» → обновит и переподключит все базы с этим алиасом.
* «Удали erp-dev».

**Только чтение**: все базы read-only по умолчанию. Включить запись для одной базы:
«Разреши запись в orders-dev» + сервер запущен с `DB_MCP_ALLOW_WRITES=true`.
Вернуть обратно: «Сделай orders-dev только для чтения».

**Где лежит**: `~/.db-mcp/vault.enc` (в Docker `/data`). Бэкап = скопировать папку. Забыли мастер-пароль:
удалить `vault.enc` и зарегистрировать базы заново.

**Если не работает**: `claude mcp list`; запустить `java -jar ...jar` руками и посмотреть ошибки в stderr;
таблица типовых ошибок выше в разделе Troubleshooting.
