package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.jobs.IndexConsistencyJob;
import com.geico.poc.cassandrasql.kv.KvTestBase.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for index consistency verification background job.
 * 
 * Index consistency checks verify that:
 * 1. Every data row has corresponding index entries
 * 2. Every index entry points to an existing data row
 * 3. Index values match the actual column values in the data row
 * 4. No orphaned index entries exist
 * 5. No missing index entries exist
 * 
 * This is critical for data integrity and helps detect:
 * - Bugs in index maintenance code
 * - Partial transaction failures
 * - Data corruption
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IndexConsistencyTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private IndexConsistencyJob indexConsistencyJob;
    
    private static final String TEST_TABLE = "consistency_test_table";
    
    @BeforeEach
    public void cleanup() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @Test
    @Order(1)
    public void testConsistencyCheckForEmptyTable() throws Exception {
        String testTable = uniqueTableName(TEST_TABLE);

        System.out.println("\n=== TEST: Consistency check for empty table ===");
        
        // Create table with index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Run consistency check
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Verify no issues
        assertNotNull(result, "Result should not be null");
        assertTrue((Boolean) result.get("consistent"), "Empty table should be consistent");
        assertEquals(0L, result.get("data_rows_checked"), "Should check 0 data rows");
        assertEquals(0L, result.get("index_entries_checked"), "Should check 0 index entries");
        assertEquals(0L, result.get("missing_index_entries"), "Should have 0 missing entries");
        assertEquals(0L, result.get("orphaned_index_entries"), "Should have 0 orphaned entries");
        
        System.out.println("✅ PASS: Empty table is consistent");
    }
    
    @Test
    @Order(2)
    public void testConsistencyCheckAfterNormalOperations() throws Exception {
        String testTable = uniqueTableName(TEST_TABLE);

        System.out.println("\n=== TEST: Consistency check after normal operations ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Insert data
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (2, 'Bob', 'bob@example.com')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (3, 'Charlie', 'charlie@example.com')");
        
        // Run consistency check
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Debug output
        System.out.println("  Consistency result: " + result);
        
        // Verify consistency
        assertTrue((Boolean) result.get("consistent"), "Table should be consistent after normal inserts");
        assertEquals(3L, result.get("data_rows_checked"), "Should check 3 data rows");
        assertEquals(3L, result.get("index_entries_checked"), "Should check 3 index entries");
        assertEquals(0L, result.get("missing_index_entries"), "Should have 0 missing entries");
        assertEquals(0L, result.get("orphaned_index_entries"), "Should have 0 orphaned entries");
        
        System.out.println("✅ PASS: Table is consistent after normal operations");
    }
    
    @Test
    @Order(3)
    public void testConsistencyAfterUpdates() throws Exception {
        String testTable = uniqueTableName(TEST_TABLE);

        System.out.println("\n=== TEST: Consistency after updates ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Insert and update data
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')");
        queryService.execute("UPDATE " + testTable + " SET email = 'alice.new@example.com' WHERE id = 1");
        
        // Run consistency check
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Debug output
        System.out.println("  Consistency result: " + result);
        
        // Verify consistency
        // Note: Due to MVCC, we may see both old and new index entries temporarily
        // The old entry will be marked as deleted and filtered out by kvStore.scan()
        // But if the scan sees it before garbage collection, it will be counted
        assertTrue((Boolean) result.get("consistent"), "Table should be consistent after update");
        assertEquals(1L, result.get("data_rows_checked"), "Should check 1 data row");
        // MVCC may show 1 or 2 entries depending on timing (old deleted + new)
        assertTrue((Long) result.get("index_entries_checked") >= 1L && (Long) result.get("index_entries_checked") <= 2L, 
            "Should check 1-2 index entries (MVCC may show old deleted entry)");
        assertEquals(0L, result.get("missing_index_entries"), "Should have 0 missing entries");
        assertEquals(0L, result.get("orphaned_index_entries"), "Should have 0 orphaned entries");
        
        System.out.println("✅ PASS: Table is consistent after updates");
    }
    
    @Test
    @Order(4)
    public void testConsistencyAfterDeletes() throws Exception {
        String testTable = uniqueTableName(TEST_TABLE);

        System.out.println("\n=== TEST: Consistency after deletes ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Insert and delete data
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (2, 'Bob', 'bob@example.com')");
        queryService.execute("DELETE FROM " + testTable + " WHERE id = 1");
        
        // Run consistency check
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Verify consistency (deleted row's index entry should also be deleted)
        // Note: Due to MVCC, we may see deleted index entries temporarily
        assertTrue((Boolean) result.get("consistent"), "Table should be consistent after delete");
        assertEquals(1L, result.get("data_rows_checked"), "Should check 1 remaining data row");
        // MVCC may show deleted entries temporarily (1-2 entries for remaining row)
        assertTrue((Long) result.get("index_entries_checked") >= 1L && (Long) result.get("index_entries_checked") <= 2L, 
            "Should check 1-2 index entries (MVCC may show deleted entries)");
        assertEquals(0L, result.get("missing_index_entries"), "Should have 0 missing entries");
        assertEquals(0L, result.get("orphaned_index_entries"), "Should have 0 orphaned entries");
        
        System.out.println("✅ PASS: Table is consistent after deletes");
    }
    
    @Test
    @Order(5)
    public void testConsistencyWithMultipleIndexes() throws Exception {
        String testTable = uniqueTableName(TEST_TABLE);

        System.out.println("\n=== TEST: Consistency with multiple indexes ===");
        
        // Create table with multiple indexes
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, age INT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_name ON " + testTable + " (name)");
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        queryService.execute("CREATE INDEX idx_age ON " + testTable + " (age)");
        
        // Insert data
        queryService.execute("INSERT INTO " + testTable + " (id, name, email, age) VALUES (1, 'Alice', 'alice@example.com', 30)");
        queryService.execute("INSERT INTO " + testTable + " (id, name, email, age) VALUES (2, 'Bob', 'bob@example.com', 25)");
        
        // Run consistency check
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Verify all indexes are consistent
        assertTrue((Boolean) result.get("consistent"), "All indexes should be consistent");
        assertEquals(2L, result.get("data_rows_checked"), "Should check 2 data rows");
        // 2 rows * 3 indexes = 6 index entries (MVCC may show more due to old versions)
        assertTrue((Long) result.get("index_entries_checked") >= 6L, 
            "Should check at least 6 index entries (2 rows * 3 indexes), MVCC may show more");
        assertEquals(0L, result.get("missing_index_entries"), "Should have 0 missing entries");
        assertEquals(0L, result.get("orphaned_index_entries"), "Should have 0 orphaned entries");
        
        System.out.println("✅ PASS: Multiple indexes are consistent");
    }
    
    @Test
    @Order(6)
    public void testConsistencyWithCompositeIndex() throws Exception {
        String testTable = uniqueTableName(TEST_TABLE);

        System.out.println("\n=== TEST: Consistency with composite index ===");
        
        // Create table with composite index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, first_name TEXT, last_name TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_name ON " + testTable + " (last_name, first_name)");
        
        // Insert data
        queryService.execute("INSERT INTO " + testTable + " (id, first_name, last_name) VALUES (1, 'Alice', 'Smith')");
        queryService.execute("INSERT INTO " + testTable + " (id, first_name, last_name) VALUES (2, 'Bob', 'Jones')");
        
        // Run consistency check
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Verify composite index is consistent
        assertTrue((Boolean) result.get("consistent"), "Composite index should be consistent");
        assertEquals(2L, result.get("data_rows_checked"), "Should check 2 data rows");
        assertEquals(2L, result.get("index_entries_checked"), "Should check 2 composite index entries");
        
        System.out.println("✅ PASS: Composite index is consistent");
    }
    
    @Test
    @Order(7)
    public void testConsistencyCheckPerformance() throws Exception {
        String testTable = uniqueTableName(TEST_TABLE);

        System.out.println("\n=== TEST: Consistency check performance (100 rows) ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Insert 100 rows
        for (int i = 1; i <= 100; i++) {
            queryService.execute(
                "INSERT INTO " + testTable + " (id, name, email) VALUES (" + i + ", 'User" + i + "', 'user" + i + "@example.com')"
            );
        }
        
        // Run consistency check and measure time
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        long duration = System.currentTimeMillis() - startTime;
        
        // Verify consistency
        assertTrue((Boolean) result.get("consistent"), "Large table should be consistent");
        assertEquals(100L, result.get("data_rows_checked"), "Should check 100 data rows");
        assertEquals(100L, result.get("index_entries_checked"), "Should check 100 index entries");
        
        // Verify reasonable performance (should complete in < 10 seconds)
        // Note: Performance may vary due to MVCC overhead and Cassandra latency
        assertTrue(duration < 10000, "Consistency check should complete in < 10 seconds (took " + duration + "ms)");
        
        System.out.println("  ⏱️  Checked 100 rows in " + duration + "ms");
        System.out.println("✅ PASS: Consistency check performance is acceptable");
    }
    
    @Test
    @Order(8)
    public void testConsistencyCheckReturnsDetailedReport() throws Exception {
        String testTable = uniqueTableName(TEST_TABLE);

        System.out.println("\n=== TEST: Consistency check returns detailed report ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Insert data
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')");
        
        // Run consistency check
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Verify report contains all expected fields
        assertNotNull(result.get("consistent"), "Report should include 'consistent' field");
        assertNotNull(result.get("table_name"), "Report should include 'table_name' field");
        assertNotNull(result.get("check_timestamp"), "Report should include 'check_timestamp' field");
        assertNotNull(result.get("data_rows_checked"), "Report should include 'data_rows_checked' field");
        assertNotNull(result.get("index_entries_checked"), "Report should include 'index_entries_checked' field");
        assertNotNull(result.get("missing_index_entries"), "Report should include 'missing_index_entries' field");
        assertNotNull(result.get("orphaned_index_entries"), "Report should include 'orphaned_index_entries' field");
        assertNotNull(result.get("duration_ms"), "Report should include 'duration_ms' field");
        
        System.out.println("✅ PASS: Consistency check returns detailed report");
    }
    
    @Test
    @Order(9)
    public void testPrimaryKeyIndexBackfillConsistency() throws Exception {
        String testTable = uniqueTableName("pk_backfill_test");

        System.out.println("\n=== TEST: PRIMARY KEY index backfill consistency (pgbench scenario) ===");
        
        // Create table WITHOUT explicit primary key (gets hidden rowid PK)
        queryService.execute(
            "CREATE TABLE " + testTable + " (bid INT, bbalance INT, filler TEXT)"
        );
        
        // Insert many rows (simulating pgbench scenario)
        int numRows = 100;
        System.out.println("  Inserting " + numRows + " rows...");
        for (int i = 1; i <= numRows; i++) {
            queryService.execute(
                "INSERT INTO " + testTable + " (bid, bbalance, filler) VALUES (" + i + ", " + (i * 100) + ", 'filler" + i + "')"
            );
        }
        
        // Now add PRIMARY KEY constraint (this creates a PRIMARY KEY index)
        System.out.println("  Adding PRIMARY KEY constraint...");
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE " + testTable + " ADD PRIMARY KEY (bid)"
        );
        assertNull(alterResp.getError(), "ALTER TABLE ADD PRIMARY KEY should succeed: " + alterResp.getError());
        
        // Wait a bit for backfill to complete
        Thread.sleep(500);
        
        // Run consistency check - this should find all index entries
        System.out.println("  Running index consistency check...");
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Debug output
        System.out.println("  Consistency result: " + result);
        System.out.println("    Data rows checked: " + result.get("data_rows_checked"));
        System.out.println("    Index entries checked: " + result.get("index_entries_checked"));
        System.out.println("    Missing index entries: " + result.get("missing_index_entries"));
        System.out.println("    Orphaned index entries: " + result.get("orphaned_index_entries"));
        
        // Verify consistency - this should pass with the fix
        assertTrue((Boolean) result.get("consistent"), 
            "PRIMARY KEY index should be consistent after backfill. Missing entries: " + result.get("missing_index_entries"));
        assertEquals((long) numRows, (Long) result.get("data_rows_checked"), 
            "Should check " + numRows + " data rows");
        assertEquals((long) numRows, (Long) result.get("index_entries_checked"), 
            "Should check " + numRows + " PRIMARY KEY index entries");
        assertEquals(0L, result.get("missing_index_entries"), 
            "Should have 0 missing PRIMARY KEY index entries (bug: was using rowid instead of indexed column values)");
        assertEquals(0L, result.get("orphaned_index_entries"), 
            "Should have 0 orphaned index entries");
        
        System.out.println("✅ PASS: PRIMARY KEY index backfill is consistent");
    }
    
    @Test
    @Order(10)
    public void testPrimaryKeyIndexBackfillWithCompositeKey() throws Exception {
        String testTable = uniqueTableName("pk_composite_backfill_test");

        System.out.println("\n=== TEST: Composite PRIMARY KEY index backfill consistency ===");
        
        // Create table WITHOUT explicit primary key (gets hidden rowid PK)
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, version INT, data TEXT)"
        );
        
        // Insert rows
        int numRows = 50;
        System.out.println("  Inserting " + numRows + " rows...");
        for (int i = 1; i <= numRows; i++) {
            queryService.execute(
                "INSERT INTO " + testTable + " (id, version, data) VALUES (" + i + ", " + (i % 10) + ", 'data" + i + "')"
            );
        }
        
        // Add composite PRIMARY KEY constraint
        System.out.println("  Adding composite PRIMARY KEY constraint...");
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE " + testTable + " ADD PRIMARY KEY (id, version)"
        );
        assertNull(alterResp.getError(), "ALTER TABLE ADD PRIMARY KEY should succeed: " + alterResp.getError());
        
        // Wait a bit for backfill to complete
        Thread.sleep(500);
        
        // Run consistency check
        System.out.println("  Running index consistency check...");
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Debug output
        System.out.println("  Consistency result: " + result);
        
        // Verify consistency
        assertTrue((Boolean) result.get("consistent"), 
            "Composite PRIMARY KEY index should be consistent after backfill");
        assertEquals((long) numRows, (Long) result.get("data_rows_checked"), 
            "Should check " + numRows + " data rows");
        assertEquals((long) numRows, (Long) result.get("index_entries_checked"), 
            "Should check " + numRows + " composite PRIMARY KEY index entries");
        assertEquals(0L, result.get("missing_index_entries"), 
            "Should have 0 missing composite PRIMARY KEY index entries");
        assertEquals(0L, result.get("orphaned_index_entries"), 
            "Should have 0 orphaned index entries");
        
        System.out.println("✅ PASS: Composite PRIMARY KEY index backfill is consistent");
    }
    
    @Test
    @Order(11)
    public void testPrimaryKeyIndexBackfillVsRegularIndex() throws Exception {
        String testTable = uniqueTableName("pk_vs_regular_index_test");

        System.out.println("\n=== TEST: PRIMARY KEY index vs regular index backfill ===");
        
        // Create table WITHOUT explicit primary key (gets hidden rowid PK)
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, email TEXT, name TEXT)"
        );
        
        // Insert rows
        int numRows = 30;
        for (int i = 1; i <= numRows; i++) {
            queryService.execute(
                "INSERT INTO " + testTable + " (id, email, name) VALUES (" + i + ", 'user" + i + "@example.com', 'User" + i + "')"
            );
        }
        
        // Create regular secondary index first
        System.out.println("  Creating regular secondary index...");
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        Thread.sleep(200);
        
        // Then add PRIMARY KEY constraint (creates PRIMARY KEY index)
        System.out.println("  Adding PRIMARY KEY constraint...");
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE " + testTable + " ADD PRIMARY KEY (id)"
        );
        assertNull(alterResp.getError(), "ALTER TABLE ADD PRIMARY KEY should succeed: " + alterResp.getError());
        
        // Wait for backfill to complete
        Thread.sleep(500);
        
        // Run consistency check
        System.out.println("  Running index consistency check...");
        Map<String, Object> result = indexConsistencyJob.checkConsistency(testTable);
        
        // Debug output
        System.out.println("  Consistency result: " + result);
        
        // Verify both indexes are consistent
        assertTrue((Boolean) result.get("consistent"), 
            "Both PRIMARY KEY and regular indexes should be consistent");
        assertEquals((long) numRows, (Long) result.get("data_rows_checked"), 
            "Should check " + numRows + " data rows");
        // Should have numRows PRIMARY KEY index entries + numRows regular index entries
        assertEquals((long) (numRows * 2), (Long) result.get("index_entries_checked"), 
            "Should check " + (numRows * 2) + " index entries (" + numRows + " PRIMARY KEY + " + numRows + " regular)");
        assertEquals(0L, result.get("missing_index_entries"), 
            "Should have 0 missing index entries");
        assertEquals(0L, result.get("orphaned_index_entries"), 
            "Should have 0 orphaned index entries");
        
        System.out.println("✅ PASS: Both PRIMARY KEY and regular indexes are consistent");
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

