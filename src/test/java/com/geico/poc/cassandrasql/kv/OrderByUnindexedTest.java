package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test ORDER BY functionality on un-indexed columns.
 * PostgreSQL implements ORDER BY in memory even when no index exists.
 * This test suite verifies that our KV store does the same.
 */
@SpringBootTest
public class OrderByUnindexedTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Test
    public void testSingleColumnOrderByAsc() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        // Create table with no indexes
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT, score INT)",
            tableName
        ));
        
        // Insert test data in random order
        queryService.execute(String.format("INSERT INTO %s (id, name, score) VALUES (3, 'Charlie', 85)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, score) VALUES (1, 'Alice', 95)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, score) VALUES (2, 'Bob', 75)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, score) VALUES (4, 'Diana', 90)", tableName));
        
        // Query with ORDER BY on un-indexed column (score)
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s ORDER BY score ASC", tableName)
        );
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows());
        assertEquals(4, response.getRows().size());
        
        // Verify ascending order by score
        List<Map<String, Object>> rows = response.getRows();
        assertEquals("Bob", rows.get(0).get("name"), "First should be Bob (75)");
        assertEquals("Charlie", rows.get(1).get("name"), "Second should be Charlie (85)");
        assertEquals("Diana", rows.get(2).get("name"), "Third should be Diana (90)");
        assertEquals("Alice", rows.get(3).get("name"), "Fourth should be Alice (95)");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testSingleColumnOrderByDesc() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        // Create table with no indexes
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT, score INT)",
            tableName
        ));
        
        // Insert test data
        queryService.execute(String.format("INSERT INTO %s (id, name, score) VALUES (1, 'Alice', 95)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, score) VALUES (2, 'Bob', 75)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, score) VALUES (3, 'Charlie', 85)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, score) VALUES (4, 'Diana', 90)", tableName));
        
        // Query with ORDER BY DESC on un-indexed column
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s ORDER BY score DESC", tableName)
        );
        
        assertNull(response.getError());
        assertEquals(4, response.getRows().size());
        
        // Verify descending order by score
        List<Map<String, Object>> rows = response.getRows();
        assertEquals("Alice", rows.get(0).get("name"), "First should be Alice (95)");
        assertEquals("Diana", rows.get(1).get("name"), "Second should be Diana (90)");
        assertEquals("Charlie", rows.get(2).get("name"), "Third should be Charlie (85)");
        assertEquals("Bob", rows.get(3).get("name"), "Fourth should be Bob (75)");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testMultiColumnOrderBy() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        // Create table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, department TEXT, salary INT, name TEXT)",
            tableName
        ));
        
        // Insert test data with same departments
        queryService.execute(String.format("INSERT INTO %s (id, department, salary, name) VALUES (1, 'Engineering', 90000, 'Alice')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, department, salary, name) VALUES (2, 'Engineering', 85000, 'Bob')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, department, salary, name) VALUES (3, 'Sales', 70000, 'Charlie')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, department, salary, name) VALUES (4, 'Sales', 75000, 'Diana')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, department, salary, name) VALUES (5, 'Engineering', 95000, 'Eve')", tableName));
        
        // Query with multi-column ORDER BY (department ASC, salary DESC)
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s ORDER BY department ASC, salary DESC", tableName)
        );
        
        assertNull(response.getError());
        assertEquals(5, response.getRows().size());
        
        // Verify order: Engineering first (sorted by salary DESC), then Sales (sorted by salary DESC)
        List<Map<String, Object>> rows = response.getRows();
        assertEquals("Eve", rows.get(0).get("name"), "First: Engineering, highest salary (95000)");
        assertEquals("Alice", rows.get(1).get("name"), "Second: Engineering, second highest (90000)");
        assertEquals("Bob", rows.get(2).get("name"), "Third: Engineering, lowest (85000)");
        assertEquals("Diana", rows.get(3).get("name"), "Fourth: Sales, highest (75000)");
        assertEquals("Charlie", rows.get(4).get("name"), "Fifth: Sales, lowest (70000)");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testOrderByWithWhereClause() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        // Create table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, category TEXT, price INT)",
            tableName
        ));
        
        // Insert test data
        queryService.execute(String.format("INSERT INTO %s (id, category, price) VALUES (1, 'Electronics', 500)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, category, price) VALUES (2, 'Electronics', 300)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, category, price) VALUES (3, 'Books', 20)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, category, price) VALUES (4, 'Electronics', 800)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, category, price) VALUES (5, 'Books', 15)", tableName));
        
        // Query with WHERE and ORDER BY
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s WHERE category = 'Electronics' ORDER BY price DESC", tableName)
        );
        
        assertNull(response.getError());
        assertEquals(3, response.getRows().size());
        
        // Verify filtered and sorted results
        List<Map<String, Object>> rows = response.getRows();
        assertEquals(800, ((Number) rows.get(0).get("price")).intValue());
        assertEquals(500, ((Number) rows.get(1).get("price")).intValue());
        assertEquals(300, ((Number) rows.get(2).get("price")).intValue());
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testOrderByWithLimit() throws Exception {
        String tableName = uniqueTableName("order_limit_test");
        
        // Drop table if it exists from previous run
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
        
        // Create table (use 'amount' instead of 'value' which is a reserved keyword)
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, amount INT)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());
        
        // Small delay to ensure schema propagation
        Thread.sleep(200);
        
        // Insert 10 rows
        for (int i = 1; i <= 10; i++) {
            QueryResponse insertResp = queryService.execute(String.format("INSERT INTO %s (id, amount) VALUES (%d, %d)", 
                tableName, i, 100 - i * 10));
            assertNull(insertResp.getError(), "INSERT failed for row " + i + ": " + insertResp.getError());
        }
        
        // Small delay after inserts
        Thread.sleep(100);
        
        // Verify data was inserted
        QueryResponse checkResponse = queryService.execute(
            String.format("SELECT * FROM %s", tableName)
        );
        assertNull(checkResponse.getError(), "Check query failed: " + checkResponse.getError());
        assertEquals(10, checkResponse.getRows().size(), "Should have 10 rows inserted");
        
        // Query with ORDER BY and LIMIT
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s ORDER BY amount ASC LIMIT 3", tableName)
        );
        
        assertNull(response.getError(), "Query failed: " + response.getError());
        assertNotNull(response.getRows(), "Rows should not be null");
        assertEquals(3, response.getRows().size(), "Expected 3 rows, got: " + response.getRows().size());
        
        // Verify we got the 3 smallest values in ascending order
        List<Map<String, Object>> rows = response.getRows();
        
        // Debug: print actual values
        System.out.println("Actual rows returned:");
        for (int i = 0; i < rows.size(); i++) {
            System.out.println("  Row " + i + ": id=" + rows.get(i).get("id") + ", amount=" + rows.get(i).get("amount"));
        }
        
        assertEquals(0, ((Number) rows.get(0).get("amount")).intValue(), "First row should have amount=0");
        assertEquals(10, ((Number) rows.get(1).get("amount")).intValue(), "Second row should have amount=10");
        assertEquals(20, ((Number) rows.get(2).get("amount")).intValue(), "Third row should have amount=20");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testOrderByTextColumn() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        // Create table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, city TEXT)",
            tableName
        ));
        
        // Insert test data
        queryService.execute(String.format("INSERT INTO %s (id, city) VALUES (1, 'New York')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, city) VALUES (2, 'Los Angeles')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, city) VALUES (3, 'Chicago')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, city) VALUES (4, 'Houston')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, city) VALUES (5, 'Phoenix')", tableName));
        
        // Query with ORDER BY on text column
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s ORDER BY city ASC", tableName)
        );
        
        assertNull(response.getError());
        assertEquals(5, response.getRows().size());
        
        // Verify alphabetical order
        List<Map<String, Object>> rows = response.getRows();
        assertEquals("Chicago", rows.get(0).get("city"));
        assertEquals("Houston", rows.get(1).get("city"));
        assertEquals("Los Angeles", rows.get(2).get("city"));
        assertEquals("New York", rows.get(3).get("city"));
        assertEquals("Phoenix", rows.get(4).get("city"));
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testOrderByWithNullValues() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        // Create table with nullable column
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, score INT)",
            tableName
        ));
        
        // Insert test data with NULL values
        queryService.execute(String.format("INSERT INTO %s (id, score) VALUES (1, 100)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, score) VALUES (2, NULL)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, score) VALUES (3, 50)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, score) VALUES (4, NULL)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, score) VALUES (5, 75)", tableName));
        
        // Query with ORDER BY (NULLs should come first in ASC order)
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s ORDER BY score ASC", tableName)
        );
        
        assertNull(response.getError());
        assertEquals(5, response.getRows().size());
        
        // Verify NULL values come first
        List<Map<String, Object>> rows = response.getRows();
        assertNull(rows.get(0).get("score"), "First value should be NULL");
        assertNull(rows.get(1).get("score"), "Second value should be NULL");
        assertEquals(50, ((Number) rows.get(2).get("score")).intValue());
        assertEquals(75, ((Number) rows.get(3).get("score")).intValue());
        assertEquals(100, ((Number) rows.get(4).get("score")).intValue());
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testOrderByPrimaryKey() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        // Create table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, data TEXT)",
            tableName
        ));
        
        // Insert in random order
        queryService.execute(String.format("INSERT INTO %s (id, data) VALUES (5, 'five')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, data) VALUES (2, 'two')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, data) VALUES (8, 'eight')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, data) VALUES (1, 'one')", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, data) VALUES (3, 'three')", tableName));
        
        // Query with ORDER BY on primary key
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s ORDER BY id DESC", tableName)
        );
        
        assertNull(response.getError());
        assertEquals(5, response.getRows().size());
        
        // Verify descending order by id
        List<Map<String, Object>> rows = response.getRows();
        assertEquals(8, ((Number) rows.get(0).get("id")).intValue());
        assertEquals(5, ((Number) rows.get(1).get("id")).intValue());
        assertEquals(3, ((Number) rows.get(2).get("id")).intValue());
        assertEquals(2, ((Number) rows.get(3).get("id")).intValue());
        assertEquals(1, ((Number) rows.get(4).get("id")).intValue());
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testOrderByMixedAscDesc() throws Exception {
        String tableName = uniqueTableName("order_test");
        
        // Create table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, team TEXT, score INT)",
            tableName
        ));
        
        // Insert test data
        queryService.execute(String.format("INSERT INTO %s (id, team, score) VALUES (1, 'A', 100)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, team, score) VALUES (2, 'B', 90)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, team, score) VALUES (3, 'A', 80)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, team, score) VALUES (4, 'B', 95)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, team, score) VALUES (5, 'A', 110)", tableName));
        
        // Query with mixed ASC/DESC: team ASC, score DESC
        QueryResponse response = queryService.execute(
            String.format("SELECT * FROM %s ORDER BY team ASC, score DESC", tableName)
        );
        
        assertNull(response.getError());
        assertEquals(5, response.getRows().size());
        
        // Verify: Team A first (sorted by score DESC), then Team B (sorted by score DESC)
        List<Map<String, Object>> rows = response.getRows();
        assertEquals("A", rows.get(0).get("team"));
        assertEquals(110, ((Number) rows.get(0).get("score")).intValue());
        assertEquals("A", rows.get(1).get("team"));
        assertEquals(100, ((Number) rows.get(1).get("score")).intValue());
        assertEquals("A", rows.get(2).get("team"));
        assertEquals(80, ((Number) rows.get(2).get("score")).intValue());
        assertEquals("B", rows.get(3).get("team"));
        assertEquals(95, ((Number) rows.get(3).get("score")).intValue());
        assertEquals("B", rows.get(4).get("team"));
        assertEquals(90, ((Number) rows.get(4).get("score")).intValue());
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
}

