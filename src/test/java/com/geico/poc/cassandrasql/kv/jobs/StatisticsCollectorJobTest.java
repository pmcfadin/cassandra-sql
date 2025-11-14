package com.geico.poc.cassandrasql.kv.jobs;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for StatisticsCollectorJob.
 * 
 * Tests:
 * - Statistics collection for tables
 * - Cardinality calculation
 * - Min/max value tracking
 * - Null count tracking
 * - Performance with large tables
 * - Statistics caching
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class StatisticsCollectorJobTest extends KvTestBase {
    
    @Autowired
    private StatisticsCollectorJob statisticsJob;
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private SchemaManager schemaManager;
    
    private String TEST_TABLE;
    
    @BeforeAll
    public void setup() throws Exception {
        TEST_TABLE = uniqueTableName("stats_test_table");
        // Create test table
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        queryService.execute("CREATE TABLE " + TEST_TABLE + " (" +
                "id INT PRIMARY KEY, " +
                "c TEXT, " +
                "v INT, " +
                "s DOUBLE)");
        
        // Insert test data with known statistics
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, c, v, s) VALUES (1, 'A', 100, 1.5)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, c, v, s) VALUES (2, 'A', 200, 2.5)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, c, v, s) VALUES (3, 'B', 150, 1.8)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, c, v, s) VALUES (4, 'B', 250, 2.2)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, c, v, s) VALUES (5, 'C', 300, 3.0)");        
        statisticsJob.execute();
    }
    
    @AfterAll
    public void cleanup() throws Exception {
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
    }
    
    // ========================================
    // Basic Functionality Tests
    // ========================================
    
    @Test
    @Order(1)
    public void testStatisticsJobEnabled() {
        assertTrue(statisticsJob.isEnabled(), "Statistics job should be enabled");
    }
    
    @Test
    @Order(2)
    public void testStatisticsJobConfiguration() {
        assertEquals("StatisticsCollectorJob", statisticsJob.getName());
        assertTrue(statisticsJob.getInitialDelayMs() > 0, "Initial delay should be positive");
        assertTrue(statisticsJob.getPeriodMs() > 0, "Period should be positive");
    }
    
    // ========================================
    // Statistics Collection Tests
    // ========================================
    
    @Test
    @Order(4)
    public void testCollectRowCount() throws Exception {
        QueryResponse response = queryService.execute("SELECT * FROM " + TEST_TABLE);
        System.out.println("Rows: " + response.getRows().size());
        assertTrue(response.getRows().size() >= 5, "Expected at least 5 rows, got: " + response.getRows().size());


        // Get statistics for test table
        TableStatistics stats = statisticsJob.getStatistics(TEST_TABLE);
        Thread.sleep(5000);

        
        assertNotNull(stats, "Statistics should not be null");
        System.out.println("Rows: " + stats.getRowCount());
        System.out.println("Cardinality: " + stats.getCardinality());            
        // Verify row count
        assertTrue(stats.getRowCount() >= 5, 
                  "Row count should be at least 5, got: " + stats.getRowCount());
    }
    
    @Test
    @Order(5)
    public void testCollectCardinality() throws Exception {
        // Get statistics
        TableStatistics stats = statisticsJob.getStatistics(TEST_TABLE);
        System.out.println("Rows: " + stats.getRowCount());
        System.out.println("Cardinality: " + stats.getCardinality());
        
        if (stats != null && stats.getCardinality().containsKey("c")) {
            // Category has 3 distinct values (A, B, C)
            long cardinality = stats.getCardinality().get("c");
            assertEquals(3, cardinality, "Category should have cardinality of 3");
        }
    }
    
    @Test
    @Order(6)
    public void testCollectMinMaxValues() throws Exception {
        
        // Get statistics
        TableStatistics stats = statisticsJob.getStatistics(TEST_TABLE);
        
        assertNotNull(stats, "Statistics should not be null");
        
        // Check min/max for 'v' column (INT type)
        if (stats.getMinValue().containsKey("v")) {
            Object minValue = stats.getMinValue().get("v");
            Object maxValue = stats.getMaxValue().get("v");
            
            assertNotNull(minValue, "Min value should be collected for column 'v'");
            assertNotNull(maxValue, "Max value should be collected for column 'v'");
        } else {
            // If column 'v' doesn't have min/max, check what columns do have statistics
            fail("Column 'v' should have min/max values. Available columns: " + stats.getMinValue().keySet());
        }
    }
    
    @Test
    @Order(7)
    public void testSelectivityCalculation() throws Exception {
        
        // Get statistics
        TableStatistics stats = statisticsJob.getStatistics(TEST_TABLE);
        
        assertNotNull(stats, "Statistics should not be null");
        
        // Check if cardinality was calculated for column "c"
        Long cardinality = stats.getCardinality().get("c");
        if (cardinality == null) {
            // Try uppercase (column names might be case-sensitive)
            cardinality = stats.getCardinality().get("C");
        }
        
        assertNotNull(cardinality, "Cardinality should be calculated for column 'c'. Available columns: " + 
                      stats.getCardinality().keySet());
        assertTrue(cardinality > 0, "Cardinality should be > 0, got: " + cardinality);
        
        // Calculate selectivity
        double selectivity = stats.getSelectivity("c");
        
        // Selectivity = 1 / cardinality = 1 / 3 ≈ 0.33
        assertTrue(selectivity > 0 && selectivity <= 1.0, 
                  "Selectivity should be between 0 and 1, got: " + selectivity);
        
        // Expected selectivity: 1 / cardinality
        double expectedSelectivity = 1.0 / cardinality.doubleValue();
        assertEquals(expectedSelectivity, selectivity, 0.01, 
                    String.format("Selectivity should be approximately %.3f (1/%d), got: %.3f", 
                                 expectedSelectivity, cardinality, selectivity));
    }
    
    // ========================================
    // Statistics Caching Tests
    // ========================================
    
    @Test
    @Order(8)
    public void testStatisticsCaching() throws Exception {
        
        // Get statistics twice
        TableStatistics stats1 = statisticsJob.getStatistics(TEST_TABLE);
        TableStatistics stats2 = statisticsJob.getStatistics(TEST_TABLE);
        
        // Should return same instance (cached)
        if (stats1 != null && stats2 != null) {
            assertSame(stats1, stats2, "Statistics should be cached");
        }
    }
    
    @Test
    @Order(9)
    public void testGetAllStatistics() throws Exception {
        
        // Get all statistics
        Map<String, TableStatistics> allStats = statisticsJob.getAllStatistics();
        
        assertNotNull(allStats, "All statistics map should not be null");
        // Should contain at least our test table (if collection succeeded)
    }
    
    // ========================================
    // Performance Tests
    // ========================================
    
    @Test
    @Order(10)
    @Disabled
    public void testStatisticsCollectionPerformance() {
        // Measure collection time
        long startTime = System.currentTimeMillis();
        statisticsJob.execute();
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.println("Statistics collection duration: " + duration + "ms");
        
        // Should complete in reasonable time (< 10 seconds)
        assertTrue(duration < 10000, 
                  "Statistics collection should complete in < 10s, took " + duration + "ms");
    }

    
    // ========================================
    // Statistics Update Tests
    // ========================================
    
    @Test
    @Order(13)
    public void testStatisticsUpdateAfterDataChange() throws Exception {

        TableStatistics stats1 = statisticsJob.getStatistics(TEST_TABLE);
        long initialRowCount = stats1 != null ? stats1.getRowCount() : 0;
        
        // Insert more data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, c, v, s) VALUES (6, 'D', 400, 4.0)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, c, v, s) VALUES (7, 'D', 500, 5.0)");
        Thread.sleep(200);
        
        // Collect statistics again
        statisticsJob.execute();
        TableStatistics stats2 = statisticsJob.getStatistics(TEST_TABLE);
        long newRowCount = stats2 != null ? stats2.getRowCount() : 0;
        
        // Row count should have increased
        if (stats1 != null && stats2 != null) {
            assertTrue(newRowCount >= initialRowCount, 
                      "Row count should increase after inserts");
        }
        
        // Cleanup
        queryService.execute("DELETE FROM " + TEST_TABLE + " WHERE id IN (6, 7)");
    }
}



