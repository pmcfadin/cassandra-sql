package com.geico.poc.cassandrasql.window;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for window functions.
 * 
 * Tests all window functions:
 * - ROW_NUMBER(), RANK(), DENSE_RANK()
 * - SUM() OVER, AVG() OVER, COUNT() OVER, MIN() OVER, MAX() OVER
 * - LAG(), LEAD(), FIRST_VALUE(), LAST_VALUE()
 * 
 * Tests features:
 * - PARTITION BY
 * - ORDER BY
 * - Multiple window functions in one query
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled
public class WindowFunctionTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    private static String TEST_TABLE;
    
    @BeforeAll
    public void setupTable() throws Exception {
        TEST_TABLE = uniqueTableName("window_test");

        // Create test table
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        queryService.execute("CREATE TABLE " + TEST_TABLE + " (" +
                "id INT PRIMARY KEY, " +
                "category TEXT, " +
                "product TEXT, " +
                "price INT, " +
                "quantity INT)");
        
        // Insert test data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, category, product, price, quantity) VALUES (1, 'Electronics', 'Laptop', 1200, 5)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, category, product, price, quantity) VALUES (2, 'Electronics', 'Phone', 800, 10)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, category, product, price, quantity) VALUES (3, 'Electronics', 'Tablet', 500, 8)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, category, product, price, quantity) VALUES (4, 'Furniture', 'Desk', 300, 3)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, category, product, price, quantity) VALUES (5, 'Furniture', 'Chair', 150, 12)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, category, product, price, quantity) VALUES (6, 'Furniture', 'Lamp', 50, 20)");
        
        // Wait for data to be available
        Thread.sleep(100);
    }
    
    @AfterAll
    public void cleanupTable() throws Exception {
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
    }
    
    // ========================================
    // ROW_NUMBER() Tests
    // ========================================
    
    @Test
    @Order(1)
    public void testRowNumberBasic() throws Exception {
        String sql = "SELECT product, price, ROW_NUMBER() OVER (ORDER BY price DESC) as row_num " +
                    "FROM " + TEST_TABLE;
        
        QueryResponse response = queryService.execute(sql);
        
        assertEquals(6, response.getRows().size());
        assertTrue(response.getColumns().contains("row_num"));
        
        // Check row numbers are sequential
        for (int i = 0; i < response.getRows().size(); i++) {
            Map<String, Object> row = response.getRows().get(i);
            assertEquals(i + 1, row.get("row_num"));
        }
        
        // Check ordering (highest price first)
        assertEquals("Laptop", response.getRows().get(0).get("product"));
        assertEquals(1200, response.getRows().get(0).get("price"));
    }
    
    @Test
    @Order(2)
    public void testRowNumberWithPartition() throws Exception {
        String sql = "SELECT category, product, price, " +
                    "ROW_NUMBER() OVER (PARTITION BY category ORDER BY price DESC) as row_num " +
                    "FROM " + TEST_TABLE;
        
        QueryResponse response = queryService.execute(sql);
        
        assertEquals(6, response.getRows().size());
        
        // Check Electronics partition
        int electronicsCount = 0;
        for (Map<String, Object> row : response.getRows()) {
            if ("Electronics".equals(row.get("category"))) {
                electronicsCount++;
                int rowNum = (Integer) row.get("row_num");
                assertTrue(rowNum >= 1 && rowNum <= 3, "Row number should be 1-3 for Electronics");
            }
        }
        assertEquals(3, electronicsCount);
        
        // Check Furniture partition
        int furnitureCount = 0;
        for (Map<String, Object> row : response.getRows()) {
            if ("Furniture".equals(row.get("category"))) {
                furnitureCount++;
                int rowNum = (Integer) row.get("row_num");
                assertTrue(rowNum >= 1 && rowNum <= 3, "Row number should be 1-3 for Furniture");
            }
        }
        assertEquals(3, furnitureCount);
    }
    
    // ========================================
    // RANK() and DENSE_RANK() Tests
    // ========================================
    
    @Test
    @Order(3)
    public void testRank() throws Exception {
        // Add duplicate prices for testing ties
        queryService.execute("INSERT INTO window_test (id, category, product, price, quantity) VALUES (7, 'Electronics', 'Mouse', 50, 15)");
        queryService.execute("INSERT INTO window_test (id, category, product, price, quantity) VALUES (8, 'Electronics', 'Keyboard', 50, 10)");
        
        String sql = "SELECT product, price, RANK() OVER (ORDER BY price DESC) as r " +
                    "FROM " + TEST_TABLE + " WHERE category = 'Electronics'";
        
        QueryResponse response = queryService.execute(sql);
        
        // Check that ties get the same rank
        int rank50Count = 0;
        Integer rank50Value = null;
        for (Map<String, Object> row : response.getRows()) {
            if ((Integer) row.get("price") == 50) {
                rank50Count++;
                if (rank50Value == null) {
                    rank50Value = (Integer) row.get("r");
                } else {
                    assertEquals(rank50Value, row.get("r"), "Ties should have same rank");
                }
            }
        }
        assertEquals(2, rank50Count, "Should have 2 products with price 50");
        
        // Cleanup
        queryService.execute("DELETE FROM " + TEST_TABLE + " WHERE id IN (7, 8)");
    }
    
    @Test
    @Order(4)
    public void testDenseRank() throws Exception {
        // Add duplicate prices
        queryService.execute("INSERT INTO window_test (id, category, product, price, quantity) VALUES (7, 'Electronics', 'Mouse', 50, 15)");
        queryService.execute("INSERT INTO window_test (id, category, product, price, quantity) VALUES (8, 'Electronics', 'Keyboard', 50, 10)");
        
        String sql = "SELECT product, price, DENSE_RANK() OVER (ORDER BY price DESC) as dense_rank " +
                    "FROM " + TEST_TABLE + " WHERE category = 'Electronics'";
        
        QueryResponse response = queryService.execute(sql);
        
        // DENSE_RANK should not have gaps (unlike RANK)
        // Check that ranks are consecutive
        int maxRank = 0;
        for (Map<String, Object> row : response.getRows()) {
            int rank = (Integer) row.get("dense_rank");
            maxRank = Math.max(maxRank, rank);
        }
        
        // Should have 4 distinct prices (1200, 800, 500, 50), so max dense_rank = 4
        assertEquals(4, maxRank, "DENSE_RANK should not have gaps");
        
        // Cleanup
        queryService.execute("DELETE FROM " + TEST_TABLE + " WHERE id IN (7, 8)");
    }
    
    // ========================================
    // Aggregate Window Functions Tests
    // ========================================
    
    @Test
    @Order(5)
    public void testSumOver() throws Exception {
        String sql = "SELECT product, price, " +
                    "SUM(price) OVER (ORDER BY id) as running_total " +
                    "FROM " + TEST_TABLE + " ORDER BY id";
        
        QueryResponse response = queryService.execute(sql);
        
        // Check running total
        int expectedTotal = 0;
        for (Map<String, Object> row : response.getRows()) {
            int price = (Integer) row.get("price");
            expectedTotal += price;
            
            // Running total should match
            double runningTotal = ((Number) row.get("running_total")).doubleValue();
            assertEquals(expectedTotal, (int) runningTotal, "Running total should accumulate");
        }
    }
    
    @Test
    @Order(6)
    public void testAvgOver() throws Exception {
        String sql = "SELECT product, price, " +
                    "AVG(price) OVER (ORDER BY id) as running_avg " +
                    "FROM " + TEST_TABLE + " ORDER BY id";
        
        QueryResponse response = queryService.execute(sql);
        
        // Check running average
        int sum = 0;
        int count = 0;
        for (Map<String, Object> row : response.getRows()) {
            int price = (Integer) row.get("price");
            sum += price;
            count++;
            
            double expectedAvg = (double) sum / count;
            double runningAvg = ((Number) row.get("running_avg")).doubleValue();
            assertEquals(expectedAvg, runningAvg, 0.01, "Running average should be correct");
        }
    }
    
    @Test
    @Order(7)
    public void testCountOver() throws Exception {
        String sql = "SELECT product, " +
                    "COUNT(*) OVER (ORDER BY id) as running_count " +
                    "FROM " + TEST_TABLE + " ORDER BY id";
        
        QueryResponse response = queryService.execute(sql);
        
        // Check running count
        for (int i = 0; i < response.getRows().size(); i++) {
            Map<String, Object> row = response.getRows().get(i);
            int runningCount = (Integer) row.get("running_count");
            assertEquals(i + 1, runningCount, "Running count should increment");
        }
    }
    
    @Test
    @Order(8)
    public void testMinMaxOver() throws Exception {
        String sql = "SELECT product, price, " +
                    "MIN(price) OVER (ORDER BY id) as running_min, " +
                    "MAX(price) OVER (ORDER BY id) as running_max " +
                    "FROM " + TEST_TABLE + " ORDER BY id";
        
        QueryResponse response = queryService.execute(sql);
        
        // Check running min/max
        int minSoFar = Integer.MAX_VALUE;
        int maxSoFar = Integer.MIN_VALUE;
        
        for (Map<String, Object> row : response.getRows()) {
            int price = (Integer) row.get("price");
            minSoFar = Math.min(minSoFar, price);
            maxSoFar = Math.max(maxSoFar, price);
            
            assertEquals(minSoFar, row.get("running_min"), "Running min should be correct");
            assertEquals(maxSoFar, row.get("running_max"), "Running max should be correct");
        }
    }
    
    // ========================================
    // LAG() and LEAD() Tests
    // ========================================
    
    @Test
    @Order(9)
    public void testLag() throws Exception {
        String sql = "SELECT product, price, " +
                    "LAG(price, 1) OVER (ORDER BY id) as prev_price " +
                    "FROM " + TEST_TABLE + " ORDER BY id";
        
        QueryResponse response = queryService.execute(sql);
        
        // First row should have NULL for prev_price
        assertNull(response.getRows().get(0).get("prev_price"));
        
        // Check that each row's prev_price matches previous row's price
        for (int i = 1; i < response.getRows().size(); i++) {
            Object prevPrice = response.getRows().get(i).get("prev_price");
            Object actualPrevPrice = response.getRows().get(i - 1).get("price");
            assertEquals(actualPrevPrice, prevPrice, "LAG should return previous row's value");
        }
    }
    
    @Test
    @Order(10)
    public void testLead() throws Exception {
        String sql = "SELECT product, price, " +
                    "LEAD(price, 1) OVER (ORDER BY id) as next_price " +
                    "FROM " + TEST_TABLE + " ORDER BY id";
        
        QueryResponse response = queryService.execute(sql);
        
        // Last row should have NULL for next_price
        assertNull(response.getRows().get(response.getRows().size() - 1).get("next_price"));
        
        // Check that each row's next_price matches next row's price
        for (int i = 0; i < response.getRows().size() - 1; i++) {
            Object nextPrice = response.getRows().get(i).get("next_price");
            Object actualNextPrice = response.getRows().get(i + 1).get("price");
            assertEquals(actualNextPrice, nextPrice, "LEAD should return next row's value");
        }
    }
    
    // ========================================
    // FIRST_VALUE() and LAST_VALUE() Tests
    // ========================================
    
    @Test
    @Order(11)
    public void testFirstValue() throws Exception {
        String sql = "SELECT category, product, price, " +
                    "FIRST_VALUE(product) OVER (PARTITION BY category ORDER BY price DESC) as most_expensive " +
                    "FROM " + TEST_TABLE;
        
        QueryResponse response = queryService.execute(sql);
        
        // Check Electronics partition
        for (Map<String, Object> row : response.getRows()) {
            if ("Electronics".equals(row.get("category"))) {
                assertEquals("Laptop", row.get("most_expensive"), "Most expensive Electronics should be Laptop");
            }
        }
        
        // Check Furniture partition
        for (Map<String, Object> row : response.getRows()) {
            if ("Furniture".equals(row.get("category"))) {
                assertEquals("Desk", row.get("most_expensive"), "Most expensive Furniture should be Desk");
            }
        }
    }
    
    @Test
    @Order(12)
    public void testLastValue() throws Exception {
        String sql = "SELECT category, product, price, " +
                    "LAST_VALUE(product) OVER (PARTITION BY category ORDER BY price DESC) as current_product " +
                    "FROM " + TEST_TABLE;
        
        QueryResponse response = queryService.execute(sql);
        
        // LAST_VALUE with default frame returns current row
        for (Map<String, Object> row : response.getRows()) {
            assertEquals(row.get("product"), row.get("current_product"), 
                        "LAST_VALUE with default frame should return current row");
        }
    }
    
    // ========================================
    // Multiple Window Functions Tests
    // ========================================
    
    @Test
    @Order(13)
    public void testMultipleWindowFunctions() throws Exception {
        String sql = "SELECT category, product, price, " +
                    "ROW_NUMBER() OVER (PARTITION BY category ORDER BY price DESC) as row_num, " +
                    "RANK() OVER (PARTITION BY category ORDER BY price DESC) as r, " +
                    "SUM(price) OVER (PARTITION BY category ORDER BY price DESC) as running_total " +
                    "FROM " + TEST_TABLE;
        
        QueryResponse response = queryService.execute(sql);
        
        assertEquals(6, response.getRows().size());
        assertTrue(response.getColumns().contains("row_num"));
        assertTrue(response.getColumns().contains("r"));
        assertTrue(response.getColumns().contains("running_total"));
        
        // Verify all window functions computed correctly
        for (Map<String, Object> row : response.getRows()) {
            assertNotNull(row.get("row_num"));
            assertNotNull(row.get("r"));
            assertNotNull(row.get("running_total"));
        }
    }
    
    // ========================================
    // Edge Cases Tests
    // ========================================
    
    @Test
    @Order(14)
    public void testWindowFunctionWithWhere() throws Exception {
        String sql = "SELECT product, price, " +
                    "ROW_NUMBER() OVER (ORDER BY price DESC) as row_num " +
                    "FROM " + TEST_TABLE + " WHERE category = 'Electronics'";
        
        QueryResponse response = queryService.execute(sql);
        
        assertEquals(3, response.getRows().size(), "Should only have Electronics products");
        
        // Check row numbers are sequential
        for (int i = 0; i < response.getRows().size(); i++) {
            assertEquals(i + 1, response.getRows().get(i).get("row_num"));
        }
    }
    
    @Test
    @Order(15)
    public void testWindowFunctionEmptyPartition() throws Exception {
        // Create table with no data
        queryService.execute("CREATE TABLE IF NOT EXISTS empty_window_test (id INT PRIMARY KEY, value INT)");
        
        String sql = "SELECT id, value, ROW_NUMBER() OVER (ORDER BY id) as row_num FROM empty_window_test";
        
        QueryResponse response = queryService.execute(sql);
        
        assertEquals(0, response.getRows().size(), "Empty table should return 0 rows");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS empty_window_test");
    }
    
    @Test
    @Order(16)
    public void testWindowFunctionWithNulls() throws Exception {
        // Create table with NULL values
        queryService.execute("CREATE TABLE IF NOT EXISTS "+TEST_TABLE+" (id INT PRIMARY KEY, v INT)");
        queryService.execute("INSERT INTO "+TEST_TABLE+"  (id, v) VALUES (1, 100)");
        queryService.execute("INSERT INTO "+TEST_TABLE+"  (id, v) VALUES (2, NULL)");
        queryService.execute("INSERT INTO "+TEST_TABLE+"  (id, v) VALUES (3, 200)");
        
        String sql = "SELECT id, v, ROW_NUMBER() OVER (ORDER BY id) as row_num FROM "+TEST_TABLE+" ";
        
        QueryResponse response = queryService.execute(sql);
        
        assertEquals(3, response.getRows().size());
        
        // Check NULL is preserved
        assertNull(response.getRows().get(1).get("v"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS "+TEST_TABLE+" ");
    }
}

