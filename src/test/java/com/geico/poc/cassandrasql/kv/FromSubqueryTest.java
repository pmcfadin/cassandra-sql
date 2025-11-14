package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FROM subqueries (derived tables)
 */
@SpringBootTest
@ActiveProfiles("kv")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FromSubqueryTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    private static int testCounter = 0;
    
    @Test
    @Order(1)
    public void testSimpleFromSubquery() throws Exception {
        String tableName = uniqueTableName("simple_test_1");
        
        // Create and populate table
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name TEXT, v INT)");

        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s (id, name, v) VALUES (1, 'Alice', 100)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, v) VALUES (2, 'Bob', 200)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, v) VALUES (3, 'Charlie', 150)", tableName));
        Thread.sleep(200);
        
        // Test simple FROM subquery
        QueryResponse result = queryService.execute(String.format(
            "SELECT * FROM (SELECT id, name, v FROM %s) AS sub ORDER BY id",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertNotNull(result.getRows(), "Should have rows");
        assertEquals(3, result.getRows().size(), "Should return 3 rows");
        assertEquals("Alice", result.getRows().get(0).get("name"));
        assertEquals(100, result.getRows().get(0).get("v"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(2)
    public void testFromSubqueryWithWhere() throws Exception {
        String tableName = uniqueTableName("where_test");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, category TEXT, amount DECIMAL(10,2))",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'A', 100.50)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 'B', 200.75)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (3, 'A', 150.25)", tableName));
        Thread.sleep(200);
        
        // Subquery with WHERE, outer query with WHERE
        QueryResponse result = queryService.execute(String.format(
            "SELECT * FROM (SELECT id, category, amount FROM %s WHERE category = 'A') AS sub WHERE amount > 120 ORDER BY id",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(1, result.getRows().size(), "Should return 1 row");
        assertEquals(3, result.getRows().get(0).get("id"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(3)
    public void testFromSubqueryWithAggregation() throws Exception {
        String tableName = uniqueTableName("agg_test");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, category TEXT, amount DECIMAL(10,2))",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'A', 100)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 'B', 200)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (3, 'A', 150)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (4, 'B', 250)", tableName));
        Thread.sleep(200);
        
        // Subquery with GROUP BY, outer query selects from aggregated results
        QueryResponse result = queryService.execute(String.format(
            "SELECT category, total FROM (SELECT category, SUM(amount) AS total FROM %s GROUP BY category) AS sub ORDER BY total DESC",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(2, result.getRows().size(), "Should return 2 rows");
        assertEquals("B", result.getRows().get(0).get("category"));
        assertEquals(450.0, ((Number) result.getRows().get(0).get("total")).doubleValue(), 0.01);
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(4)
    public void testFromSubqueryWithJoin() throws Exception {
        String table1 = uniqueTableName("join_test1");
        String table2 = uniqueTableName("join_test2");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            table1
        ));
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, user_id INT, amount DECIMAL(10,2))",
            table2
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'Alice'), (2, 'Bob')", table1));
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 1, 100), (2, 1, 200), (3, 2, 150)", table2));
        Thread.sleep(200);
        
        // Subquery with JOIN
        QueryResponse result = queryService.execute(String.format(
            "SELECT name, total FROM (SELECT u.name, SUM(o.amount) AS total FROM %s u LEFT JOIN %s o ON u.id = o.user_id GROUP BY u.id, u.name) AS sub ORDER BY total DESC",
            table1, table2
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(3, result.getRows().size(), "Should return 3 rows");
        assertEquals("Alice", result.getRows().get(0).get("name"));
        assertEquals(200.0, ((Number) result.getRows().get(0).get("total")).doubleValue(), 0.01);
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + table1);
        queryService.execute("DROP TABLE IF EXISTS " + table2);
    }
    
    @Test
    @Order(5)
    public void testFromSubqueryWithHaving() throws Exception {
        String tableName = uniqueTableName("having_test");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, category TEXT, amount DECIMAL(10,2))",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'A', 100)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 'B', 200)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (3, 'A', 150)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (4, 'C', 50)", tableName));
        Thread.sleep(200);
        
        // Subquery with HAVING clause
        QueryResponse result = queryService.execute(String.format(
            "SELECT * FROM (SELECT category, SUM(amount) AS total FROM %s GROUP BY category HAVING SUM(amount) > 100) AS sub ORDER BY total DESC",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(2, result.getRows().size(), "Should return 2 rows (A and B, not C)");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Disabled
    @Order(6)
    public void testFromSubqueryWithOrderAndLimit() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, v INT)",
            tableName
        ));
        Thread.sleep(200);
        
        for (int i = 1; i <= 10; i++) {
            queryService.execute(String.format("INSERT INTO %s VALUES (%d, %d)", tableName, i, i * 10));
        }
        Thread.sleep(200);
        
        // Subquery with ORDER BY and LIMIT
        QueryResponse result = queryService.execute(String.format(
            "SELECT * FROM (SELECT id, v FROM %s ORDER BY v DESC LIMIT 3) AS sub ORDER BY id",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(3, result.getRows().size(), "Should return 3 rows");
        assertEquals(8, result.getRows().get(0).get("id"));
        assertEquals(9, result.getRows().get(1).get("id"));
        assertEquals(10, result.getRows().get(2).get("id"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Disabled
    @Order(7)
    public void testNestedFromSubqueries() throws Exception {
        String tableName = uniqueTableName("nested_test");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, v INT)",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 100), (2, 200), (3, 150)", tableName));
        Thread.sleep(200);
        
        // Nested subqueries
        QueryResponse result = queryService.execute(String.format(
            "SELECT * FROM (SELECT * FROM (SELECT id, v FROM %s WHERE v > 100) AS inner_sub WHERE id < 10) AS outer_sub ORDER BY id",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(2, result.getRows().size(), "Should return 2 rows");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(8)
    public void testFromSubqueryWithExpressions() throws Exception {
        String tableName = uniqueTableName("expr_test");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, price DECIMAL(10,2), quantity INT)",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 10.50, 5)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 20.00, 3)", tableName));
        Thread.sleep(200);
        
        // Subquery with calculated columns
        QueryResponse result = queryService.execute(String.format(
            "SELECT id, total FROM (SELECT id, price * quantity AS total FROM %s) AS sub WHERE total > 50 ORDER BY id",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(2, result.getRows().size(), "Should return 2 rows");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(9)
    public void testFromSubqueryAggregateOnAggregate() throws Exception {
        String tableName = uniqueTableName("agg_agg_test");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, category TEXT, amount DECIMAL(10,2))",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'A', 100)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 'B', 200)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (3, 'A', 150)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (4, 'B', 250)", tableName));
        Thread.sleep(200);
        
        // Aggregate on aggregated results (like the demo script)
        QueryResponse result = queryService.execute(String.format(
            "SELECT COUNT(*) AS category_count, AVG(total) AS avg_total FROM (SELECT category, SUM(amount) AS total FROM %s GROUP BY category) AS sub",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(1, result.getRows().size(), "Should return 1 row");
        assertEquals(2, ((Number) result.getRows().get(0).get("category_count")).intValue());
        assertEquals(350.0, ((Number) result.getRows().get(0).get("avg_total")).doubleValue(), 0.01);
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
}

