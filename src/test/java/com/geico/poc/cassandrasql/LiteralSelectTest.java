package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SELECT with literals (no FROM clause) - KV mode.
 * 
 * PostgreSQL allows queries like:
 * - SELECT 1
 * - SELECT 1, 'test', true
 * - SELECT 1 + 1
 * 
 * These are commonly used for:
 * - Connection testing (pgbench uses SELECT 1)
 * - Constant value generation
 * - Expression evaluation
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LiteralSelectTest extends KvTestBase {
    
    @Test
    @Order(1)
    public void testSelectSingleLiteral() throws Exception {
        System.out.println("\n=== TEST: SELECT 1 ===");
        
        // This is the most common pattern for connection testing
        QueryResponse response = queryService.execute("SELECT 1");
        
        assertFalse(response.getError() != null, "SELECT 1 should not error: " + response.getError());
        assertNotNull(response.getRows(), "Should return rows");
        assertEquals(1, response.getRows().size(), "Should return 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        assertNotNull(row, "Row should not be null");
        assertTrue(row.size() > 0, "Row should have at least one column");
        
        // The value should be 1 (column name may vary)
        Object value = row.values().iterator().next();
        assertEquals(1, ((Number) value).intValue(), "Value should be 1");
        
        System.out.println("  Result: " + response.getRows());
        System.out.println("✅ PASS: SELECT 1 works");
    }
    
    @Test
    @Order(2)
    public void testSelectMultipleLiterals() throws Exception {
        System.out.println("\n=== TEST: SELECT 1, 'test', true ===");
        
        QueryResponse response = queryService.execute("SELECT 1, 'test', true");
        
        assertFalse(response.getError() != null, "SELECT with multiple literals should not error: " + response.getError());
        assertNotNull(response.getRows(), "Should return rows");
        assertEquals(1, response.getRows().size(), "Should return 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        assertEquals(3, row.size(), "Should have 3 columns");
        
        System.out.println("  Result: " + response.getRows());
        System.out.println("✅ PASS: SELECT with multiple literals works");
    }
    
    @Test
    @Disabled("Not implemented")
    @Order(3)
    public void testSelectLiteralWithAlias() throws Exception {
        System.out.println("\n=== TEST: SELECT 1 AS one ===");
        
        QueryResponse response = queryService.execute("SELECT 1 AS one");
        
        assertFalse(response.getError() != null, "SELECT 1 AS one should not error: " + response.getError());
        assertNotNull(response.getRows(), "Should return rows");
        assertEquals(1, response.getRows().size(), "Should return 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        assertTrue(row.containsKey("one"), "Should have column 'one'");
        assertEquals(1, ((Number) row.get("one")).intValue(), "Value should be 1");
        
        System.out.println("  Result: " + response.getRows());
        System.out.println("✅ PASS: SELECT 1 AS one works");
    }
    
    @Test
    @Order(4)
    public void testSelectExpression() throws Exception {
        System.out.println("\n=== TEST: SELECT 1 + 1 ===");
        
        QueryResponse response = queryService.execute("SELECT 1 + 1");
        
        assertFalse(response.getError() != null, "SELECT 1 + 1 should not error: " + response.getError());
        assertNotNull(response.getRows(), "Should return rows");
        assertEquals(1, response.getRows().size(), "Should return 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        Object value = row.values().iterator().next();
        assertEquals(2, ((Number) value).intValue(), "Value should be 2");
        
        System.out.println("  Result: " + response.getRows());
        System.out.println("✅ PASS: SELECT 1 + 1 works");
    }
    
    @Test
    @Order(5)
    public void testSelectNull() throws Exception {
        System.out.println("\n=== TEST: SELECT NULL ===");
        
        QueryResponse response = queryService.execute("SELECT NULL");
        
        assertFalse(response.getError() != null, "SELECT NULL should not error: " + response.getError());
        assertNotNull(response.getRows(), "Should return rows");
        assertEquals(1, response.getRows().size(), "Should return 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        Object value = row.values().iterator().next();
        assertNull(value, "Value should be NULL");
        
        System.out.println("  Result: " + response.getRows());
        System.out.println("✅ PASS: SELECT NULL works");
    }
    
    @Test
    @Order(6)
    public void testSelectString() throws Exception {
        System.out.println("\n=== TEST: SELECT 'hello' ===");
        
        QueryResponse response = queryService.execute("SELECT 'hello'");
        
        assertFalse(response.getError() != null, "SELECT 'hello' should not error: " + response.getError());
        assertNotNull(response.getRows(), "Should return rows");
        assertEquals(1, response.getRows().size(), "Should return 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        Object value = row.values().iterator().next();
        assertEquals("hello", value, "Value should be 'hello'");
        
        System.out.println("  Result: " + response.getRows());
        System.out.println("✅ PASS: SELECT 'hello' works");
    }
}

