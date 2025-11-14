package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test aggregation queries with WHERE clauses
 */
@SpringBootTest
public class AggregationWhereClauseTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Test
    public void testSumWithWhereClause() throws Exception {
        String tableName = uniqueTableName("sum_test");
        
        // Create table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, amount BIGINT)",
            tableName
        ));
        
        // Insert test data
        for (int i = 1; i <= 10; i++) {
            queryService.execute(String.format(
                "INSERT INTO %s (id, amount) VALUES (%d, %d)",
                tableName, i, i * 1000000
            ));
        }
        
        // Test 1: SUM without WHERE clause
        QueryResponse response1 = queryService.execute(
            String.format("SELECT SUM(amount) FROM %s", tableName)
        );
        assertNull(response1.getError(), "Query should succeed");
        assertNotNull(response1.getRows());
        assertEquals(1, response1.getRows().size());
        
        Object sumAll = response1.getRows().get(0).get("sum_amount");
        assertNotNull(sumAll, "SUM result should not be null");
        
        // Verify it's a Long, not a Double (no scientific notation)
        assertTrue(sumAll instanceof Long, 
            "SUM of integer column should return Long, got: " + sumAll.getClass().getName());
        
        // Total should be 1M + 2M + ... + 10M = 55M
        assertEquals(55000000L, ((Number) sumAll).longValue());
        
        // Test 2: SUM with WHERE clause (id = 1)
        QueryResponse response2 = queryService.execute(
            String.format("SELECT SUM(amount) FROM %s WHERE id = 1", tableName)
        );
        assertNull(response2.getError(), "Query with WHERE should succeed");
        assertNotNull(response2.getRows());
        assertEquals(1, response2.getRows().size());
        
        Object sumFiltered = response2.getRows().get(0).get("sum_amount");
        assertNotNull(sumFiltered, "Filtered SUM result should not be null");
        
        // Should only sum the one row where id=1 (amount=1000000)
        assertEquals(1000000L, ((Number) sumFiltered).longValue(),
            "WHERE clause should filter to only id=1");
        
        // Test 3: SUM with WHERE clause (id <= 5)
        QueryResponse response3 = queryService.execute(
            String.format("SELECT SUM(amount) FROM %s WHERE id <= 5", tableName)
        );
        assertNull(response3.getError());
        assertNotNull(response3.getRows());
        
        Object sumRange = response3.getRows().get(0).get("sum_amount");
        // Should sum 1M + 2M + 3M + 4M + 5M = 15M
        assertEquals(15000000L, ((Number) sumRange).longValue(),
            "WHERE clause should filter to id <= 5");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testCountWithWhereClause() throws Exception {
        String tableName = uniqueTableName("count_test");
        
        // Create table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, status TEXT)",
            tableName
        ));
        
        // Insert test data
        for (int i = 1; i <= 20; i++) {
            String status = (i % 2 == 0) ? "active" : "inactive";
            queryService.execute(String.format(
                "INSERT INTO %s (id, status) VALUES (%d, '%s')",
                tableName, i, status
            ));
        }
        
        // Test 1: COUNT without WHERE
        QueryResponse response1 = queryService.execute(
            String.format("SELECT COUNT(*) FROM %s", tableName)
        );
        assertNull(response1.getError());
        assertEquals(20L, ((Number) response1.getRows().get(0).get("count_all")).longValue());
        
        // Test 2: COUNT with WHERE clause
        QueryResponse response2 = queryService.execute(
            String.format("SELECT COUNT(*) FROM %s WHERE status = 'active'", tableName)
        );
        assertNull(response2.getError());
        
        Object countFiltered = response2.getRows().get(0).get("count_all");
        assertEquals(10L, ((Number) countFiltered).longValue(),
            "WHERE clause should filter to only 'active' rows");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testLargeSumNoScientificNotation() throws Exception {
        String tableName = uniqueTableName("large_sum_test");
        
        // Create table with BIGINT column
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, balance BIGINT)",
            tableName
        ));
        
        // Insert 1000 accounts with $1,000,000 each
        for (int i = 1; i <= 1000; i++) {
            queryService.execute(String.format(
                "INSERT INTO %s (id, balance) VALUES (%d, 1000000)",
                tableName, i
            ));
        }
        
        // Test: SUM should return 1,000,000,000 (1 billion) as a Long, not 1.0E9
        QueryResponse response = queryService.execute(
            String.format("SELECT SUM(balance) FROM %s", tableName)
        );
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows());
        assertEquals(1, response.getRows().size());
        
        Object sum = response.getRows().get(0).get("sum_balance");
        assertNotNull(sum, "SUM result should not be null");
        
        // Verify it's a Long, not a Double
        assertTrue(sum instanceof Long, 
            "SUM should return Long for integer columns, got: " + sum.getClass().getName());
        
        // Verify the value is exactly 1 billion
        assertEquals(1000000000L, ((Number) sum).longValue());
        
        // Verify toString doesn't use scientific notation
        String sumStr = sum.toString();
        assertFalse(sumStr.contains("E") || sumStr.contains("e"),
            "SUM result should not use scientific notation, got: " + sumStr);
        assertEquals("1000000000", sumStr);
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
    
    @Test
    public void testAvgWithWhereClause() throws Exception {
        String tableName = uniqueTableName("avg_test");
        
        // Create table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, score INT)",
            tableName
        ));
        
        // Insert test data
        for (int i = 1; i <= 10; i++) {
            queryService.execute(String.format(
                "INSERT INTO %s (id, score) VALUES (%d, %d)",
                tableName, i, i * 10
            ));
        }
        
        // Test: AVG with WHERE clause
        QueryResponse response = queryService.execute(
            String.format("SELECT AVG(score) FROM %s WHERE id <= 5", tableName)
        );
        
        assertNull(response.getError());
        assertNotNull(response.getRows());
        
        Object avg = response.getRows().get(0).get("avg_score");
        // Average of 10, 20, 30, 40, 50 = 30.0
        assertEquals(30.0, ((Number) avg).doubleValue(), 0.01,
            "WHERE clause should filter to id <= 5");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
}

