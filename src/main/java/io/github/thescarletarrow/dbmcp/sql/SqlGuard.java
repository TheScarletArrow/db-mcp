package io.github.thescarletarrow.dbmcp.sql;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static checks applied before SQL is sent to a database. This is defence in depth on top of
 * read-only JDBC connections: it rejects obviously non-read statements and statement batching.
 */
public final class SqlGuard {

    private static final Set<String> READ_STARTERS = Set.of("SELECT", "WITH", "EXPLAIN", "SHOW", "VALUES", "TABLE", "DESCRIBE", "DESC");

    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "COMMENT",
            "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "DO", "BEGIN", "DECLARE",
            "LOCK", "COPY", "VACUUM", "ANALYZE", "CLUSTER", "REINDEX", "REFRESH",
            "SET", "RESET", "COMMIT", "ROLLBACK", "SAVEPOINT", "LISTEN", "NOTIFY", "PURGE", "FLASHBACK");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private SqlGuard() {
    }

    /**
     * @return the trimmed statement (without trailing semicolon) if it looks like a single read-only statement
     * @throws IllegalArgumentException with an actionable message otherwise
     */
    public static String requireReadOnly(String sql) {
        String statement = requireSingleStatement(sql);
        String masked = maskLiteralsAndComments(statement.toUpperCase(Locale.ROOT)).strip();
        Matcher first = WORD.matcher(masked);
        if (!first.find() || !READ_STARTERS.contains(first.group())) {
            throw new IllegalArgumentException("Only read-only statements (SELECT/WITH/EXPLAIN/SHOW) are allowed in run_query. "
                    + "Use execute_statement for DML/DDL (requires db-mcp.query.allow-writes=true).");
        }
        Matcher words = WORD.matcher(masked);
        while (words.find()) {
            String word = words.group();
            if (WRITE_KEYWORDS.contains(word) && !isAllowedInReadContext(word, masked, words.start())) {
                throw new IllegalArgumentException("Statement contains '" + word + "', which is not allowed in run_query. "
                        + "Quote identifiers if this is a column name, or use execute_statement for writes.");
            }
        }
        return statement;
    }

    /**
     * @return the trimmed statement (without trailing semicolon) if it is a single statement
     */
    public static String requireSingleStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty");
        }
        String masked = maskLiteralsAndComments(sql);
        String trimmedMasked = masked.strip();
        while (trimmedMasked.endsWith(";")) {
            trimmedMasked = trimmedMasked.substring(0, trimmedMasked.length() - 1).strip();
        }
        if (trimmedMasked.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Only a single SQL statement is allowed per call (found ';' inside the statement)");
        }
        if (trimmedMasked.isEmpty()) {
            throw new IllegalArgumentException("SQL must contain a statement, not only comments");
        }
        String statement = sql.strip();
        while (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).strip();
        }
        return statement;
    }

    private static boolean isAllowedInReadContext(String word, String masked, int position) {
        // "EXPLAIN ANALYZE ..." is a read; "SET" appears in PostgreSQL "... FOR UPDATE" no, but in window "ROWS ... SET"? keep strict.
        if (word.equals("ANALYZE") || word.equals("ANALYSE")) {
            return masked.startsWith("EXPLAIN");
        }
        return false;
    }

    /**
     * Replaces the content of string literals, quoted identifiers and comments with spaces so that
     * keyword scanning is not fooled by text such as {@code 'drop me'} or {@code -- delete}.
     */
    static String maskLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                out.append(quote);
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) { // escaped quote
                            out.append("  ");
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < n) {
                    out.append(quote);
                    i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                out.append(" ".repeat(stop - i));
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
