package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test simple literal expressions in SELECT queries
 */
public class SimpleLiteralTest extends KvTestBase {
    
    @Test
    public void testSelectWithLiteral() throws Exception {
        System.out.println("\n=== SELECT with Literal Test ===\n");
        
        String tableName = uniqueTableName("test_table");
        
        // Create table and insert data
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name VARCHAR)");
        queryService.execute("INSERT INTO " + tableName + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + tableName + " (id, name) VALUES (2, 'Bob')");
        
        // SELECT with literal
        String sql = "SELECT 'User' as type, id, name FROM " + tableName;
        
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Error: " + response.getError());
        System.out.println("Columns: " + response.getColumns());
        System.out.println("Rows: " + response.getRows());
        
        assertNull(response.getError(), "Should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(2, response.getRows().size(), "Should have 2 rows");
        
        // Check that literal column is present
        Map<String, Object> row1 = response.getRows().get(0);
        Object type = row1.get("type");
        if (type == null) type = row1.get("TYPE");
        
        assertEquals("User", type, "Literal column should have value 'User'");
        
        System.out.println("\n✅ SELECT with literal works!");
    }
    
    @Test
    public void testAggregateWithLiteral() throws Exception {
        System.out.println("\n=== Aggregate with Literal Test ===\n");
        
        String tableName = uniqueTableName("test_table");
        
        // Create table and insert data
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, val INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, val) VALUES (1, 100)");
        queryService.execute("INSERT INTO " + tableName + " (id, val) VALUES (2, 200)");
        queryService.execute("INSERT INTO " + tableName + " (id, val) VALUES (3, 300)");
        
        // Aggregate with literal
        String sql = "SELECT 'Summary' as label, COUNT(*) as row_count, SUM(val) as total FROM " + tableName;
        
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Error: " + response.getError());
        System.out.println("Columns: " + response.getColumns());
        System.out.println("Rows: " + response.getRows());
        
        assertNull(response.getError(), "Should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        
        // Check literal
        Object label = row.get("label");
        if (label == null) label = row.get("LABEL");
        assertEquals("Summary", label, "Literal should be 'Summary'");
        
        // Check aggregates
        Object count = row.get("row_count");
        if (count == null) count = row.get("ROW_COUNT");
        assertEquals(3, ((Number) count).intValue(), "Count should be 3");
        
        Object total = row.get("total");
        if (total == null) total = row.get("TOTAL");
        assertEquals(600, ((Number) total).intValue(), "Total should be 600");
        
        System.out.println("\n✅ Aggregate with literal works!");
    }
}

