package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SELECT with literals (no FROM clause) - KV mode.
 * 
 * This is critical for tools like pgbench which use "SELECT 1" to check connectivity.
 * PostgreSQL allows SELECT without FROM clause to return literal values.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SelectLiteralTest extends KvTestBase {
    
    @Test
    @Order(1)
    public void testSelectIntegerLiteral() throws Exception {
        System.out.println("\n=== TEST: SELECT 1 ===");
        
        QueryResponse response = queryService.execute("SELECT 1");
        
        assertNull(response.getError(), "SELECT 1 should succeed: " + response.getError());
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        assertNotNull(response.getColumns(), "Should have columns");
        assertEquals(1, response.getColumns().size(), "Should have 1 column");
        
        // Check the value
        Object value = response.getRows().get(0).values().iterator().next();
        assertEquals(1, ((Number) value).intValue(), "Value should be 1");
        
        System.out.println("✅ PASS: SELECT 1 works");
    }
    
    @Test
    @Order(2)
    public void testSelectStringLiteral() throws Exception {
        System.out.println("\n=== TEST: SELECT 'hello' ===");
        
        QueryResponse response = queryService.execute("SELECT 'hello'");
        
        assertNull(response.getError(), "SELECT 'hello' should succeed: " + response.getError());
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Object value = response.getRows().get(0).values().iterator().next();
        assertEquals("hello", value.toString(), "Value should be 'hello'");
        
        System.out.println("✅ PASS: SELECT 'hello' works");
    }
    
    @Test
    @Order(3)
    public void testSelectMultipleLiterals() throws Exception {
        System.out.println("\n=== TEST: SELECT 1, 'test', 3.14 ===");
        
        QueryResponse response = queryService.execute("SELECT 1, 'test', 3.14");
        
        assertNull(response.getError(), "SELECT multiple literals should succeed: " + response.getError());
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        assertEquals(3, response.getColumns().size(), "Should have 3 columns");
        
        System.out.println("✅ PASS: SELECT multiple literals works");
    }
    
    @Test
    @Disabled("Not implemented")
    @Order(4)
    public void testSelectLiteralWithAlias() throws Exception {
        System.out.println("\n=== TEST: SELECT 1 AS one ===");
        
        QueryResponse response = queryService.execute("SELECT 1 AS one");
        
        assertNull(response.getError(), "SELECT 1 AS one should succeed: " + response.getError());
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        assertTrue(response.getColumns().contains("one") || 
                   response.getColumns().get(0).equalsIgnoreCase("one"), 
                   "Column should be named 'one'");
        
        System.out.println("✅ PASS: SELECT 1 AS one works");
    }
    
    @Test
    @Order(5)
    public void testSelectExpression() throws Exception {
        System.out.println("\n=== TEST: SELECT 1 + 1 ===");
        
        QueryResponse response = queryService.execute("SELECT 1 + 1");
        
        assertNull(response.getError(), "SELECT 1 + 1 should succeed: " + response.getError());
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Object value = response.getRows().get(0).values().iterator().next();
        assertEquals(2, ((Number) value).intValue(), "Value should be 2");
        
        System.out.println("✅ PASS: SELECT 1 + 1 works");
    }
    
    @Test
    @Order(6)
    public void testSelectNull() throws Exception {
        System.out.println("\n=== TEST: SELECT NULL ===");
        
        QueryResponse response = queryService.execute("SELECT NULL");
        
        assertNull(response.getError(), "SELECT NULL should succeed: " + response.getError());
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Object value = response.getRows().get(0).values().iterator().next();
        assertNull(value, "Value should be NULL");
        
        System.out.println("✅ PASS: SELECT NULL works");
    }
    
    @Test
    @Order(7)
    public void testSelectBoolean() throws Exception {
        System.out.println("\n=== TEST: SELECT TRUE, FALSE ===");
        
        QueryResponse response = queryService.execute("SELECT TRUE, FALSE");
        
        assertNull(response.getError(), "SELECT TRUE, FALSE should succeed: " + response.getError());
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        assertEquals(2, response.getColumns().size(), "Should have 2 columns");
        
        System.out.println("✅ PASS: SELECT TRUE, FALSE works");
    }
    
    @Test
    @Order(8)
    public void testPgbenchConnectivityCheck() throws Exception {
        System.out.println("\n=== TEST: pgbench connectivity check (SELECT 1) ===");
        
        // This is exactly what pgbench uses to check if PostgreSQL is responding
        QueryResponse response = queryService.execute("SELECT 1");
        
        assertNull(response.getError(), "pgbench connectivity check should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertFalse(response.getRows().isEmpty(), "Should have at least one row");
        
        System.out.println("✅ PASS: pgbench connectivity check works");
    }
}

