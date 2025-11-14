package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CASE expression evaluation in SELECT statements
 */
@SpringBootTest
@ActiveProfiles("kv")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CaseExpressionTest {
    
    @Autowired
    private QueryService queryService;
    
    private static int testCounter = 0;
    
    private String uniqueTableName(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + (testCounter++);
    }
    
    @Test
    @Order(1)
    public void testSimpleCaseExpression() throws Exception {
        String tableName = uniqueTableName("case_test");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, amount INT)",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 100)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 50)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (3, 200)", tableName));
        Thread.sleep(200);
        
        // Test CASE expression in SELECT
        QueryResponse result = queryService.execute(String.format(
            "SELECT id, amount, " +
            "CASE " +
            "  WHEN amount >= 100 THEN 'HIGH' " +
            "  WHEN amount >= 50 THEN 'MEDIUM' " +
            "  ELSE 'LOW' " +
            "END AS category " +
            "FROM %s ORDER BY id",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(3, result.getRows().size(), "Should return 3 rows");
        
        // Verify CASE expression was evaluated
        assertTrue(result.getColumns().contains("category"), "Should have 'category' column");
        assertEquals("HIGH", result.getRows().get(0).get("category"), "Row 1 should be HIGH");
        assertEquals("MEDIUM", result.getRows().get(1).get("category"), "Row 2 should be MEDIUM");
        assertEquals("HIGH", result.getRows().get(2).get("category"), "Row 3 should be HIGH");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(2)
    public void testCaseWithArithmetic() throws Exception {
        String tableName = uniqueTableName("case_arith");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, price DECIMAL(10,2), cost DECIMAL(10,2))",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 100.00, 60.00)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 50.00, 45.00)", tableName));
        Thread.sleep(200);
        
        // CASE with arithmetic expression
        QueryResponse result = queryService.execute(String.format(
            "SELECT id, price, cost, " +
            "CASE " +
            "  WHEN (price - cost) > 20 THEN 'Profitable' " +
            "  WHEN (price - cost) > 0 THEN 'Break-even' " +
            "  ELSE 'Loss' " +
            "END AS status " +
            "FROM %s ORDER BY id",
            tableName
        ));
        System.out.println(result.getRows());
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertTrue(result.getColumns().contains("status"), "Should have 'status' column");
        assertEquals("Profitable", result.getRows().get(0).get("status"));
        assertEquals("Break-even", result.getRows().get(1).get("status"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(3)
    public void testMultipleCaseExpressions() throws Exception {
        String tableName = uniqueTableName("multi_case");
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, score INT, attendance INT)",
            tableName
        ));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 95, 100)", tableName));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 75, 80)", tableName));
        Thread.sleep(200);
        
        // Multiple CASE expressions in same query
        QueryResponse result = queryService.execute(String.format(
            "SELECT id, " +
            "CASE WHEN score >= 90 THEN 'A' WHEN score >= 80 THEN 'B' ELSE 'C' END AS grade, " +
            "CASE WHEN attendance >= 90 THEN 'Good' ELSE 'Poor' END AS attendance_status " +
            "FROM %s ORDER BY id",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertTrue(result.getColumns().contains("grade"), "Should have 'grade' column");
        assertTrue(result.getColumns().contains("attendance_status"), "Should have 'attendance_status' column");
        
        assertEquals("A", result.getRows().get(0).get("grade"));
        assertEquals("Good", result.getRows().get(0).get("attendance_status"));
        assertEquals("C", result.getRows().get(1).get("grade"));
        assertEquals("Poor", result.getRows().get(1).get("attendance_status"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
}

