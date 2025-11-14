package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for EXPLAIN and EXPLAIN ANALYZE functionality.
 * Tests single SELECT, JOIN, subquery, multi-way JOIN, and aggregation queries.
 */
public class ExplainTest extends KvTestBase {
    
    @BeforeEach
    public void setup() throws Exception {
        // Ensure schema cache is fresh
        Thread.sleep(100); // Allow propagation
    }
    
    @Test
    @Order(1)
    public void testExplainSimpleSelect() throws Exception {
        // Create test table
        String tableName = uniqueTableName("explain_test");
        QueryResponse createResp = queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name TEXT, age INT)");
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());
        
        // Wait for schema propagation
        Thread.sleep(100);
        
        // Verify table exists (should be in cache after CREATE TABLE)
        assertNotNull(schemaManager.getTable(tableName), "Table should exist in SchemaManager");
        
        // Insert some data
        queryService.execute("INSERT INTO " + tableName + " (id, name, age) VALUES (1, 'Alice', 30)");
        queryService.execute("INSERT INTO " + tableName + " (id, name, age) VALUES (2, 'Bob', 25)");
        
        // Test EXPLAIN
        QueryResponse response = queryService.execute("EXPLAIN SELECT * FROM " + tableName);
        
        assertNotNull(response);
        assertNull(response.getError(), "EXPLAIN should not error: " + response.getError());
        assertNotNull(response.getRows());
        assertTrue(response.getRows().size() > 0, "EXPLAIN should return plan output");
        
        // Check that plan contains expected sections
        String planText = extractPlanText(response);
        assertTrue(planText.contains("Query:"), "Plan should show query");
        assertTrue(planText.contains("Parse: SUCCESS"), "Plan should show parse success");
        assertTrue(planText.contains("Validate: SUCCESS"), "Plan should show validate success");
        assertTrue(planText.contains("Logical Plan"), "Plan should show logical plan");
        assertTrue(planText.contains("Optimized Plan"), "Plan should show optimized plan");
        assertTrue(planText.contains("Cost Estimates"), "Plan should show cost estimates");
        assertTrue(planText.contains("Cost-Based Optimizer: ENABLED"), "Plan should show CBO is enabled");
        
        System.out.println("✅ EXPLAIN output:");
        System.out.println(planText);
    }
    
    @Test
    @Order(2)
    public void testExplainAnalyze() throws Exception {
        // Create test table
        String tableName = uniqueTableName("explain_analyze_test");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, v INT)");
        
        Thread.sleep(100);
        
        // Insert test data
        for (int i = 1; i <= 10; i++) {
            queryService.execute("INSERT INTO " + tableName + " (id, value) v (" + i + ", " + (i * 10) + ")");
        }
        
        // Test EXPLAIN ANALYZE
        QueryResponse response = queryService.execute("EXPLAIN ANALYZE SELECT * FROM " + tableName + " WHERE v > 50");
        
        assertNotNull(response);
        assertNull(response.getError(), "EXPLAIN ANALYZE should not error: " + response.getError());
        assertNotNull(response.getRows());
        assertTrue(response.getRows().size() > 0, "EXPLAIN ANALYZE should return plan output");
        
        // Check that plan contains execution statistics
        String planText = extractPlanText(response);
        System.out.println("EXPLAIN ANALYZE output: " + planText);
        assertTrue(planText.contains("Execution Statistics"), "Plan should show execution stats");
        assertTrue(planText.contains("Execution Time:"), "Plan should show execution time");
        assertTrue(planText.contains("Rows Returned:"), "Plan should show rows returned");
        assertTrue(planText.contains("Memory Used:"), "Plan should show memory used");
        
        System.out.println("✅ EXPLAIN ANALYZE output:");
        System.out.println(planText);
    }
    
    @Test
    @Order(3)
    public void testExplainJoin() throws Exception {
        // Create test tables
        String table1 = uniqueTableName("users");
        String table2 = uniqueTableName("orders");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, user_id INT, amount INT)");
        
        Thread.sleep(200);
        
        // Insert test data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + table2 + " (id, user_id, amount) VALUES (1, 1, 100)");
        queryService.execute("INSERT INTO " + table2 + " (id, user_id, amount) VALUES (2, 1, 200)");
        
        // Test EXPLAIN on JOIN query
        String joinSql = "SELECT u.name, o.amount FROM " + table1 + " u JOIN " + table2 + " o ON u.id = o.user_id";
        QueryResponse response = queryService.execute("EXPLAIN " + joinSql);
        
        assertNotNull(response);
        assertNull(response.getError(), "EXPLAIN JOIN should not error: " + response.getError());
        
        String planText = extractPlanText(response);
        System.out.println("EXPLAIN JOIN output: " + planText);
        assertTrue(planText.contains("Logical Plan"), "Plan should show logical plan for JOIN");
        assertTrue(planText.contains("Optimized Plan"), "Plan should show optimized plan for JOIN");
        
        System.out.println("✅ EXPLAIN JOIN output:");
        System.out.println(planText);
    }
    
    @Test
    @Order(4)
    public void testExplainAggregation() throws Exception {
        // Create test table
        String tableName = uniqueTableName("sales");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, category TEXT, amount INT)");
        
        Thread.sleep(200);
        
        // Insert test data
        queryService.execute("INSERT INTO " + tableName + " (id, category, amount) VALUES (1, 'A', 100)");
        queryService.execute("INSERT INTO " + tableName + " (id, category, amount) VALUES (2, 'A', 150)");
        queryService.execute("INSERT INTO " + tableName + " (id, category, amount) VALUES (3, 'B', 200)");
        
        // Test EXPLAIN on aggregation query
        String aggSql = "SELECT category, SUM(amount) as total FROM " + tableName + " GROUP BY category";
        QueryResponse response = queryService.execute("EXPLAIN " + aggSql);
        
        assertNotNull(response);
        assertNull(response.getError(), "EXPLAIN aggregation should not error: " + response.getError());
        
        String planText = extractPlanText(response);
        assertTrue(planText.contains("Logical Plan"), "Plan should show logical plan for aggregation");
        
        System.out.println("✅ EXPLAIN aggregation output:");
        System.out.println(planText);
    }
    
    @Test
    @Order(5)
    public void testExplainSubquery() throws Exception {
        // Create test tables
        String outerTable = uniqueTableName("orders");
        String innerTable = uniqueTableName("customers");
        
        queryService.execute("CREATE TABLE " + outerTable + " (id INT PRIMARY KEY, customer_id INT, amount INT)");
        queryService.execute("CREATE TABLE " + innerTable + " (id INT PRIMARY KEY, name TEXT, city TEXT)");
        
        Thread.sleep(200);
        
        // Insert test data
        queryService.execute("INSERT INTO " + outerTable + " (id, customer_id, amount) VALUES (1, 1, 100)");
        queryService.execute("INSERT INTO " + outerTable + " (id, customer_id, amount) VALUES (2, 2, 200)");
        queryService.execute("INSERT INTO " + innerTable + " (id, name, city) VALUES (1, 'Alice', 'NYC')");
        queryService.execute("INSERT INTO " + innerTable + " (id, name, city) VALUES (2, 'Bob', 'LA')");
        
        // Test EXPLAIN on subquery
        String subquerySql = "SELECT * FROM " + outerTable + 
            " WHERE customer_id IN (SELECT id FROM " + innerTable + " WHERE city = 'NYC')";
        QueryResponse response = queryService.execute("EXPLAIN " + subquerySql);
        
        assertNotNull(response);
        assertNull(response.getError(), "EXPLAIN subquery should not error: " + response.getError());
        
        String planText = extractPlanText(response);
        assertTrue(planText.contains("Logical Plan"), "Plan should show logical plan for subquery");
        
        System.out.println("✅ EXPLAIN subquery output:");
        System.out.println(planText);
    }
    
    @Test
    @Order(6)
    public void testExplainMultiWayJoin() throws Exception {
        // Create test tables
        String table1 = uniqueTableName("users");
        String table2 = uniqueTableName("orders");
        String table3 = uniqueTableName("products");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, user_id INT, product_id INT)");
        queryService.execute("CREATE TABLE " + table3 + " (id INT PRIMARY KEY, name TEXT, price INT)");
        
        Thread.sleep(200);
        
        // Insert test data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table2 + " (id, user_id, product_id) VALUES (1, 1, 1)");
        queryService.execute("INSERT INTO " + table3 + " (id, name, price) VALUES (1, 'Widget', 100)");
        
        // Test EXPLAIN on 3-way JOIN
        String joinSql = "SELECT u.name, p.name as product, p.price FROM " + table1 + " u " +
            "JOIN " + table2 + " o ON u.id = o.user_id " +
            "JOIN " + table3 + " p ON o.product_id = p.id";
        QueryResponse response = queryService.execute("EXPLAIN " + joinSql);
        
        assertNotNull(response);
        assertNull(response.getError(), "EXPLAIN multi-way JOIN should not error: " + response.getError());
        
        String planText = extractPlanText(response);
        assertTrue(planText.contains("Logical Plan"), "Plan should show logical plan for multi-way JOIN");
        
        System.out.println("✅ EXPLAIN multi-way JOIN output:");
        System.out.println(planText);
    }
    
    @Test
    @Order(7)
    public void testExplainWithIndex() throws Exception {
        // Create test table with index
        String tableName = uniqueTableName("indexed_table");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name TEXT, age INT)");
        queryService.execute("CREATE INDEX idx_age ON " + tableName + " (age)");
        
        Thread.sleep(200);
        
        // Insert test data
        for (int i = 1; i <= 20; i++) {
            queryService.execute("INSERT INTO " + tableName + " (id, name, age) VALUES (" + i + ", 'User" + i + "', " + (20 + i) + ")");
        }
        
        // Test EXPLAIN on indexed column
        QueryResponse response = queryService.execute("EXPLAIN SELECT * FROM " + tableName + " WHERE age > 30");
        
        assertNotNull(response);
        assertNull(response.getError(), "EXPLAIN with index should not error: " + response.getError());
        
        String planText = extractPlanText(response);
        System.out.println("✅ EXPLAIN with index output:");
        System.out.println(planText);
        
        // The plan should show the optimizer considered the index
        // (exact output depends on optimizer implementation)
    }
    
    /**
     * Extract plan text from EXPLAIN response
     */
    private String extractPlanText(QueryResponse response) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : response.getRows()) {
            Object planLine = row.get("QUERY PLAN");
            if (planLine == null) {
                planLine = row.get("query plan");
            }
            if (planLine != null) {
                sb.append(planLine.toString()).append("\n");
            }
        }
        return sb.toString();
    }
}

