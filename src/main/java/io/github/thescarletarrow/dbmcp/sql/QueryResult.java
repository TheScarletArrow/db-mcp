package io.github.thescarletarrow.dbmcp.sql;

import java.util.List;

/**
 * Tabular result returned to the model.
 *
 * @param columns     column labels in result order
 * @param columnTypes JDBC type names per column
 * @param rows        row values, JSON-friendly (numbers, strings, booleans, null)
 * @param rowCount    number of rows included
 * @param truncated   true when more rows were available than the limit
 * @param executionMillis wall time of the statement including fetching
 */
public record QueryResult(List<String> columns,
                          List<String> columnTypes,
                          List<List<Object>> rows,
                          int rowCount,
                          boolean truncated,
                          long executionMillis) {
}
