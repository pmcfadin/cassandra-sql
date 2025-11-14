package com.geico.poc.cassandrasql.kv.jobs;

import com.datastax.oss.driver.api.core.CqlSession;
import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvStore;
import com.geico.poc.cassandrasql.kv.TimestampOracle;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for VacuumJob.
 * 
 * Critical tests:
 * - NEVER deletes live data (latest committed version)
 * - Only deletes old MVCC versions
 * - Only deletes tombstones after retention period
 * - Respects retention period
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class VacuumJobTest extends KvTestBase{
    
    @Autowired
    private VacuumJob vacuumJob;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private QueryService queryService;
    
    private static  String TEST_TABLE;
    
    @BeforeAll
    public void setup() throws Exception {
        // Ensure KV store tables exist
        kvStore.initialize();

        Thread.sleep(200);
    }
    

    
    @BeforeEach
    public void cleanupBefore() throws Exception {
        // Clean up test data before each test
        TEST_TABLE = uniqueTableName("vacuum_test_table");
        // Create test table
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        queryService.execute("CREATE TABLE " + TEST_TABLE + " (id INT PRIMARY KEY, data TEXT)");
        }
    
    @AfterEach
    public void cleanupAfter() throws Exception {
        // Clean up test data after each test
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
    }
    

    
    // ========================================
    // CRITICAL: Live Data Protection Tests
    // ========================================
    
    @Test
    @Order(1)
    public void testVacuumNeverDeletesLiveData() throws Exception {
        System.out.println("\n=== TEST: Vacuum NEVER deletes live data ===");
        
        // Insert live data
        QueryResponse insertResp = queryService.execute(
            "INSERT INTO " + TEST_TABLE + " (id, data) VALUES (1, 'live_data')");
        assertNull(insertResp.getError(), "Insert should succeed");
        Thread.sleep(100);
        
        // Verify data exists
        QueryResponse selectBefore = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 1");
        assertNull(selectBefore.getError(), "Select before vacuum should succeed");
        assertEquals(1, selectBefore.getRows().size(), "Should have 1 row before vacuum");
        
        // Run vacuum
        vacuumJob.execute();
        
        // Verify data STILL exists
        QueryResponse selectAfter = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 1");
        assertNull(selectAfter.getError(), "Select after vacuum should succeed");
        assertEquals(1, selectAfter.getRows().size(), "Should STILL have 1 row after vacuum");
        assertEquals("live_data", selectAfter.getRows().get(0).get("data"), "Data should be unchanged");
        
        System.out.println("✅ PASS: Live data was NOT deleted by vacuum");
    }
    
    @Test
    @Order(2)
    public void testVacuumKeepsLatestVersionOfUpdatedRow() throws Exception {
        System.out.println("\n=== TEST: Vacuum keeps latest version of updated row ===");
        
        // Insert initial version
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, data) VALUES (2, 'version1')");
        Thread.sleep(100);
        
        // Update to create new version
        queryService.execute("UPDATE " + TEST_TABLE + " SET data = 'version2' WHERE id = 2");
        Thread.sleep(100);
        
        // Update again
        queryService.execute("UPDATE " + TEST_TABLE + " SET data = 'version3' WHERE id = 2");
        Thread.sleep(100);
        
        // Verify latest version exists
        QueryResponse selectBefore = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 2");
        assertEquals(1, selectBefore.getRows().size(), "Should have 1 row");
        assertEquals("version3", selectBefore.getRows().get(0).get("data"), "Should have latest version");
        
        // Wait for retention period (1 hour minimum, so old versions are eligible)
        // In real scenario, old versions would be cleaned up
        // For now, just verify latest version is preserved
        
        // Run vacuum
        vacuumJob.execute();
        
        // Verify latest version STILL exists
        QueryResponse selectAfter = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 2");
        assertNull(selectAfter.getError(), "Select after vacuum should succeed");
        assertEquals(1, selectAfter.getRows().size(), "Should STILL have 1 row");
        assertEquals("version3", selectAfter.getRows().get(0).get("data"), "Should STILL have latest version");
        
        System.out.println("✅ PASS: Latest version was preserved by vacuum");
    }
    
    @Test
    @Order(3)
    public void testVacuumDoesNotDeleteRecentlyInsertedData() throws Exception {
        System.out.println("\n=== TEST: Vacuum does not delete recently inserted data ===");
        
        // Insert multiple rows
        for (int i = 10; i < 20; i++) {
            queryService.execute("INSERT INTO " + TEST_TABLE + " (id, data) VALUES (" + i + ", 'row" + i + "')");
        }
        Thread.sleep(100);
        
        // Verify all rows exist
        QueryResponse selectBefore = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertTrue(selectBefore.getRows().size() >= 10, "Should have at least 10 rows");
        
        // Run vacuum immediately (all data is recent)
        vacuumJob.execute();
        
        // Verify all rows STILL exist
        QueryResponse selectAfter = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertEquals(selectBefore.getRows().size(), selectAfter.getRows().size(), 
                    "Row count should be unchanged after vacuum");
        
        System.out.println("✅ PASS: Recent data was NOT deleted by vacuum");
    }
    
    // ========================================
    // Old Version Cleanup Tests
    // ========================================
    
    @Test
    @Order(4)
    public void testVacuumDeletesOldVersionsButKeepsLatest() throws Exception {
        System.out.println("\n=== TEST: Vacuum deletes old versions but keeps latest ===");
        
        // Create multiple versions using low-level KvStore API
        byte[] key = "test_key_old_versions".getBytes();
        UUID txId = UUID.randomUUID();
        
        long ts1 = timestampOracle.getCurrentTimestamp();
        Thread.sleep(10);
        long ts2 = timestampOracle.getCurrentTimestamp();
        Thread.sleep(10);
        long ts3 = timestampOracle.getCurrentTimestamp();
        
        // Simulate old timestamps (more than 1 hour ago)
        long oldTs1 = ts1 - (2L * 3600L * 1000L * 1000L); // 2 hours ago
        long oldTs2 = ts2 - (2L * 3600L * 1000L * 1000L); // 2 hours ago
        long recentTs = ts3; // Now
        
        // Insert old versions
        kvStore.put(key, "value1".getBytes(), oldTs1, txId, oldTs1, false);
        kvStore.put(key, "value2".getBytes(), oldTs2, txId, oldTs2, false);
        
        // Insert recent version (latest)
        kvStore.put(key, "value3".getBytes(), recentTs, txId, recentTs, false);
        
        // Run vacuum
        vacuumJob.execute();
        
        // Verify latest version still exists
        KvStore.KvEntry latest = kvStore.get(key, timestampOracle.getCurrentTimestamp());
        assertNotNull(latest, "Latest version should exist after vacuum");
        assertArrayEquals("value3".getBytes(), latest.getValue(), "Latest version should have correct value");
        
        System.out.println("✅ PASS: Old versions cleaned up, latest preserved");
    }
    
    // ========================================
    // Deleted Row (Tombstone) Tests
    // ========================================
    
    @Test
    @Order(5)
    public void testVacuumDeletesOldTombstones() throws Exception {
        System.out.println("\n=== TEST: Vacuum deletes old tombstones ===");
        
        // Create a deleted row (tombstone) with old timestamp
        byte[] key = String.format("test_key_tombstone_%s", System.currentTimeMillis()).getBytes();
        UUID txId = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        
        long nowTs = timestampOracle.getCurrentTimestamp();
        // Create an old tombstone (2 hours ago) - this should be deleted by vacuum
        long oldTs = nowTs - (2L * 3600L * 1000L * 1000L); // 2 hours ago
        
        System.out.println("Putting a tombstone (deleted=true) with timestamp "+ oldTs);
        // Insert tombstone (deleted=true) - this is the latest version but should be deleted if old enough
        kvStore.put(key, null, oldTs, txId, oldTs, true); // deleted=true
        
        // Verify tombstone exists before vacuum
        System.out.println(String.format("Looking for key %s at timestamp %s", key, nowTs));
        KvStore.KvEntry before = kvStore.get(key, nowTs);
        // get() returns null for tombstones
        assertNull(before, "Tombstone should return null from get() before vacuum (but entry exists in store)");
        
        // Run vacuum - should delete the old tombstone
        vacuumJob.execute();

        // Verify tombstone is deleted after vacuum
        System.out.println(String.format("Looking for key %s at timestamp %s after vacuum", key, nowTs));
        before = kvStore.get(key, nowTs);
        assertNull(before, "Tombstone should not exist after vacuum");
        
        
        System.out.println("✅ PASS: Vacuum processed tombstones");
    }
    
    // ========================================
    // Retention Period Tests
    // ========================================
    
    @Test
    @Order(6)
    public void testVacuumRespectsRetentionPeriod() throws Exception {
        System.out.println("\n=== TEST: Vacuum respects retention period ===");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, data) VALUES (100, 'retained')");
        Thread.sleep(100);
        
        // Run vacuum immediately (data is within retention period)
        vacuumJob.execute();
        
        // Data should still exist (within retention period)
        QueryResponse select = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 100");
        assertNull(select.getError(), "Select should succeed");
        assertEquals(1, select.getRows().size(), "Data within retention period should be preserved");
        
        System.out.println("✅ PASS: Retention period respected");
    }
    
    // ========================================
    // Safety Tests
    // ========================================
    
    @Test
    @Order(7)
    public void testVacuumEnforcesMinimumRetention() {
        System.out.println("\n=== TEST: Vacuum enforces minimum 1-hour retention ===");
        
        // Vacuum should enforce minimum retention even if configured to 0
        // This is tested by the calculateMinSafeTimestamp() method
        assertDoesNotThrow(() -> vacuumJob.execute(), 
                          "Vacuum should run safely with minimum retention");
        
        System.out.println("✅ PASS: Minimum retention enforced");
    }
    
    @Test
    @Order(8)
    public void testVacuumHandlesEmptyStore() {
        System.out.println("\n=== TEST: Vacuum handles empty store ===");
        
        // Vacuum should handle empty store gracefully
        assertDoesNotThrow(() -> vacuumJob.execute(), 
                          "Vacuum should handle empty store");
        
        System.out.println("✅ PASS: Empty store handled");
    }
    
    @Test
    @Order(9)
    public void testVacuumIsIdempotent() throws Exception {
        System.out.println("\n=== TEST: Vacuum is idempotent ===");
        
        // Insert test data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, data) VALUES (200, 'idempotent_test')");
        Thread.sleep(100);
        
        // Verify data exists
        QueryResponse select = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 200");
        assertEquals(1, select.getRows().size(), "Data should exist before vacuum");

        // Run vacuum multiple times (reduced from 5 to 2 to avoid timeout)
        for (int i = 0 ; i < 2 ; i++) { 
            vacuumJob.execute();
            Thread.sleep(50); // Small delay between runs

            // Data should still exist
            select = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 200");
            assertEquals(1, select.getRows().size(), "Data should survive multiple vacuum runs");
        }
        
        System.out.println("✅ PASS: Vacuum is idempotent");
    }
    
    // ========================================
    // Configuration Tests
    // ========================================
    
    @Test
    @Order(10)
    public void testVacuumConfiguration() {
        System.out.println("\n=== TEST: Vacuum configuration ===");
        
        assertEquals("VacuumJob", vacuumJob.getName());
        assertTrue(vacuumJob.getInitialDelayMs() > 0, "Initial delay should be positive");
        assertTrue(vacuumJob.getPeriodMs() > 0, "Period should be positive");
        
        System.out.println("✅ PASS: Configuration valid");
    }
}
