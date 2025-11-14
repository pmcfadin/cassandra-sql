package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for arithmetic expressions in UPDATE and SELECT statements
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ArithmeticExpressionsTest extends KvTestBase {
    
    private String testTable;
    
    @BeforeEach
    public void setupTable() throws Exception {
        testTable = uniqueTableName("arith_test");
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, balance INT, quantity INT, price DOUBLE)");
        
        // Insert test data
        queryService.execute("INSERT INTO " + testTable + " (id, balance, quantity, price) VALUES (1, 100, 10, 9.99)");
        queryService.execute("INSERT INTO " + testTable + " (id, balance, quantity, price) VALUES (2, 200, 20, 19.99)");
        queryService.execute("INSERT INTO " + testTable + " (id, balance, quantity, price) VALUES (3, 300, 30, 29.99)");
    }
    
    // ========================================
    // Test 1: Addition in UPDATE
    // ========================================
    
    @Test
    @Order(1)
    public void testUpdateWithAddition() throws Exception {
        System.out.println("\n=== TEST 1: UPDATE with Addition ===\n");
        
        // Update: balance = balance + 50
        String sql = "UPDATE " + testTable + " SET balance = balance + 50 WHERE id = 1";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UPDATE with addition should succeed: " + response.getError());
        
        // Verify the update
        sql = "SELECT balance FROM " + testTable + " WHERE id = 1";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        assertEquals(150, ((Number) response.getRows().get(0).get("balance")).intValue(), "Balance should be 100 + 50 = 150");
        
        System.out.println("✅ Addition in UPDATE works correctly");
    }
    
    // ========================================
    // Test 2: Subtraction in UPDATE
    // ========================================
    
    @Test
    @Order(2)
    public void testUpdateWithSubtraction() throws Exception {
        System.out.println("\n=== TEST 2: UPDATE with Subtraction ===\n");
        
        // Update: balance = balance - 30
        String sql = "UPDATE " + testTable + " SET balance = balance - 30 WHERE id = 2";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UPDATE with subtraction should succeed: " + response.getError());
        
        // Verify the update
        sql = "SELECT balance FROM " + testTable + " WHERE id = 2";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        assertEquals(170, ((Number) response.getRows().get(0).get("balance")).intValue(), "Balance should be 200 - 30 = 170");
        
        System.out.println("✅ Subtraction in UPDATE works correctly");
    }
    
    // ========================================
    // Test 3: Multiplication in UPDATE
    // ========================================
    
    @Test
    @Order(3)
    public void testUpdateWithMultiplication() throws Exception {
        System.out.println("\n=== TEST 3: UPDATE with Multiplication ===\n");
        
        // Update: quantity = quantity * 2
        String sql = "UPDATE " + testTable + " SET quantity = quantity * 2 WHERE id = 1";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UPDATE with multiplication should succeed: " + response.getError());
        
        // Verify the update
        sql = "SELECT quantity FROM " + testTable + " WHERE id = 1";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        assertEquals(20, ((Number) response.getRows().get(0).get("quantity")).intValue(), "Quantity should be 10 * 2 = 20");
        
        System.out.println("✅ Multiplication in UPDATE works correctly");
    }
    
    // ========================================
    // Test 4: Division in UPDATE
    // ========================================
    
    @Test
    @Order(4)
    public void testUpdateWithDivision() throws Exception {
        System.out.println("\n=== TEST 4: UPDATE with Division ===\n");
        
        // Update: quantity = quantity / 2
        String sql = "UPDATE " + testTable + " SET quantity = quantity / 2 WHERE id = 2";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UPDATE with division should succeed: " + response.getError());
        
        // Verify the update
        sql = "SELECT quantity FROM " + testTable + " WHERE id = 2";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        assertEquals(10, ((Number) response.getRows().get(0).get("quantity")).intValue(), "Quantity should be 20 / 2 = 10");
        
        System.out.println("✅ Division in UPDATE works correctly");
    }
    
    // ========================================
    // Test 5: Complex expression in UPDATE
    // ========================================
    
    @Test
    @Order(5)
    public void testUpdateWithComplexExpression() throws Exception {
        System.out.println("\n=== TEST 5: UPDATE with Complex Expression ===\n");
        
        // Update: balance = balance + (quantity * 5)
        String sql = "UPDATE " + testTable + " SET balance = balance + (quantity * 5) WHERE id = 1";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UPDATE with complex expression should succeed: " + response.getError());
        
        // Verify the update
        sql = "SELECT balance FROM " + testTable + " WHERE id = 1";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        // balance = 100 + (10 * 5) = 100 + 50 = 150
        assertEquals(150, ((Number) response.getRows().get(0).get("balance")).intValue(), "Balance should be 100 + (10 * 5) = 150");
        
        System.out.println("✅ Complex expressions in UPDATE work correctly");
    }
    
    // ========================================
    // Test 6: Multiple columns with expressions
    // ========================================
    
    @Test
    @Order(6)
    public void testUpdateMultipleColumnsWithExpressions() throws Exception {
        System.out.println("\n=== TEST 6: UPDATE Multiple Columns with Expressions ===\n");
        
        // Update: balance = balance + 100, quantity = quantity - 5
        String sql = "UPDATE " + testTable + " SET balance = balance + 100, quantity = quantity - 5 WHERE id = 3";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UPDATE multiple columns should succeed: " + response.getError());
        
        // Verify the update
        sql = "SELECT balance, quantity FROM " + testTable + " WHERE id = 3";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        Map<String, Object> row = response.getRows().get(0);
        assertEquals(400, ((Number) row.get("balance")).intValue(), "Balance should be 300 + 100 = 400");
        assertEquals(25, ((Number) row.get("quantity")).intValue(), "Quantity should be 30 - 5 = 25");
        
        System.out.println("✅ Multiple column updates with expressions work correctly");
    }
    
    // ========================================
    // Test 7: Expression with literal only (no column reference)
    // ========================================
    
    @Test
    @Order(7)
    public void testUpdateWithLiteralExpression() throws Exception {
        System.out.println("\n=== TEST 7: UPDATE with Literal Expression ===\n");
        
        // Update: balance = 50 + 50
        String sql = "UPDATE " + testTable + " SET balance = 50 + 50 WHERE id = 1";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UPDATE with literal expression should succeed: " + response.getError());
        
        // Verify the update
        sql = "SELECT balance FROM " + testTable + " WHERE id = 1";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        assertEquals(100, ((Number) response.getRows().get(0).get("balance")).intValue(), "Balance should be 50 + 50 = 100");
        
        System.out.println("✅ Literal expressions in UPDATE work correctly");
    }
    
    // ========================================
    // Test 8: Pgbench-style UPDATE
    // ========================================
    
    @Test
    @Order(8)
    public void testPgbenchStyleUpdate() throws Exception {
        System.out.println("\n=== TEST 8: Pgbench-style UPDATE ===\n");
        
        // This is the type of UPDATE that pgbench does
        // UPDATE pgbench_accounts SET abalance = abalance + :delta WHERE aid = :aid
        String sql = "UPDATE " + testTable + " SET balance = balance + 75 WHERE id = 2";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Pgbench-style UPDATE should succeed: " + response.getError());
        
        // Verify the update
        sql = "SELECT balance FROM " + testTable + " WHERE id = 2";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        assertEquals(275, ((Number) response.getRows().get(0).get("balance")).intValue(), "Balance should be 200 + 75 = 275");
        
        System.out.println("✅ Pgbench-style UPDATE works correctly");
    }
}

