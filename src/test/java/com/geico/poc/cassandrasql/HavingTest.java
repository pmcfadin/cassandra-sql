package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HAVING clause with GROUP BY
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HavingTest {
    
    @Autowired
    private QueryService queryService;
    
    private static final String TEST_TABLE = "having_test_orders";
    
    @BeforeEach
    public void setup() throws Exception {
        // Drop and recreate table using KV mode
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore
        }
        
        // Create test table using KV mode
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (" +
            "    order_id INT PRIMARY KEY," +
            "    user_id INT," +
            "    amount DECIMAL" +
            ")"
        );
        
        // Insert test data using KV mode
        queryService.execute("INSERT INTO " + TEST_TABLE + " (order_id, user_id, amount) VALUES (1, 1, 100.00)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (order_id, user_id, amount) VALUES (2, 1, 50.00)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (order_id, user_id, amount) VALUES (3, 1, 75.00)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (order_id, user_id, amount) VALUES (4, 2, 200.00)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (order_id, user_id, amount) VALUES (5, 2, 150.00)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (order_id, user_id, amount) VALUES (6, 3, 25.00)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (order_id, user_id, amount) VALUES (7, 3, 30.00)");
        
        Thread.sleep(100); // Allow data to settle
    }
    
    @AfterEach
    public void teardown() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @Test
    @Order(1)
    public void testHavingWithCount() throws Exception {
        String sql = "SELECT user_id, COUNT(*) as order_count " +
                    "FROM " + TEST_TABLE + " " +
                    "GROUP BY user_id " +
                    "HAVING COUNT(*) > 2";
        
        System.out.println("\n\n========== TEST: testHavingWithCount ==========");
        System.out.println("SQL: " + sql);
        System.out.println("===============================================\n");
        
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Response error: " + response.getError());
        System.out.println("Response rows: " + response.getRows());
        System.out.println("Response row count: " + (response.getRows() != null ? response.getRows().size() : "null"));
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 users with >2 orders");
        
        // Verify user_id column exists
        for (Map<String, Object> row : response.getRows()) {
            assertTrue(row.containsKey("user_id") || row.containsKey("USER_ID"), 
                      "Should have user_id column");
            assertTrue(row.containsKey("order_count") || row.containsKey("ORDER_COUNT") || 
                      row.containsKey("COUNT(*)"), 
                      "Should have order_count column");
        }
    }
    
    @Test
    @Order(2)
    public void testHavingWithSum() throws Exception {
        String sql = "SELECT user_id, SUM(amount) as total_amount " +
                    "FROM " + TEST_TABLE + " " +
                    "GROUP BY user_id " +
                    "HAVING SUM(amount) >= 200";
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(2, response.getRows().size(), "Should have 2 users with total >= 200");
        
        // Verify amounts
        for (Map<String, Object> row : response.getRows()) {
            Object totalAmount = row.get("total_amount") != null ? row.get("total_amount") : 
                                row.get("TOTAL_AMOUNT") != null ? row.get("TOTAL_AMOUNT") :
                                row.get("SUM(AMOUNT)");
            assertNotNull(totalAmount, "Should have total_amount");
            
            double amount = ((Number) totalAmount).doubleValue();
            assertTrue(amount >= 200.0, "Total amount should be >= 200");
        }
    }
    
    @Test
    @Order(3)
    public void testHavingWithAvg() throws Exception {
        String sql = "SELECT user_id, AVG(amount) as avg_amount " +
                    "FROM " + TEST_TABLE + " " +
                    "GROUP BY user_id " +
                    "HAVING AVG(amount) > 100";
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 user with avg > 100");
        
        // Verify it's user_id 2
        Map<String, Object> row = response.getRows().get(0);
        Object userId = row.get("user_id") != null ? row.get("user_id") : row.get("USER_ID");
        assertEquals(2, ((Number) userId).intValue(), "Should be user_id 2");
    }
    
    @Test
    @Order(4)
    public void testHavingWithMin() throws Exception {
        String sql = "SELECT user_id, MIN(amount) as min_amount " +
                    "FROM " + TEST_TABLE + " " +
                    "GROUP BY user_id " +
                    "HAVING MIN(amount) < 50";
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 user with min < 50");
        
        // Verify it's user_id 3
        Map<String, Object> row = response.getRows().get(0);
        Object userId = row.get("user_id") != null ? row.get("user_id") : row.get("USER_ID");
        assertEquals(3, ((Number) userId).intValue(), "Should be user_id 3");
    }
    
    @Test
    @Order(5)
    public void testHavingWithMax() throws Exception {
        String sql = "SELECT user_id, MAX(amount) as max_amount " +
                    "FROM " + TEST_TABLE + " " +
                    "GROUP BY user_id " +
                    "HAVING MAX(amount) >= 200";
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 user with max >= 200");
        
        // Verify it's user_id 2
        Map<String, Object> row = response.getRows().get(0);
        Object userId = row.get("user_id") != null ? row.get("user_id") : row.get("USER_ID");
        assertEquals(2, ((Number) userId).intValue(), "Should be user_id 2");
    }
    
    @Test
    @Order(6)
    public void testHavingWithEquals() throws Exception {
        String sql = "SELECT user_id, COUNT(*) as order_count " +
                    "FROM " + TEST_TABLE + " " +
                    "GROUP BY user_id " +
                    "HAVING COUNT(*) = 3";
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 users with exactly 3 orders");
    }
    
    @Test
    @Order(7)
    public void testHavingWithNotEquals() throws Exception {
        String sql = "SELECT user_id, COUNT(*) as order_count " +
                    "FROM " + TEST_TABLE + " " +
                    "GROUP BY user_id " +
                    "HAVING COUNT(*) <> 2";
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 user with != 2 orders");
        
        // Verify it's user_id 1 (has 3 orders)
        Map<String, Object> row = response.getRows().get(0);
        Object userId = row.get("user_id") != null ? row.get("user_id") : row.get("USER_ID");
        assertEquals(1, ((Number) userId).intValue(), "Should be user_id 1");
    }
    
    @Test
    @Order(8)
    public void testHavingFilterAll() throws Exception {
        String sql = "SELECT user_id, SUM(amount) as total_amount " +
                    "FROM " + TEST_TABLE + " " +
                    "GROUP BY user_id " +
                    "HAVING SUM(amount) > 1000";
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(0, response.getRows().size(), "Should have no users with total > 1000");
    }
}

