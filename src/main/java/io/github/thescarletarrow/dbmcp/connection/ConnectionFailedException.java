package io.github.thescarletarrow.dbmcp.connection;

import java.sql.SQLException;

/**
 * Raised when a pool for a registered database cannot open its first connection. The message carries
 * the JDBC driver's explanation (unknown host, refused connection, invalid credentials, ...).
 */
public class ConnectionFailedException extends RuntimeException {

    private final String databaseName;

    public ConnectionFailedException(String databaseName, Throwable cause) {
        super("Cannot connect to database '" + databaseName + "': " + rootMessage(cause), cause);
        this.databaseName = databaseName;
    }

    public String databaseName() {
        return databaseName;
    }

    private static String rootMessage(Throwable cause) {
        Throwable t = cause;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage().strip();
        if (t instanceof SQLException sql && sql.getSQLState() != null) {
            message += " [SQLState " + sql.getSQLState() + "]";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
