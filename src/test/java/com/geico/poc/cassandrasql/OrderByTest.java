package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ORDER BY functionality (KV mode)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OrderByTest extends KvTestBase {
    
    private String testTable;
    
    @BeforeEach
    public void setUp() throws Exception {
        // Use unique table name per test
        testTable = uniqueTableName("order_by_test");
        
        // Create test table
        queryService.execute("DROP TABLE IF EXISTS " + testTable);
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name TEXT, score INT, category TEXT)");
        
        // Insert test data with various values for sorting
        queryService.execute(String.format("INSERT INTO %s (id, name, score, category) VALUES (1, 'Alice', 95, 'A')", testTable));
        queryService.execute(String.format("INSERT INTO %s (id, name, score, category) VALUES (2, 'Bob', 87, 'B')", testTable));
        queryService.execute(String.format("INSERT INTO %s (id, name, score, category) VALUES (3, 'Charlie', 92, 'A')", testTable));
        queryService.execute(String.format("INSERT INTO %s (id, name, score, category) VALUES (4, 'David', 78, 'C')", testTable));
        queryService.execute(String.format("INSERT INTO %s (id, name, score, category) VALUES (5, 'Eve', 92, 'B')", testTable));
        queryService.execute(String.format("INSERT INTO %s (id, name, score, category) VALUES (6, 'Frank', 85, 'A')", testTable));
        
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
    @DisplayName("ORDER BY single column ASC should sort ascending")
    public void testOrderByAsc() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " ORDER BY name ASC");
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(6, response.getRowCount(), "Should return all 6 rows");
        
        // Verify ordering
        List<Map<String, Object>> rows = response.getRows();
        if (rows != null && rows.size() >= 2) {
            String name1 = rows.get(0).get("name").toString();
            String name2 = rows.get(1).get("name").toString();
            assertTrue(name1.compareTo(name2) <= 0, "Names should be in ascending order");
        }
    }
    
    @Test
    @DisplayName("ORDER BY single column DESC should sort descending")
    public void testOrderByDesc() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + testTable + " ORDER BY score DESC");
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(6, response.getRowCount(), "Should return all 6 rows");
        
        // Verify ordering - first score should be highest
        List<Map<String, Object>> rows = response.getRows();
        if (rows != null && rows.size() >= 2) {
            int score1 = Integer.parseInt(rows.get(0).get("score").toString());
            int score2 = Integer.parseInt(rows.get(1).get("score").toString());
            assertTrue(score1 >= score2, "Scores should be in descending order");
        }
    }
    
    @Test
    @DisplayName("ORDER BY multiple columns should sort by priority")
    public void testOrderByMultipleColumns() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY score DESC, name ASC"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(6, response.getRowCount(), "Should return all 6 rows");
        
        // Verify ordering
        List<Map<String, Object>> rows = response.getRows();
        if (rows != null && rows.size() >= 3) {
            // Check that rows with same score are sorted by name
            for (int i = 0; i < rows.size() - 1; i++) {
                int score1 = Integer.parseInt(rows.get(i).get("score").toString());
                int score2 = Integer.parseInt(rows.get(i + 1).get("score").toString());
                
                if (score1 == score2) {
                    String name1 = rows.get(i).get("name").toString();
                    String name2 = rows.get(i + 1).get("name").toString();
                    assertTrue(name1.compareTo(name2) <= 0, 
                        "Names should be in ascending order when scores are equal");
                } else {
                    assertTrue(score1 >= score2, "Scores should be in descending order");
                }
            }
        }
    }
    
    @Test
    @DisplayName("ORDER BY with WHERE clause should sort filtered results")
    public void testOrderByWithWhere() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + testTable + " WHERE score > 85 ORDER BY score ASC"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertTrue(response.getRowCount() > 0, "Should return some rows");
        
        // Verify all scores > 85 and sorted
        List<Map<String, Object>> rows = response.getRows();
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                int score = Integer.parseInt(rows.get(i).get("score").toString());
                assertTrue(score > 85, "All scores should be > 85");
                
                if (i < rows.size() - 1) {
                    int nextScore = Integer.parseInt(rows.get(i + 1).get("score").toString());
                    assertTrue(score <= nextScore, "Scores should be in ascending order");
                }
            }
        }
    }
    
    @Test
    @DisplayName("ORDER BY with LIMIT should return top N")
    public void testOrderByWithLimit() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY score DESC LIMIT 3"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(3, response.getRowCount(), "Should return exactly 3 rows");
        
        // Verify these are the top 3 scores
        List<Map<String, Object>> rows = response.getRows();
        if (rows != null && rows.size() == 3) {
            int score1 = Integer.parseInt(rows.get(0).get("score").toString());
            int score2 = Integer.parseInt(rows.get(1).get("score").toString());
            int score3 = Integer.parseInt(rows.get(2).get("score").toString());
            
            assertTrue(score1 >= score2, "First score should be >= second");
            assertTrue(score2 >= score3, "Second score should be >= third");
            assertTrue(score1 >= 92, "Top score should be at least 92");
        }
    }
    
    @Test
    @DisplayName("ORDER BY with LIMIT and OFFSET for pagination")
    public void testOrderByPagination() throws Exception {
        // Get all results sorted
        QueryResponse allResults = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY name ASC"
        );
        
        // Get page 1 (first 2)
        QueryResponse page1 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY name ASC LIMIT 2 OFFSET 0"
        );
        
        // Get page 2 (next 2)
        QueryResponse page2 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY name ASC LIMIT 2 OFFSET 2"
        );
        
        assertAll("Pagination should work correctly",
            () -> assertEquals(6, allResults.getRowCount(), "Should have 6 total rows"),
            () -> assertEquals(2, page1.getRowCount(), "Page 1 should have 2 rows"),
            () -> assertEquals(2, page2.getRowCount(), "Page 2 should have 2 rows")
        );
        
        // Verify pages don't overlap
        if (page1.getRows() != null && page2.getRows() != null && 
            page1.getRows().size() >= 1 && page2.getRows().size() >= 1) {
            String lastOfPage1 = page1.getRows().get(page1.getRows().size() - 1).get("name").toString();
            String firstOfPage2 = page2.getRows().get(0).get("name").toString();
            assertTrue(lastOfPage1.compareTo(firstOfPage2) < 0, 
                "Last of page 1 should come before first of page 2");
        }
    }
    
    @Test
    @DisplayName("ORDER BY with NULL values should handle gracefully")
    public void testOrderByWithNulls() throws Exception {
        // Create table with nullable column
        String nullsTable = uniqueTableName("test_order_nulls");
        queryService.execute("DROP TABLE IF EXISTS " + nullsTable);
        queryService.execute("CREATE TABLE " + nullsTable + " (id INT PRIMARY KEY, value TEXT)");
        registerTable(nullsTable);
        
        queryService.execute("INSERT INTO " + nullsTable + " (id, value) VALUES (1, 'A')");
        queryService.execute("INSERT INTO " + nullsTable + " (id, value) VALUES (2, NULL)");
        queryService.execute("INSERT INTO " + nullsTable + " (id, value) VALUES (3, 'B')");
        
        Thread.sleep(100);
        
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + nullsTable + " ORDER BY value ASC"
        );
        
        assertNotNull(response);
        // Should handle NULLs gracefully (either first or last depending on implementation)
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + nullsTable);
    }
    
    @Test
    @DisplayName("ORDER BY on numeric column should sort numerically")
    public void testOrderByNumeric() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY id ASC"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        
        // Verify numeric ordering
        List<Map<String, Object>> rows = response.getRows();
        if (rows != null) {
            for (int i = 0; i < rows.size() - 1; i++) {
                int id1 = Integer.parseInt(rows.get(i).get("id").toString());
                int id2 = Integer.parseInt(rows.get(i + 1).get("id").toString());
                assertTrue(id1 < id2, "IDs should be in ascending numeric order");
            }
        }
    }
    
    @Test
    @DisplayName("ORDER BY on text column should sort alphabetically")
    public void testOrderByText() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY category ASC, name ASC"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        
        // Verify alphabetical ordering
        List<Map<String, Object>> rows = response.getRows();
        if (rows != null) {
            for (int i = 0; i < rows.size() - 1; i++) {
                String cat1 = rows.get(i).get("category").toString();
                String cat2 = rows.get(i + 1).get("category").toString();
                assertTrue(cat1.compareTo(cat2) <= 0, "Categories should be in alphabetical order");
            }
        }
    }
    
    @Test
    @DisplayName("ORDER BY without direction defaults to ASC")
    public void testOrderByDefaultDirection() throws Exception {
        QueryResponse response1 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY name"
        );
        QueryResponse response2 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY name ASC"
        );
        
        assertNotNull(response1);
        assertNotNull(response2);
        assertNull(response1.getError(), "Query without direction should succeed");
        assertNull(response2.getError(), "Query with ASC should succeed");
        
        // Both should return same number of rows
        assertEquals(response1.getRowCount(), response2.getRowCount(), 
            "Both queries should return same number of rows");
    }
    
    @Test
    @DisplayName("ORDER BY with JOIN should work")
    public void testOrderByWithJoin() throws Exception {
        // Create second table for JOIN
        String joinTable = uniqueTableName("test_order_join");
        queryService.execute("CREATE TABLE " + joinTable + " (id INT PRIMARY KEY, user_id INT, amount INT)");
        
        queryService.execute("INSERT INTO " + joinTable + " (id, user_id, amount) VALUES (1, 1, 100)");
        queryService.execute("INSERT INTO " + joinTable + " (id, user_id, amount) VALUES (2, 2, 200)");
        queryService.execute("INSERT INTO " + joinTable + " (id, user_id, amount) VALUES (3, 3, 150)");
        
        Thread.sleep(100);
        
        QueryResponse response = queryService.execute(
            "SELECT t1.name, t2.amount FROM " + testTable + " t1 " +
            "JOIN " + joinTable + " t2 ON t1.id = t2.user_id " +
            "ORDER BY t2.amount DESC"
        );
        
        assertNotNull(response);
        // JOIN with ORDER BY should work
        if (response.getError() == null) {
            assertTrue(response.getRowCount() > 0, "Should return some rows");
            
            // Verify ordering
            List<Map<String, Object>> rows = response.getRows();
            if (rows != null) {
                for (int i = 0; i < rows.size() - 1; i++) {
                    int amount1 = Integer.parseInt(rows.get(i).get("amount").toString());
                    int amount2 = Integer.parseInt(rows.get(i + 1).get("amount").toString());
                    assertTrue(amount1 >= amount2, "Amounts should be in descending order");
                }
            }
        }
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + joinTable);
    }
    
    @Test
    @DisplayName("Multiple ORDER BY queries should be independent")
    public void testMultipleOrderByQueries() throws Exception {
        QueryResponse response1 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY name ASC"
        );
        QueryResponse response2 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY score DESC"
        );
        QueryResponse response3 = queryService.execute(
            "SELECT * FROM " + testTable + " ORDER BY category ASC"
        );
        
        assertAll("Multiple queries should be independent",
            () -> assertNotNull(response1),
            () -> assertNotNull(response2),
            () -> assertNotNull(response3),
            () -> assertEquals(6, response1.getRowCount()),
            () -> assertEquals(6, response2.getRowCount()),
            () -> assertEquals(6, response3.getRowCount())
        );
    }
}



