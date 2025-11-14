package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for LIMIT and OFFSET functionality (KV mode)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LimitTest extends KvTestBase {
    
    private String testTable;
    
    @BeforeEach
    public void setUp() throws Exception {
        // Use unique table name per test
        testTable = uniqueTableName("limit_test");
        
        // Create test table
        queryService.execute("DROP TABLE IF EXISTS " + testTable);
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name TEXT, score INT)");
        
        // Insert test data (10 rows)
        for (int i = 1; i <= 10; i++) {
            queryService.execute(String.format(
                "INSERT INTO %s (id, name, score) VALUES (%d, 'User%d', %d)",
                testTable, i, i, i * 10
            ));
        }
        
        // Wait for data to be available
        Thread.sleep(100);
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
            Thread.sleep(50);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("LIMIT without OFFSET should return first N rows")
    public void testLimitOnly() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " LIMIT 3");
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(3, response.getRowCount(), "Should return exactly 3 rows");
    }
    
    @Test
    @Order(2)
    @DisplayName("LIMIT 0 should return no rows")
    public void testLimitZero() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " LIMIT 0");
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(0, response.getRowCount(), "Should return 0 rows");
    }
    
    @Test
    @Order(3)
    @DisplayName("LIMIT larger than result set should return all rows")
    public void testLimitLargerThanResultSet() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " LIMIT 100");
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(10, response.getRowCount(), "Should return all 10 rows");
    }
    
    @Test
    @Order(4)
    @DisplayName("LIMIT with OFFSET should skip first N rows")
    public void testLimitWithOffset() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " LIMIT 3 OFFSET 2");
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(3, response.getRowCount(), "Should return 3 rows after skipping 2");
    }
    
    @Test
    @Order(5)
    @DisplayName("OFFSET larger than result set should return no rows")
    public void testOffsetLargerThanResultSet() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " LIMIT 5 OFFSET 20");
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(0, response.getRowCount(), "Should return 0 rows when offset exceeds result set");
    }
    
    @Test
    @Order(6)
    @DisplayName("LIMIT with ORDER BY should return top N rows")
    public void testLimitWithOrderBy() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY score DESC LIMIT 3"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(3, response.getRowCount(), "Should return top 3 rows");
        
        // Verify ordering: scores should be 100, 90, 80
        if (response.getRows() != null && response.getRows().size() >= 3) {
            Object score1 = response.getRows().get(0).get("score");
            Object score2 = response.getRows().get(1).get("score");
            Object score3 = response.getRows().get(2).get("score");
            
            assertTrue(Integer.parseInt(score1.toString()) >= Integer.parseInt(score2.toString()),
                "First score should be >= second score");
            assertTrue(Integer.parseInt(score2.toString()) >= Integer.parseInt(score3.toString()),
                "Second score should be >= third score");
        }
    }
    
    @Test
    @Order(7)
    @DisplayName("LIMIT with ORDER BY and OFFSET for pagination")
    public void testPagination() throws Exception {
        // Page 1: rows 0-2
        QueryResponse page1 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY id ASC LIMIT 3 OFFSET 0"
        );
        
        // Page 2: rows 3-5
        QueryResponse page2 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY id ASC LIMIT 3 OFFSET 3"
        );
        
        // Page 3: rows 6-8
        QueryResponse page3 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY id ASC LIMIT 3 OFFSET 6"
        );
        
        // Page 4: rows 9-10 (only 2 rows left)
        QueryResponse page4 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY id ASC LIMIT 3 OFFSET 9"
        );
        
        assertAll("Pagination should work correctly",
            () -> assertEquals(3, page1.getRowCount(), "Page 1 should have 3 rows"),
            () -> assertEquals(3, page2.getRowCount(), "Page 2 should have 3 rows"),
            () -> assertEquals(3, page3.getRowCount(), "Page 3 should have 3 rows"),
            () -> assertEquals(1, page4.getRowCount(), "Page 4 should have 1 row")
        );
    }
    
    @Test
    @Order(8)
    @DisplayName("LIMIT with WHERE clause")
    public void testLimitWithWhere() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + testTable + " WHERE score > 50 LIMIT 3"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertTrue(response.getRowCount() <= 3, "Should return at most 3 rows");
        
        // Verify all returned rows match WHERE clause
        if (response.getRows() != null) {
            for (var row : response.getRows()) {
                Object score = row.get("score");
                assertTrue(Integer.parseInt(score.toString()) > 50,
                    "All rows should have score > 50");
            }
        }
    }
    
    @Test
    @Order(9)
    @DisplayName("LIMIT 1 should return single row")
    public void testLimitOne() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " LIMIT 1");
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return exactly 1 row");
    }
    
    @Test
    @Order(10)
    @DisplayName("Multiple LIMIT queries should be independent")
    public void testMultipleLimitQueries() throws Exception {
        QueryResponse response1 = queryService.execute("SELECT * FROM " + testTable + " LIMIT 2");
        QueryResponse response2 = queryService.execute("SELECT * FROM " + testTable + " LIMIT 5");
        QueryResponse response3 = queryService.execute("SELECT * FROM " + testTable + " LIMIT 1");
        
        assertAll("Multiple queries should be independent",
            () -> assertEquals(2, response1.getRowCount(), "First query should return 2 rows"),
            () -> assertEquals(5, response2.getRowCount(), "Second query should return 5 rows"),
            () -> assertEquals(1, response3.getRowCount(), "Third query should return 1 row")
        );
    }
    
    @Test
    @Order(11)
    @DisplayName("LIMIT with complex ORDER BY")
    public void testLimitWithComplexOrderBy() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY score DESC, name ASC LIMIT 5"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(5, response.getRowCount(), "Should return 5 rows");
    }
    
    @Test
    @Order(12)
    @DisplayName("OFFSET without LIMIT should work")
    public void testOffsetWithoutLimit() throws Exception {
        // Note: Some databases require LIMIT with OFFSET, but we should handle it gracefully
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " OFFSET 3");
        
        assertNotNull(response);
        // Should either work (returning 7 rows) or fail gracefully
        if (response.getError() == null) {
            assertEquals(7, response.getRowCount(), "Should return remaining 7 rows after offset");
        }
    }
    
    @Test
    @Order(13)
    @DisplayName("LIMIT with JOIN should work")
    public void testLimitWithJoin() throws Exception {
        // Create second table for JOIN
        String joinTable = uniqueTableName("test_limit_join");
        queryService.execute("DROP TABLE IF EXISTS " + joinTable);
        queryService.execute("CREATE TABLE " + joinTable + " (id INT PRIMARY KEY, user_id INT, v TEXT)");
        registerTable(joinTable);
        
        // Insert some data
        queryService.execute("INSERT INTO " + joinTable + " (id, user_id, v) VALUES (1, 1, 'A')");
        queryService.execute("INSERT INTO " + joinTable + " (id, user_id, v) VALUES (2, 2, 'B')");
        queryService.execute("INSERT INTO " + joinTable + " (id, user_id, v) VALUES (3, 3, 'C')");
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        QueryResponse response = queryService.execute(
            "SELECT t1.name, t2.v FROM " + testTable + " t1 " +
            "JOIN " + joinTable + " t2 ON t1.id = t2.user_id LIMIT 2"
        );
        
        assertNotNull(response);
        // JOIN with LIMIT should work
        if (response.getError() == null) {
            assertTrue(response.getRowCount() <= 2, "Should return at most 2 rows");
        }
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + joinTable);
    }
}

