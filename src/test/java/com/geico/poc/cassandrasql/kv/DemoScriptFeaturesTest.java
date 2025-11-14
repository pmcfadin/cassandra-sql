package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test of demo script features:
 * - String concatenation (||)
 * - CASE expressions
 * - Arithmetic expressions
 * - UNION/UNION ALL
 * - Materialized views
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DemoScriptFeaturesTest extends KvTestBase {
    
    @Test
    @Order(1)
    public void testStringConcatenationAndCaseExpression() throws Exception {
        String tableName = uniqueTableName("demo_customers");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, first_name TEXT, last_name TEXT, loyalty_points INT)",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 'Alice', 'Johnson', 320), (2, 'Bob', 'Smith', 137), (3, 'Carol', 'Williams', 250)",
            tableName
        ));
        Thread.sleep(200);
        
        // Test string concatenation + CASE expression
        QueryResponse result = queryService.execute(String.format(
            "SELECT " +
            "  id, " +
            "  first_name || ' ' || last_name AS full_name, " +
            "  loyalty_points, " +
            "  CASE " +
            "    WHEN loyalty_points >= 300 THEN 'Gold' " +
            "    WHEN loyalty_points >= 200 THEN 'Silver' " +
            "    ELSE 'Bronze' " +
            "  END AS tier " +
            "FROM %s",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(3, result.getRows().size(), "Should return 3 rows");
        
        // Verify string concatenation works
        boolean foundAlice = false, foundBob = false, foundCarol = false;
        for (var row : result.getRows()) {
            String fullName = (String) row.get("full_name");
            assertNotNull(fullName, "full_name should not be null");
            assertFalse(fullName.contains("||"), "full_name should not contain || operator");
            assertFalse(fullName.contains("`"), "full_name should not contain backticks");
            
            if (fullName.equals("Alice Johnson")) foundAlice = true;
            if (fullName.equals("Bob Smith")) foundBob = true;
            if (fullName.equals("Carol Williams")) foundCarol = true;
        }
        assertTrue(foundAlice && foundBob && foundCarol, "Should have all three concatenated names");
        
        // Verify CASE expression works
        boolean foundGold = false, foundSilver = false, foundBronze = false;
        for (var row : result.getRows()) {
            String tier = (String) row.get("tier");
            assertNotNull(tier, "tier should not be null");
            assertFalse(tier.contains("CASE"), "tier should not contain CASE keyword");
            
            if (tier.equals("Gold")) foundGold = true;
            if (tier.equals("Silver")) foundSilver = true;
            if (tier.equals("Bronze")) foundBronze = true;
        }
        assertTrue(foundGold && foundSilver && foundBronze, "Should have all three tier levels");
        
        // Table is automatically cleaned up by KvTestBase
        registerTable(tableName);
    }
    
    @Test
    @Order(2)
    public void testArithmeticExpressionsAndUnion() throws Exception {
        String ordersTable = uniqueTableName("demo_orders");
        String productsTable = uniqueTableName("demo_products");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, total DECIMAL)",
            ordersTable
        ));
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, price DECIMAL, cost DECIMAL)",
            productsTable
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 1000.00), (2, 500.00)",
            ordersTable
        ));
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 100.00, 60.00)",
            productsTable
        ));
        Thread.sleep(200);
        
        // Test arithmetic expressions
        QueryResponse arithmeticResult = queryService.execute(String.format(
            "SELECT id, total, total * 0.1 AS tax, total * 1.1 AS total_with_tax FROM %s",
            ordersTable
        ));
        
        assertNull(arithmeticResult.getError(), "Arithmetic query should succeed");
        assertTrue(arithmeticResult.getColumns().contains("tax"), "Should have tax column");
        assertTrue(arithmeticResult.getColumns().contains("total_with_tax"), "Should have total_with_tax column");
        
        // Test UNION ALL
        // Note: Both SELECTs need explicit aliases for UNION to work correctly
        QueryResponse unionResult = queryService.execute(String.format(
            "SELECT 'Orders' AS table_name, COUNT(*) AS row_count FROM %s " +
            "UNION ALL " +
            "SELECT 'Products' AS table_name, COUNT(*) AS row_count FROM %s",
            ordersTable, productsTable
        ));
        
        assertNull(unionResult.getError(), "UNION query should succeed: " + unionResult.getError());
        assertEquals(2, unionResult.getRows().size(), "UNION should return 2 rows");
        
        // Verify both rows have data
        for (var row : unionResult.getRows()) {
            assertNotNull(row.get("table_name"), "table_name should not be null. Row keys: " + row.keySet());
            assertNotNull(row.get("row_count"), "row_count should not be null. Row keys: " + row.keySet());
            
            String tableName = (String) row.get("table_name");
            assertTrue(tableName.equals("Orders") || tableName.equals("Products"), 
                "table_name should be Orders or Products, got: " + tableName);
        }
        
        // Tables are automatically cleaned up by KvTestBase
        registerTable(ordersTable);
        registerTable(productsTable);
    }
    
    @Test
    @Order(3)
    public void testMaterializedViewWithAggregates() throws Exception {
        String ordersTable = uniqueTableName("demo_mv_orders");
        String viewName = uniqueTableName("demo_mv_stats");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, customer_id INT, total DECIMAL)",
            ordersTable
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 100, 1000.00), (2, 100, 500.00), (3, 200, 300.00)",
            ordersTable
        ));
        Thread.sleep(200);
        
        // Create materialized view
        QueryResponse createResult = queryService.execute(String.format(
            "CREATE MATERIALIZED VIEW %s AS " +
            "SELECT customer_id, COUNT(id) AS order_count, SUM(total) AS total_spent " +
            "FROM %s GROUP BY customer_id",
            viewName, ordersTable
        ));
        
        assertNull(createResult.getError(), "CREATE MATERIALIZED VIEW should succeed: " + createResult.getError());
        
        // Query the view
        QueryResponse viewResult = queryService.execute(String.format(
            "SELECT * FROM %s",
            viewName
        ));
        
        assertNull(viewResult.getError(), "Query view should succeed");
        assertEquals(2, viewResult.getRows().size(), "View should have 2 rows (2 customers)");
        
        // Verify columns are present
        assertTrue(viewResult.getColumns().contains("customer_id"), "Should have customer_id column");
        assertTrue(viewResult.getColumns().contains("order_count"), "Should have order_count column");
        assertTrue(viewResult.getColumns().contains("total_spent"), "Should have total_spent column");
        
        // Verify data is not empty
        for (var row : viewResult.getRows()) {
            assertNotNull(row.get("customer_id"), "customer_id should not be null");
            assertNotNull(row.get("order_count"), "order_count should not be null");
            assertNotNull(row.get("total_spent"), "total_spent should not be null");
        }
        
        // Test REFRESH
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (4, 100, 200.00)",
            ordersTable
        ));
        Thread.sleep(200);
        
        QueryResponse refreshResult = queryService.execute(String.format(
            "REFRESH MATERIALIZED VIEW %s",
            viewName
        ));
        
        assertNull(refreshResult.getError(), "REFRESH should succeed: " + refreshResult.getError());
        
        // Query again to verify refresh worked
        QueryResponse afterRefresh = queryService.execute(String.format(
            "SELECT * FROM %s WHERE customer_id = 100",
            viewName
        ));
        
        assertNull(afterRefresh.getError(), "Query after refresh should succeed");
        assertEquals(1, afterRefresh.getRows().size(), "Should have 1 row for customer 100");
        
        // Customer 100 should now have 3 orders and total 1700
        var row = afterRefresh.getRows().get(0);
        assertEquals(3, ((Number) row.get("order_count")).intValue(), "Customer 100 should have 3 orders");
        assertEquals(1700.0, ((Number) row.get("total_spent")).doubleValue(), 0.01, "Customer 100 should have total 1700");
        
        // Cleanup - materialized views need manual cleanup
        queryService.execute("DROP MATERIALIZED VIEW IF EXISTS " + viewName);
        registerTable(ordersTable);
    }
}

