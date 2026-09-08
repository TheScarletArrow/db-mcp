package io.github.thescarletarrow.dbmcp.metadata;

import io.github.thescarletarrow.dbmcp.support.H2DataSources;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaInspectorTest {

    private static final H2DataSources DATA_SOURCES = new H2DataSources("meta");
    private final SchemaInspector inspector = new SchemaInspector(DATA_SOURCES);

    @BeforeAll
    static void schema() throws SQLException {
        try (Connection c = DATA_SOURCES.dataSource("meta").getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA shop");
            s.execute("CREATE TABLE shop.customers (id INT PRIMARY KEY, email VARCHAR(200) NOT NULL)");
            s.execute("CREATE TABLE shop.orders (id INT PRIMARY KEY, customer_id INT NOT NULL REFERENCES shop.customers(id), total NUMERIC(12,2) DEFAULT 0)");
            s.execute("CREATE INDEX orders_customer_idx ON shop.orders(customer_id)");
            s.execute("CREATE VIEW shop.order_totals AS SELECT customer_id, SUM(total) AS total FROM shop.orders GROUP BY customer_id");
            c.commit();
        }
    }

    @Test
    void listsSchemasAndTables() throws SQLException {
        assertThat(inspector.listSchemas("meta", false)).extracting(SchemaInspector.SchemaInfo::name).contains("shop", "public");

        SchemaInspector.TableList tables = inspector.listTables("meta", "shop", null, true, 10);
        assertThat(tables.tables()).extracting(SchemaInspector.TableInfo::name).containsExactlyInAnyOrder("customers", "orders", "order_totals");
        assertThat(tables.truncated()).isFalse();

        SchemaInspector.TableList onlyTables = inspector.listTables("meta", "shop", "ord%", false, 1);
        assertThat(onlyTables.tables()).extracting(SchemaInspector.TableInfo::name).containsExactly("orders");
    }

    @Test
    void describesTableWithKeysAndIndexes() throws SQLException {
        SchemaInspector.TableDescription orders = inspector.describeTable("meta", "SHOP", "Orders");

        assertThat(orders.schema()).isEqualTo("shop");
        assertThat(orders.primaryKey()).containsExactly("id");
        assertThat(orders.columns()).extracting(SchemaInspector.ColumnInfo::name).containsExactly("id", "customer_id", "total");
        assertThat(orders.columns().get(1).nullable()).isFalse();
        assertThat(orders.columns().get(2).defaultValue()).isEqualTo("0");
        assertThat(orders.foreignKeys()).singleElement().satisfies(fk -> {
            assertThat(fk.columns()).containsExactly("customer_id");
            assertThat(fk.referencedTable()).isEqualTo("customers");
            assertThat(fk.referencedColumns()).containsExactly("id");
        });
        assertThat(orders.indexes()).anySatisfy(ix -> {
            assertThat(ix.name()).isEqualTo("orders_customer_idx");
            assertThat(ix.unique()).isFalse();
            assertThat(ix.columns()).containsExactly("customer_id");
        });
    }

    @Test
    void unknownTableGivesActionableError() {
        assertThatThrownBy(() -> inspector.describeTable("meta", "shop", "nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list_tables");
    }
}
