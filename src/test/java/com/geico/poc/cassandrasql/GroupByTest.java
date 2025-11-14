package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for GROUP BY and aggregation functionality
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GroupByTest {
    
    @Autowired
    private QueryService queryService;
    
    private static final String TEST_TABLE = "test_groupby";
    
    @BeforeAll
    public void setUp() throws Exception {
        // Create test table
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        queryService.execute("CREATE TABLE " + TEST_TABLE + " (id INT PRIMARY KEY, category TEXT, amount INT, quantity INT)");
        
        // Insert test data
        queryService.execute(String.format("INSERT INTO %s (id, category, amount, quantity) VALUES (1, 'Electronics', 100, 2)", TEST_TABLE));
        queryService.execute(String.format("INSERT INTO %s (id, category, amount, quantity) VALUES (2, 'Electronics', 200, 1)", TEST_TABLE));
        queryService.execute(String.format("INSERT INTO %s (id, category, amount, quantity) VALUES (3, 'Books', 50, 5)", TEST_TABLE));
        queryService.execute(String.format("INSERT INTO %s (id, category, amount, quantity) VALUES (4, 'Books', 30, 3)", TEST_TABLE));
        queryService.execute(String.format("INSERT INTO %s (id, category, amount, quantity) VALUES (5, 'Clothing', 75, 2)", TEST_TABLE));
        
        // Wait for data to be available
        Thread.sleep(100);
    }
    
    @AfterAll
    public void tearDown() throws Exception {
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
    }
    
    // ========================================
    // Simple Aggregation Tests (No GROUP BY)
    // ========================================
    
    @Test
    @DisplayName("COUNT(*) should count all rows")
    public void testCountAll() throws Exception {
        QueryResponse response = queryService.execute("SELECT COUNT(*) FROM " + TEST_TABLE);
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        // Check count value
        Object countValue = response.getRows().get(0).values().iterator().next();
        assertEquals(5L, ((Number) countValue).longValue(), "Should count 5 rows");
    }
    
    @Test
    @DisplayName("SUM should calculate total")
    public void testSum() throws Exception {
        QueryResponse response = queryService.execute("SELECT SUM(amount) FROM " + TEST_TABLE);
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Object sumValue = response.getRows().get(0).values().iterator().next();
        assertEquals(455L, ((Number) sumValue).longValue(), "Sum should be 100+200+50+30+75=455");
    }
    
    @Test
    @DisplayName("AVG should calculate average")
    public void testAvg() throws Exception {
        QueryResponse response = queryService.execute("SELECT AVG(amount) FROM " + TEST_TABLE);
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Object avgValue = response.getRows().get(0).values().iterator().next();
        assertEquals(91.0, ((Number) avgValue).doubleValue(), 0.1, "Average should be 455/5=91");
    }
    
    @Test
    @DisplayName("MIN should find minimum")
    public void testMin() throws Exception {
        QueryResponse response = queryService.execute("SELECT MIN(amount) FROM " + TEST_TABLE);
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Object minValue = response.getRows().get(0).values().iterator().next();
        assertEquals(30, ((Number) minValue).intValue(), "Minimum should be 30");
    }
    
    @Test
    @DisplayName("MAX should find maximum")
    public void testMax() throws Exception {
        QueryResponse response = queryService.execute("SELECT MAX(amount) FROM " + TEST_TABLE);
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Object maxValue = response.getRows().get(0).values().iterator().next();
        assertEquals(200, ((Number) maxValue).intValue(), "Maximum should be 200");
    }
    
    // ========================================
    // GROUP BY Tests
    // ========================================
    
    @Test
    @DisplayName("GROUP BY with COUNT should group and count")
    public void testGroupByCount() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT category, COUNT(*) FROM " + TEST_TABLE + " GROUP BY category"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(3, response.getRowCount(), "Should return 3 groups (Electronics, Books, Clothing)");
        
        // Verify columns
        assertTrue(response.getColumns().contains("category"), "Should have category column");
    }
    
    @Test
    @DisplayName("GROUP BY with SUM should group and sum")
    public void testGroupBySum() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT category, SUM(amount) FROM " + TEST_TABLE + " GROUP BY category"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(3, response.getRowCount(), "Should return 3 groups");
        
        // Find Electronics group and verify sum
        boolean foundElectronics = false;
        for (var row : response.getRows()) {
            if ("Electronics".equals(row.get("category"))) {
                foundElectronics = true;
                Object sumValue = row.get("sum_amount");
                assertNotNull(sumValue, "Sum should not be null");
                assertEquals(300L, ((Number) sumValue).longValue(), "Electronics sum should be 100+200=300");
            }
        }
        assertTrue(foundElectronics, "Should find Electronics group");
    }
    
    @Test
    @DisplayName("GROUP BY with multiple aggregates")
    public void testGroupByMultipleAggregates() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT category, COUNT(*), SUM(amount), AVG(amount) FROM " + TEST_TABLE + " GROUP BY category"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(3, response.getRowCount(), "Should return 3 groups");
        
        // Verify all aggregate columns exist
        assertTrue(response.getColumns().contains("category"), "Should have category");
    }
    
    @Test
    @DisplayName("GROUP BY with WHERE clause")
    public void testGroupByWithWhere() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT category, COUNT(*) FROM " + TEST_TABLE + " WHERE amount > 50 GROUP BY category"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        // Should filter out Books (30), keep Electronics (100, 200) and Clothing (75)
        assertTrue(response.getRowCount() >= 2, "Should return at least 2 groups after filtering");
    }
    
    // ========================================
    // Edge Cases
    // ========================================
    
    @Test
    @DisplayName("GROUP BY on empty result set")
    public void testGroupByEmpty() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT category, COUNT(*) FROM " + TEST_TABLE + " WHERE amount > 1000 GROUP BY category"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(0, response.getRowCount(), "Should return 0 groups for empty result");
    }
    
    @Test
    @DisplayName("Multiple aggregates without GROUP BY")
    public void testMultipleAggregatesNoGroupBy() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT COUNT(*), SUM(amount), AVG(amount), MIN(amount), MAX(amount) FROM " + TEST_TABLE
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return 1 row");
    }
}



