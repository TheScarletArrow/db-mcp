package io.github.thescarletarrow.dbmcp.sql;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static checks applied before SQL is sent to a database. This is defence in depth on top of read-only JDBC
 * connections and read-only transactions - both of which several drivers (Oracle, H2) silently ignore - so it
 * has to stand on its own: it rejects anything that is not plainly a single read.
 *
 * <p>Rejected in {@code run_query}, on top of the obvious {@code INSERT}/{@code UPDATE}/{@code DELETE}/DDL:
 * <ul>
 *   <li>{@code SELECT ... INTO}, which creates or fills a table while looking like a query;</li>
 *   <li>routines that write through a {@code SELECT}: sequence bumps ({@code nextval}, {@code setval}),
 *       large-object and file access ({@code lo_import}, {@code pg_read_file}, {@code CSVWRITE}),
 *       out-of-database calls ({@code dblink}, {@code UTL_HTTP}) and anything that executes SQL passed as
 *       text ({@code dblink_exec}, {@code DBMS_XMLGEN}, {@code query_to_xml});</li>
 *   <li>a second statement smuggled in after a {@code ;} - including through a comment, a dollar-quoted
 *       string or a backslash-escaped quote (see {@link SqlMasker}).</li>
 * </ul>
 */
public final class SqlGuard {

