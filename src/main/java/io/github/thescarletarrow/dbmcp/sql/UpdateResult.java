package io.github.thescarletarrow.dbmcp.sql;

/**
 * Result of a DML/DDL statement.
 */
public record UpdateResult(int affectedRows, long executionMillis) {
}
