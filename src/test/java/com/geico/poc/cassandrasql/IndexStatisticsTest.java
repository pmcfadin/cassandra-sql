package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import com.geico.poc.cassandrasql.kv.jobs.IndexStatisticsJob;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for index statistics collection background job.
 * 
 * Index statistics are used by the cost-based optimizer to:
 * 1. Choose between index scan vs full table scan
 * 2. Estimate query cost
 * 3. Select the best index when multiple are available
 * 
 * Statistics collected:
 * - Row count (total rows in table)
 * - Index cardinality (distinct values in indexed columns)
 * - Index size (number of index entries)
 * - Selectivity (cardinality / row count)
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IndexStatisticsTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private IndexStatisticsJob indexStatisticsJob;
    
    private String TEST_TABLE;
    
    @BeforeEach
    public void cleanup() {
        TEST_TABLE = uniqueTableName("stats_test_table");
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @Test
    @Order(1)
    public void testCollectStatisticsForEmptyTable() throws Exception {
        System.out.println("\n=== TEST: Collect statistics for empty table ===");
        
        // Create table with index
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + TEST_TABLE + " (email)");
        
        // Collect statistics
        indexStatisticsJob.collectStatistics(TEST_TABLE);
        
        // Verify statistics
        TableMetadata table = schemaManager.getTable(TEST_TABLE);
        assertNotNull(table, "Table should exist");
        
        Map<String, Object> stats = indexStatisticsJob.getStatistics(TEST_TABLE);
        assertNotNull(stats, "Statistics should exist");
        assertEquals(0L, stats.get("row_count"), "Row count should be 0");
        
        System.out.println("✅ PASS: Statistics collected for empty table");
    }
    
    @Test
    @Order(2)
    public void testCollectStatisticsAfterInserts() throws Exception {
        System.out.println("\n=== TEST: Collect statistics after inserts ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + TEST_TABLE + " (email)");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email) VALUES (2, 'Bob', 'bob@example.com')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email) VALUES (3, 'Charlie', 'charlie@example.com')");
        
        // Collect statistics
        indexStatisticsJob.collectStatistics(TEST_TABLE);
        
        // Verify statistics
        Map<String, Object> stats = indexStatisticsJob.getStatistics(TEST_TABLE);
        assertNotNull(stats, "Statistics should exist");
        assertEquals(3L, stats.get("row_count"), "Row count should be 3");
        
        // Verify index statistics
        @SuppressWarnings("unchecked")
        Map<String, Object> indexStats = (Map<String, Object>) stats.get("idx_email");
        assertNotNull(indexStats, "Index statistics should exist");
        assertEquals(3L, indexStats.get("index_size"), "Index should have 3 entries");
        assertEquals(3L, indexStats.get("cardinality"), "Cardinality should be 3 (all unique)");
        assertEquals(1.0, (Double) indexStats.get("selectivity"), 0.01, "Selectivity should be 1.0 (all unique)");
        
        System.out.println("✅ PASS: Statistics collected after inserts");
    }
    
    @Test
    @Order(3)
    public void testStatisticsWithDuplicateValues() throws Exception {
        System.out.println("\n=== TEST: Statistics with duplicate values ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, status TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_status ON " + TEST_TABLE + " (status)");
        
        // Insert data with duplicates
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, status) VALUES (1, 'Alice', 'active')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, status) VALUES (2, 'Bob', 'active')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, status) VALUES (3, 'Charlie', 'inactive')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, status) VALUES (4, 'Dave', 'active')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, status) VALUES (5, 'Eve', 'inactive')");
        
        // Collect statistics
        indexStatisticsJob.collectStatistics(TEST_TABLE);
        
        // Verify statistics
        Map<String, Object> stats = indexStatisticsJob.getStatistics(TEST_TABLE);
        assertEquals(5L, stats.get("row_count"), "Row count should be 5");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> indexStats = (Map<String, Object>) stats.get("idx_status");
        assertEquals(5L, indexStats.get("index_size"), "Index should have 5 entries");
        assertEquals(2L, indexStats.get("cardinality"), "Cardinality should be 2 (active, inactive)");
        assertEquals(0.4, (Double) indexStats.get("selectivity"), 0.01, "Selectivity should be 0.4 (2/5)");
        
        System.out.println("✅ PASS: Statistics with duplicate values");
    }
    
    @Test
    @Order(4)
    public void testStatisticsForCompositeIndex() throws Exception {
        System.out.println("\n=== TEST: Statistics for composite index ===");
        
        // Create table and composite index
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (id INT, first_name TEXT, last_name TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_name ON " + TEST_TABLE + " (last_name, first_name)");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, first_name, last_name) VALUES (1, 'Alice', 'Smith')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, first_name, last_name) VALUES (2, 'Bob', 'Smith')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, first_name, last_name) VALUES (3, 'Charlie', 'Jones')");
        
        // Collect statistics
        indexStatisticsJob.collectStatistics(TEST_TABLE);
        
        // Verify statistics
        Map<String, Object> stats = indexStatisticsJob.getStatistics(TEST_TABLE);
        assertEquals(3L, stats.get("row_count"), "Row count should be 3");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> indexStats = (Map<String, Object>) stats.get("idx_name");
        assertNotNull(indexStats, "Composite index statistics should exist");
        assertEquals(3L, indexStats.get("index_size"), "Index should have 3 entries");
        // Composite index cardinality is based on unique combinations
        assertEquals(3L, indexStats.get("cardinality"), "Cardinality should be 3 (all combinations unique)");
        
        System.out.println("✅ PASS: Statistics for composite index");
    }
    
    @Test
    @Order(5)
    public void testStatisticsForMultipleIndexes() throws Exception {
        System.out.println("\n=== TEST: Statistics for multiple indexes ===");
        
        // Create table with multiple indexes
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, email TEXT, age INT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_name ON " + TEST_TABLE + " (name)");
        queryService.execute("CREATE INDEX idx_email ON " + TEST_TABLE + " (email)");
        queryService.execute("CREATE INDEX idx_age ON " + TEST_TABLE + " (age)");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email, age) VALUES (1, 'Alice', 'alice@example.com', 30)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email, age) VALUES (2, 'Bob', 'bob@example.com', 30)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email, age) VALUES (3, 'Charlie', 'charlie@example.com', 25)");
        
        // Collect statistics
        indexStatisticsJob.collectStatistics(TEST_TABLE);
        
        // Verify statistics for all indexes
        Map<String, Object> stats = indexStatisticsJob.getStatistics(TEST_TABLE);
        assertEquals(3L, stats.get("row_count"), "Row count should be 3");
        
        // Verify each index has statistics
        assertNotNull(stats.get("idx_name"), "idx_name statistics should exist");
        assertNotNull(stats.get("idx_email"), "idx_email statistics should exist");
        assertNotNull(stats.get("idx_age"), "idx_age statistics should exist");
        
        // Verify age index has lower selectivity (duplicates)
        @SuppressWarnings("unchecked")
        Map<String, Object> ageStats = (Map<String, Object>) stats.get("idx_age");
        assertEquals(2L, ageStats.get("cardinality"), "Age cardinality should be 2 (30, 25)");
        assertTrue((Double) ageStats.get("selectivity") < 1.0, "Age selectivity should be < 1.0");
        
        System.out.println("✅ PASS: Statistics for multiple indexes");
    }
    
    @Test
    @Order(6)
    public void testStatisticsUpdateAfterDataChanges() throws Exception {
        System.out.println("\n=== TEST: Statistics update after data changes ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + TEST_TABLE + " (email)");
        
        // Insert initial data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email) VALUES (2, 'Bob', 'bob@example.com')");
        
        // Collect initial statistics
        indexStatisticsJob.collectStatistics(TEST_TABLE);
        Map<String, Object> stats1 = indexStatisticsJob.getStatistics(TEST_TABLE);
        assertEquals(2L, stats1.get("row_count"), "Initial row count should be 2");
        
        // Insert more data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email) VALUES (3, 'Charlie', 'charlie@example.com')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, email) VALUES (4, 'Dave', 'dave@example.com')");
        
        // Collect updated statistics
        indexStatisticsJob.collectStatistics(TEST_TABLE);
        Map<String, Object> stats2 = indexStatisticsJob.getStatistics(TEST_TABLE);
        assertEquals(4L, stats2.get("row_count"), "Updated row count should be 4");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> indexStats = (Map<String, Object>) stats2.get("idx_email");
        assertEquals(4L, indexStats.get("index_size"), "Index size should be updated to 4");
        
        System.out.println("✅ PASS: Statistics update after data changes");
    }
    
    @Test
    @Order(7)
    public void testStatisticsForTableWithoutIndexes() throws Exception {
        System.out.println("\n=== TEST: Statistics for table without indexes ===");
        
        // Create table WITHOUT indexes
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, PRIMARY KEY (id))"
        );
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name) VALUES (2, 'Bob')");
        
        // Collect statistics
        indexStatisticsJob.collectStatistics(TEST_TABLE);
        
        // Verify statistics
        Map<String, Object> stats = indexStatisticsJob.getStatistics(TEST_TABLE);
        assertNotNull(stats, "Statistics should exist even without indexes");
        assertEquals(2L, stats.get("row_count"), "Row count should be 2");
        
        // Verify no index statistics (only metadata fields, no index-specific stats)
        // Expected fields: row_count, table_name, collection_timestamp, collection_duration_ms
        assertTrue(stats.containsKey("row_count"), "Should have row_count");
        assertTrue(stats.containsKey("table_name"), "Should have table_name");
        assertTrue(stats.containsKey("collection_timestamp"), "Should have collection_timestamp");
        assertTrue(stats.containsKey("collection_duration_ms"), "Should have collection_duration_ms");
        assertEquals(4, stats.size(), "Should only have metadata fields (no index stats)");
        
        System.out.println("✅ PASS: Statistics for table without indexes");
    }
    
    @AfterEach
    public void cleanupAfter() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore
        }
    }
}

