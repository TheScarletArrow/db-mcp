# db-mcp

[![Publish Docker image](https://github.com/TheScarletArrow/db-mcp/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/TheScarletArrow/db-mcp/actions/workflows/docker-publish.yml)

MCP (Model Context Protocol) server that gives an AI assistant access to **several PostgreSQL, Oracle and ClickHouse
databases at the same time**. Built with Spring Boot 4 / Spring AI 2 on Java 21.

* Register any number of databases; each gets its own small HikariCP pool.
* Credentials are stored **encrypted (AES-256-GCM)** in a local vault and reused across sessions, so the
  assistant asks for them only once. One login/password can be shared by many databases (typical for
  dev/test/stage environments): register the second database with the alias of the first.
* When the MCP client supports *elicitation* (Claude Code does), the server asks the user for the
  login/password itself; otherwise the tool tells the assistant to ask.
* Every database is **read-only by default** and a read-only database **cannot be written to at all**:
  the statement guard rejects DML/DDL, `SELECT ... INTO`, write-through-SELECT routines (`nextval`,
  `lo_import`, `dblink`, ClickHouse `url`/`s3`/`file`, `DBMS_*`/`UTL_*`, ...) and statements smuggled in
  after a `;`. On transactional engines the transaction is declared read-only on the server and rolled back.
  Writes are opt-in twice: server-wide (`DB_MCP_ALLOW_WRITES=true`) and per database (`readOnly=false`), so
  production stays SELECT-only while a dev database accepts changes. Details: [Read-only enforcement](#read-only-enforcement).
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
| "Add ClickHouse analytics at jdbc:ch://localhost:8123/default, same login as orders-dev" | `register_database` with `credentialAlias=orders-dev`; engine is inferred from the URL. |
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
* ClickHouse: `jdbc:ch://host:8123/database` or `jdbc:clickhouse:http://host:8123/database`
  (`compress=false` is added automatically for driver compatibility unless the URL already sets `compress`)
* From Docker, a database on your machine is `host.docker.internal`, not `localhost`.

Names and aliases: 1-64 chars of `a-z 0-9 . _ -`, starting with a letter or a digit, case-insensitive
(`Orders-DEV` and `orders-dev` are the same database). `register_database` called without a
`credentialAlias` stores the credentials under the database name, so the next database can reuse them by
that name.

## Working with data

* "Which schemas are in orders-dev?" → `list_schemas`. System schemas/databases (`pg_catalog`,
  `information_schema`, `SYS`, `APEX_*`, ClickHouse `system`, ...) are hidden unless explicitly asked for.
* "Show tables in schema billing of orders-dev" → `list_tables` (LIKE pattern such as `ord%`, views included
  by default, 200 tables per call and up to 2000 on request; `truncated` tells you to narrow the pattern);
  "describe orders" → `describe_table` (columns with type/nullability/default, PK, FKs, indexes).
  Identifiers are case-normalized per engine; double-quote for exact match.
* "How many orders per status this month?" → `run_query`. One read statement
  (`SELECT`/`WITH`/`EXPLAIN`/`SHOW`/`VALUES`/`TABLE`) per call, executed read-only and rolled back on
  transactional engines, 200 rows by default (`maxRows` up to 5000), long cells clipped. The result carries
  `columns`, `columnTypes`, `rows`, `rowCount`, `truncated` and `executionMillis`. Anything else (INSERT,
  DDL, `FOR UPDATE`, `SELECT ... INTO`, second statement after `;`) is rejected before it reaches the
  database - see [Read-only enforcement](#read-only-enforcement).
* Writes: `execute_statement` runs one DML/DDL statement, commits and returns `affectedRows`, only when
  **both** hold: server started with `DB_MCP_ALLOW_WRITES=true` **and** the database has `readOnly=false`.

## Read-only enforcement

A database registered with `readOnly=true` (the default) accepts **no** insert, update, delete or DDL,
through any tool and any wording of the statement. Three independent fences, because no single one holds
on every engine:

1. **The statement guard** (`SqlGuard`) - the only fence that works on all of them. `run_query` accepts a
   single statement starting with `SELECT`, `WITH`, `EXPLAIN`, `SHOW`, `VALUES`, `TABLE` or
   `DESCRIBE`/`DESC`, and rejects everything else *before* the database sees it:
   * DML/DDL and transaction control, including data-modifying CTEs (`WITH x AS (DELETE ... RETURNING ...)`)
     and locking reads (`FOR UPDATE`);
   * `SELECT ... INTO` and `INTO OUTFILE`, which create or fill a table while looking like a query;
   * routines that write or reach outside the database even inside a plain `SELECT`: sequence bumps
     (`nextval`, `setval`), large objects and files (`lo_import`, `lo_export`, `pg_read_file`, H2's
     `CSVWRITE`/`FILE_WRITE`), server state (`pg_terminate_backend`, `pg_reload_conf`), sleeps and advisory
     locks, calls that execute SQL passed to them as text (`dblink_exec`, `query_to_xml`), ClickHouse table
     functions that read files, call networks or run executables (`url`, `s3`, `file`, `remote`,
     `executable`, ...), and every Oracle `DBMS_*` / `UTL_*` / `OWA_*` package;
   * a second statement after a `;`, including when it is hidden in a comment, a dollar-quoted string or
     behind a backslash-escaped quote. String literals, quoted identifiers, `$tag$...$tag$`, Oracle
     `q'{...}'` and comments are blanked out before keywords are scanned, so `'drop me'` is data, not a
     verb - and anything that cannot be lexed unambiguously (an unterminated literal or comment, a
     backslash in front of a closing quote) is rejected rather than guessed.
2. **A read-only transaction on the server where the engine supports it** - `run_query` issues `SET TRANSACTION READ ONLY` and always
   rolls back. On PostgreSQL the server itself then refuses any write, which is what catches the one thing
   no name-based check can see: a user-defined function that writes when it is selected from. Engines that
   do not know the statement fall back to the other two fences (a one-off `WARN` line says so), and
   `Connection.setReadOnly(true)` is set on every query when the driver accepts it - Oracle and H2 ignore it,
   PostgreSQL does not. ClickHouse has no equivalent transaction fence here, so `run_query` relies on the SQL
   guard plus the driver's read-only hint.
3. **The per-database flag** - `execute_statement` checks `readOnly` before it even looks at the SQL, so a
   read-only database never has a write statement built for it. Flip it with `set_read_only` (and the
   server must also run with `DB_MCP_ALLOW_WRITES=true`).

The guard is deliberately blunt: a column literally named `update`, `load` or `into` has to be quoted
(`SELECT "into" FROM audit`), and read-only Oracle package functions such as `DBMS_LOB.getlength` are
refused together with the writing ones. The single keyword judged in context is `ANALYZE`: allowed after
`EXPLAIN`, because there it runs the plan of a read, refused on its own, where it rewrites statistics.
Rejections carry a message that says what to do instead.

None of this replaces a SELECT-only database user for anything that matters (see
[Persistence, backup, security](#persistence-backup-security)).

## Tools

Optional parameters are marked `?`.

| Tool | Parameters | Purpose |
|------|------------|---------|
| `list_databases` | - | Registered databases, engine, URL, credential alias, username, read-only flag (never passwords). |
| `register_database` | `name`, `url`, `type?`, `credentialAlias?`, `username?`, `password?`, `description?`, `readOnly?` | Add/update a PostgreSQL, Oracle or ClickHouse database. Credentials: reuse `credentialAlias`, pass `username`/`password`, or let the server elicit them. `type` is inferred from the URL when omitted. `readOnly` defaults to `true`; omitted on re-registration it keeps the stored value. Tests the connection and returns the result. |
| `set_read_only` | `name`, `readOnly` | Flip a database between read-only and writable without re-registering it. |
| `remove_database` | `name` | Unregister a database and close its pool. Stored credentials are kept. |
| `test_connection` | `name` | Product/version, authenticated user and current schema of a database. |
| `save_credentials` | `alias`, `username`, `password` | Store or update a login/password set; every database using the alias reconnects. |
| `list_credentials` | - | Alias, username and the databases using each set (never passwords). |
| `remove_credentials` | `alias` | Delete a set; refused while a registered database still uses it. |
| `list_schemas` | `database`, `includeSystem?` | Schemas of a database; system schemas hidden by default. |
| `list_tables` | `database`, `schema?`, `namePattern?`, `includeViews?`, `limit?` | Tables and views of a schema (current schema when omitted), LIKE pattern, 200 per call and 2000 max. |
| `describe_table` | `database`, `table`, `schema?` | Columns with type/nullability/default, primary key, foreign keys and indexes. |
| `run_query` | `database`, `sql`, `maxRows?` | One read-only statement, capped rows, clipped long cells. Transactional engines use a rolled-back read-only transaction. |
| `execute_statement` | `database`, `sql` | One DML/DDL statement, only when `DB_MCP_ALLOW_WRITES=true` **and** the database is not read-only. |

Every tool carries MCP tool annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`,
`openWorldHint`), so a client that surfaces them can tell a catalog lookup from a write before calling.

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
# HTTP (server or container already running; replace 8080 with your DB_MCP_PORT)
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

### Docker

```bash
cp .env.example .env                  # put your master password there, then:
docker compose up -d                  # MCP endpoint: http://127.0.0.1:8080/mcp, health: /actuator/health
docker compose logs -f                # logs
docker compose restart                # vault (URLs, logins, passwords) is still there
docker compose down                   # stops; the volume db-mcp-data keeps the vault
docker compose down -v                # !!! also deletes the vault
```

`docker compose up -d` builds the image on first use. To skip the build entirely, point compose at the
published image by uncommenting the last two lines of `.env`:

```properties
DB_MCP_IMAGE=ghcr.io/thescarletarrow/db-mcp:latest
DB_MCP_PULL_POLICY=always
```

Connect the client to the container:

```bash
claude mcp add --scope user --transport http db-mcp http://127.0.0.1:8080/mcp
```

or let the client start a container per session over stdio (same volume, so the same vault). `db-mcp:local`
is what `docker compose` builds; `ghcr.io/thescarletarrow/db-mcp:latest` works the same without a build:

```bash
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
The container runs with a read-only root filesystem and `no-new-privileges`; only `/data` and `/tmp` are
writable.

#### What is in the image

~110 MB on disk, ~80 MB to pull. Two things keep it that small:

* **A jlink'd runtime instead of a full JRE.** Stage 2 of the `Dockerfile` builds a ~60 MB Java runtime
  containing only the modules this application uses, on top of `alpine` rather than a Debian base.
  The module list is `jdeps` output plus what is reached reflectively — SASL (PostgreSQL's
  SCRAM-SHA-256 handshake), JGSS, JDBC rowset/XA, HTTP/TLS client support for ClickHouse, the EC and
  PKCS#11 providers, JMX, zipfs. If you add a
  dependency that needs more, `jlink --add-modules` in the `Dockerfile` is the place to declare it.
* **Spring Boot layers instead of one fat jar.** The jar is split with
  `java -Djarmode=tools ... extract --layers --launcher` and copied in four `COPY` steps, least volatile
  first: `dependencies` (43 MB) → `spring-boot-loader` (0.7 MB) → `snapshot-dependencies` → `application`
  (0.4 MB). A code change rebuilds and re-pushes only that last 0.4 MB layer; upgrading a dependency
  invalidates the first one. The build stage is pinned to `$BUILDPLATFORM`, so a multi-arch build compiles
  the (architecture independent) jar once instead of once per platform under emulation.

`.github/workflows/docker-publish.yml` pushes `ghcr.io/thescarletarrow/db-mcp` for `linux/amd64` and
`linux/arm64` on every push to `main` and on `v*` tags.

## Configuration

| Environment variable / property | Default | Meaning |
|---------------------------------|---------|---------|
| `DB_MCP_HOME` / `db-mcp.vault.directory` | `~/.db-mcp` (Docker: `/data`) | Where `vault.enc` (and `vault.key`) live. |
| `DB_MCP_MASTER_PASSWORD` / `db-mcp.vault.master-password` | *(empty)* | If set, the vault key is derived with PBKDF2-HMAC-SHA256 (random salt per write, iteration count in the next row). If empty, a random 256-bit key is generated once into `vault.key` (mode `0600`). Recommended: set it. |
| `db-mcp.vault.pbkdf2-iterations` | `600000` | PBKDF2 iterations used when a master password is set (minimum 100000). Raising it re-encrypts the vault on the next write; the value used is stored in the file, so older vaults keep loading. |
| `DB_MCP_ALLOW_WRITES` / `db-mcp.query.allow-writes` | `false` | Server-wide switch for `execute_statement`. |
| `db-mcp.query.default-max-rows` / `hard-max-rows` | `200` / `5000` | Row limits for `run_query`. |
| `db-mcp.query.timeout` | `30s` | Statement timeout. |
| `db-mcp.query.max-cell-length` | `2000` | Longer text cells are clipped in results. |
| `db-mcp.pool.max-size` / `connection-timeout` / `idle-timeout` | `4` / `10s` / `5m` | Per-database HikariCP settings. |
| `DB_MCP_PORT`, `DB_MCP_BIND` (http profile) | `8080`, `127.0.0.1` | HTTP transport binding. |
| `SPRING_PROFILES_ACTIVE=http` | *(unset = stdio)* | Selects the HTTP transport. |
| `JAVA_OPTS` (Docker) | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM flags. Setting it replaces these defaults. |

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
| `Unknown database 'x'. Registered databases: [...]` | Typo in the name, or the database was never registered in this vault. Names are lower-cased: `Orders-DEV` is `orders-dev`. |
| `Credential 'dev' is still used by: [orders-dev, ...]` | `remove_credentials` refuses while databases authenticate with the alias. Remove them, or point them at another alias first. |
| `Database 'x' references missing credential 'dev'` | The vault has the database but not its credentials (e.g. a partially restored backup). `save_credentials` under that alias restores it. |
| `Write statements are disabled ...` | Start the server with `DB_MCP_ALLOW_WRITES=true`. |
| `Database 'x' is registered as read-only ...` | "Allow writes on x" (`set_read_only`). |
| `Only read-only statements (SELECT/WITH/EXPLAIN/SHOW) are allowed in run_query` | The statement does not start with one of the accepted read keywords, or it contains a second statement. Use `execute_statement` for writes. |
| `'INTO' is not allowed in run_query ...` | `SELECT ... INTO` writes a table. Drop the INTO clause, or use `execute_statement` on a writable database. |
| `Statement uses 'NEXTVAL' ...` (or `CSVWRITE`, `DBLINK`, `DBMS_...`, `URL`, `S3`) | A routine or table function that writes or reaches outside the database; not allowed in `run_query` even inside a SELECT. |
| `Statement contains 'LOAD', which is not allowed ...` | A column or alias collides with a keyword: double-quote it (`SELECT "load" FROM t`). |
| `A backslash in front of a closing quote is ambiguous ...` | Write the literal with a doubled quote (`'it''s'`) or as `$$it's$$`. |
| Claude Code does not see the server | `claude mcp list`; check the scope (`local` vs `user`) and that `java` is on PATH. Logs go to stderr: run the jar manually to see start-up errors. |
| Query result says `truncated: true` | More rows than `maxRows`; add WHERE/LIMIT or ask for a bigger `maxRows` (max 5000). |

## Upgrading

Rebuild the jar (or `docker compose up -d --build`, or `docker compose pull && docker compose up -d` when
running the published image); the vault format is versioned and older vaults load unchanged (databases
registered before the read-only flag existed are treated as read-only).

## Project layout

```
config/      DbMcpProperties          typed settings (db-mcp.*)
vault/       VaultCipher, SecretVault  AES-GCM encryption, atomic owner-only file persistence
registry/    DatabaseRegistry          databases + credential sets, write-through to the vault
connection/  DataSourceManager         one lazily created HikariCP pool per database, evicted on change
sql/         SqlGuard, SqlMasker,      read-only guard (keyword + routine policy over masked SQL),
             QueryExecutor             limits, read-only transactions, JSON-friendly value mapping
metadata/    SchemaInspector           schemas / tables / columns / keys / indexes via DatabaseMetaData
tools/       *Tools                    @McpTool endpoints exposed to the assistant
```

Tests: H2-backed unit tests plus an in-process end-to-end test that drives the real MCP server over the
streamable-HTTP transport (`McpServerEndToEndTest`); `PostgresContainerIT` re-checks the read-only fences
against a real PostgreSQL under `./mvnw verify` and skips itself when Docker is unavailable.

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

**Docker вместо jar**: `cp .env.example .env` (вписать туда мастер-пароль), затем `docker compose up -d` и
`claude mcp add --scope user --transport http db-mcp http://127.0.0.1:8080/mcp`.
Собирать ничего не нужно, если раскомментировать в `.env` строки `DB_MCP_IMAGE` / `DB_MCP_PULL_POLICY` —
тогда образ (~80 МБ) скачается с ghcr.io.
Базы на своей машине указывать как `host.docker.internal`. Данные живут в volume `db-mcp-data`,
`docker compose down -v` их удалит.

**Регистрация БД** (говорить ассистенту в чате):

* «Зарегистрируй orders-dev: jdbc:postgresql://localhost:5432/orders» → спросит логин/пароль один раз.
* «Добавь jdbc:oracle:thin:@//localhost:1521/ERP как erp-dev, креды те же, что у orders-dev» → ничего не спросит.
* «Добавь ClickHouse jdbc:ch://localhost:8123/default как analytics-dev, креды те же» → тип определится по URL.
* «Какие базы подключены?» / «Какие креды сохранены?» / «Проверь подключение к erp-dev».
* «Пароль для алиаса orders-dev поменялся» → обновит и переподключит все базы с этим алиасом.
* «Удали erp-dev».

**Запросы**: «Какие схемы в orders-dev?», «Покажи таблицы в схеме billing», «Опиши таблицу orders»,
«Сколько заказов по статусам за месяц?». Один читающий запрос за вызов, 200 строк по умолчанию (до 5000
через `maxRows`), длинные ячейки обрезаются.

**Только чтение**: все базы read-only по умолчанию. В такую базу нельзя ничего записать вообще:
отклоняются INSERT/UPDATE/DELETE/DDL, `SELECT ... INTO`, функции-запись внутри SELECT (`nextval`,
`lo_import`, `dblink`, `CSVWRITE`, ClickHouse `url`/`s3`/`file`/`remote`, пакеты `DBMS_*`/`UTL_*`) и второй запрос после `;` — в том числе
спрятанный в комментарии или в строковом литерале (типичные SQL-инъекции). Подробно:
[Read-only enforcement](#read-only-enforcement). Включить запись для одной базы: «Разреши запись в orders-dev»
+ сервер запущен с `DB_MCP_ALLOW_WRITES=true`. Вернуть обратно: «Сделай orders-dev только для чтения».

**Где лежит**: `~/.db-mcp/vault.enc` (в Docker `/data`). Бэкап = скопировать папку. Забыли мастер-пароль:
удалить `vault.enc` и зарегистрировать базы заново.

**Если не работает**: `claude mcp list`; запустить `java -jar ...jar` руками и посмотреть ошибки в stderr;
таблица типовых ошибок выше в разделе Troubleshooting.