    private static final Set<String> READ_STARTERS = Set.of("SELECT", "WITH", "EXPLAIN", "SHOW", "VALUES", "TABLE", "DESCRIBE", "DESC");

    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "RETURNING",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "COMMENT",
            "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "DO", "BEGIN", "DECLARE",
            "LOCK", "COPY", "VACUUM", "ANALYZE", "CLUSTER", "REINDEX", "REFRESH", "CHECKPOINT",
            "SET", "RESET", "COMMIT", "ROLLBACK", "SAVEPOINT", "LISTEN", "NOTIFY", "PURGE", "FLASHBACK",
            "PREPARE", "DEALLOCATE", "DISCARD", "LOAD", "IMPORT", "ATTACH", "DETACH");

    /**
     * Routines that modify data, touch the file system or reach outside the database even though the statement
     * around them is a plain {@code SELECT}. Read-only transactions do not stop most of them.
     */
    private static final Set<String> UNSAFE_ROUTINES = Set.of(
            // PostgreSQL - sequences and large objects
            "NEXTVAL", "SETVAL", "LO_IMPORT", "LO_EXPORT", "LO_CREATE", "LO_UNLINK", "LO_PUT", "LO_FROM_BYTEA",
            "LOWRITE", "LO_OPEN",
            // PostgreSQL - file system and server state
            "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR", "PG_STAT_FILE", "PG_FILE_WRITE", "PG_FILE_UNLINK",
            "PG_FILE_RENAME", "PG_FILE_SYNC", "PG_RELOAD_CONF", "PG_ROTATE_LOGFILE", "PG_SWITCH_WAL",
            "PG_CREATE_RESTORE_POINT", "PG_LOGICAL_EMIT_MESSAGE", "PG_STAT_STATEMENTS_RESET",
            "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND",
            // PostgreSQL - locks and sleeps that hold a connection hostage
            "PG_ADVISORY_LOCK", "PG_ADVISORY_LOCK_SHARED", "PG_ADVISORY_XACT_LOCK", "PG_ADVISORY_XACT_LOCK_SHARED",
            "PG_SLEEP", "PG_SLEEP_FOR", "PG_SLEEP_UNTIL",
            // PostgreSQL - run SQL handed over as text
            "DBLINK", "DBLINK_EXEC", "DBLINK_CONNECT", "DBLINK_CONNECT_U", "DBLINK_SEND_QUERY", "DBLINK_OPEN",
            "QUERY_TO_XML", "QUERY_TO_XMLSCHEMA", "QUERY_TO_XML_AND_XMLSCHEMA",
            // Oracle - outbound calls (the DBMS_/UTL_ packages are covered by the prefixes below)
            "HTTPURITYPE", "DBURITYPE",
            // H2 - file access straight from a SELECT
            "FILE_READ", "FILE_WRITE", "CSVREAD", "CSVWRITE", "LINK_SCHEMA");

    /** Oracle's built-in packages: the read-only ones are not worth the risk of allow-listing them one by one. */
    private static final List<String> UNSAFE_ROUTINE_PREFIXES = List.of("DBMS_", "UTL_", "OWA_");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private SqlGuard() {
    }

    /**
     * @return the trimmed statement (without trailing semicolon) if it is a single read-only statement
     * @throws IllegalArgumentException with an actionable message otherwise
     */
    public static String requireReadOnly(String sql) {
        Statement statement = single(sql);
        String masked = statement.masked();
        Matcher words = WORD.matcher(masked);
        if (!words.find() || !READ_STARTERS.contains(words.group())) {
            throw new IllegalArgumentException("Only read-only statements (SELECT/WITH/EXPLAIN/SHOW) are allowed in run_query. "
                    + "Use execute_statement for DML/DDL (requires db-mcp.query.allow-writes=true and a writable database).");
        }
        words.reset();
        while (words.find()) {
            String word = words.group();
            if (word.equals("INTO")) {
                throw new IllegalArgumentException("'INTO' is not allowed in run_query: 'SELECT ... INTO' creates or fills "
                        + "a table instead of returning rows. Drop the INTO clause, or use execute_statement on a "
                        + "writable database if a table really has to be written.");
            }
            if (WRITE_KEYWORDS.contains(word) && !isAllowedInReadContext(word, masked)) {
                throw new IllegalArgumentException("Statement contains '" + word + "', which is not allowed in run_query. "
                        + "Quote identifiers if this is a column name, or use execute_statement for writes.");
            }
            if (isUnsafeRoutine(word)) {
                throw new IllegalArgumentException("Statement uses '" + word + "', which can modify data or reach outside "
                        + "the database, so it is not allowed in run_query even though it is written as a SELECT.");
            }
        }
        return statement.text();
    }

    /**
     * @return the trimmed statement (without trailing semicolon) if it is a single statement
     */
    public static String requireSingleStatement(String sql) {
        return single(sql).text();
    }

    /**
     * A single statement: the text to send to the database, plus the same range with literals, quoted identifiers
     * and comments blanked out and upper-cased for keyword scanning.
     */
    private record Statement(String text, String masked) {
    }

    private static Statement single(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty");
        }
        String masked = SqlMasker.mask(sql);
        int start = 0;
        while (start < masked.length() && Character.isWhitespace(masked.charAt(start))) {
            start++;
        }
        int end = trimTrailingSemicolons(masked, start);
        if (start >= end) {
            throw new IllegalArgumentException("SQL must contain a statement, not only comments");
        }
        String body = masked.substring(start, end);
        if (body.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Only a single SQL statement is allowed per call (found ';' inside the statement)");
        }
        return new Statement(sql.substring(start, end), body.toUpperCase(Locale.ROOT));
    }

    private static int trimTrailingSemicolons(String masked, int start) {
        int end = masked.length();
        while (end > start) {
            char c = masked.charAt(end - 1);
            if (!Character.isWhitespace(c) && c != ';') {
                break;
            }
            end--;
        }
        return end;
    }

    private static boolean isAllowedInReadContext(String word, String masked) {
        // "EXPLAIN ANALYZE ..." runs the plan of a read; a bare "ANALYZE table" collects statistics and writes.
        if (word.equals("ANALYZE") || word.equals("ANALYSE")) {
            return masked.startsWith("EXPLAIN");
        }
        return false;
    }

    private static boolean isUnsafeRoutine(String word) {
        return UNSAFE_ROUTINES.contains(word) || UNSAFE_ROUTINE_PREFIXES.stream().anyMatch(word::startsWith);
    }
}
