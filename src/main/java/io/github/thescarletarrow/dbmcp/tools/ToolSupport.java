package io.github.thescarletarrow.dbmcp.tools;

import java.sql.SQLException;

/**
 * Helpers shared by tool classes.
 */
final class ToolSupport {

    private ToolSupport() {
    }

    /** Wraps a JDBC failure into a message the model can act on, without leaking credentials. */
    static IllegalStateException sqlFailure(String action, String database, SQLException e) {
        String state = e.getSQLState() == null ? "" : " [SQLState " + e.getSQLState() + "]";
        return new IllegalStateException("Failed to " + action + " on database '" + database + "'" + state + ": "
                + firstLine(e.getMessage()), e);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).strip();
    }
}
