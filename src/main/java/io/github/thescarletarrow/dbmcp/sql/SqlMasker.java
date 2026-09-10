package io.github.thescarletarrow.dbmcp.sql;

import java.util.Arrays;

/**
 * Blanks out the <em>content</em> of string literals, quoted identifiers and comments so that the keyword scan
 * in {@link SqlGuard} cannot be fooled by text such as {@code 'drop me'} or {@code -- delete}. Every character
 * keeps its position, so an offset into the masked text addresses the same character in the original SQL.
 *
 * <p>Anything that cannot be lexed the same way both supported engines would lex it - an unterminated literal
 * or comment, a backslash in front of a closing quote (whose meaning depends on the server's
 * {@code standard_conforming_strings} / {@code sql_mode}) - is rejected rather than guessed. Where the two
 * engines differ the masker deliberately masks the smaller region: seeing more SQL than the server will run
 * can only make the guard stricter, while seeing less would make it blind.
 */
final class SqlMasker {

    private static final char NONE = '\0';

    private SqlMasker() {
    }

    static String mask(String sql) {
        char[] out = sql.toCharArray();
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (c == NONE) {
                throw new IllegalArgumentException("SQL must not contain NUL characters");
            }
            if (c == '\'') {
                i = maskQuoted(sql, out, i, '\'', true);
            } else if (c == '"' || c == '`') {
                i = maskQuoted(sql, out, i, c, false);
            } else if (c == '$' && isDollarQuoteStart(sql, i)) {
                i = maskDollarQuoted(sql, out, i);
            } else if (isAlternativeQuoteStart(sql, i)) {
                i = maskAlternativeQuoted(sql, out, i);
            } else if (c == '-' && charAt(sql, i + 1) == '-') {
                i = maskLineComment(sql, out, i);
            } else if (c == '/' && charAt(sql, i + 1) == '*') {
                i = maskBlockComment(sql, out, i);
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** Masks a {@code '...'} literal or a {@code "..."} / {@code `...`} identifier; the delimiters stay visible. */
    private static int maskQuoted(String sql, char[] out, int start, char quote, boolean literal) {
        int i = start + 1;
        int backslashes = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (literal && backslashes % 2 == 1) {
                    throw new IllegalArgumentException("A backslash in front of a closing quote is ambiguous: whether "
                            + "it escapes the quote depends on the server's settings, so the statement is rejected. "
                            + "Double the quote ('') or use a dollar-quoted string instead.");
                }
                if (charAt(sql, i + 1) == quote) { // '' or "" - an escaped quote, the literal continues
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i += 2;
                    backslashes = 0;
                    continue;
                }
                return i + 1;
            }
            backslashes = c == '\\' ? backslashes + 1 : 0;
            out[i] = ' ';
            i++;
        }
        throw new IllegalArgumentException("Unterminated " + (literal ? "string literal" : "quoted identifier")
                + " (unbalanced " + quote + ") in the statement");
    }

    /** PostgreSQL {@code $$...$$} / {@code $tag$...$tag$}. */
    private static int maskDollarQuoted(String sql, char[] out, int start) {
        String tag = dollarTag(sql, start);
        int bodyStart = start + tag.length();
        int end = sql.indexOf(tag, bodyStart);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated dollar-quoted string: no closing '" + tag + "'");
        }
        Arrays.fill(out, start, end + tag.length(), ' ');
        return end + tag.length();
    }

    /** Oracle {@code q'[...]'} and friends (also {@code nq'...'}). */
    private static int maskAlternativeQuoted(String sql, char[] out, int start) {
        char opener = sql.charAt(start + 2);
        char closer = switch (opener) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> opener;
        };
        for (int i = start + 3; i + 1 < sql.length(); i++) {
            if (sql.charAt(i) == closer && sql.charAt(i + 1) == '\'') {
                Arrays.fill(out, start, i + 2, ' ');
                return i + 2;
            }
        }
        throw new IllegalArgumentException("Unterminated alternative-quoted literal: no closing '" + closer + "'");
    }

    private static int maskLineComment(String sql, char[] out, int start) {
        int i = start;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            out[i] = ' ';
            i++;
        }
        return i;
    }

    private static int maskBlockComment(String sql, char[] out, int start) {
        int end = sql.indexOf("*/", start + 2);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated block comment: no closing '*/'");
        }
        // Block comments are not treated as nesting: PostgreSQL does nest them, so ending the comment at the
        // first '*/' leaves more SQL visible to the guard than the server will run, never less.
        Arrays.fill(out, start, end + 2, ' ');
        return end + 2;
    }

    private static boolean isDollarQuoteStart(String sql, int i) {
        // A '$' right after an identifier character belongs to that identifier (Oracle's V$SESSION, and the same
        // rule in PostgreSQL), so it cannot open a dollar-quoted string.
        return !isIdentifierPart(charAt(sql, i - 1)) && dollarTag(sql, i) != null;
    }

    private static String dollarTag(String sql, int start) {
        int i = start + 1;
        while (i < sql.length() && isIdentifierPart(sql.charAt(i))) {
            if (i == start + 1 && Character.isDigit(sql.charAt(i))) {
                return null; // $1, $2 - a bind parameter, not a dollar quote
            }
            i++;
        }
        return charAt(sql, i) == '$' ? sql.substring(start, i + 1) : null;
    }

    private static boolean isAlternativeQuoteStart(String sql, int i) {
        char c = sql.charAt(i);
        if ((c != 'q' && c != 'Q') || charAt(sql, i + 1) != '\'' || i + 3 >= sql.length()) {
            return false;
        }
        char previous = charAt(sql, i - 1);
        if (previous == 'n' || previous == 'N') { // nq'...' is the national-charset flavour
            previous = charAt(sql, i - 2);
        }
        return !isIdentifierPart(previous);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static char charAt(String sql, int index) {
        return index >= 0 && index < sql.length() ? sql.charAt(index) : NONE;
    }
}
