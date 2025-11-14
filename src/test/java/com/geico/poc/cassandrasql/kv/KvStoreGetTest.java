package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.kv.jobs.VacuumJob;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KvStore.get() method, specifically testing timestamp filtering logic.
 * 
 * Critical tests:
 * - get() correctly filters by commit_ts, not just ts
 * - get() handles cases where ts <= readTimestamp but commit_ts > readTimestamp
 * - get() returns the latest committed version with commit_ts <= readTimestamp
 * - get() correctly handles uncommitted versions
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "cassandra-sql.kv.vacuum.enabled=false"  // Explicitly disable vacuum for these tests
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KvStoreGetTest extends KvTestBase {
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired(required = false)
    private VacuumJob vacuumJob;
    
    @BeforeAll
    public void setup() throws Exception {
        kvStore.initialize();
        
        if (vacuumJob != null && vacuumJob.isEnabled()) {
            System.out.println("⚠️  WARNING: Vacuum is enabled - this may interfere with version tests");
        }
    }
    
    /**
     * Test that get() correctly filters by commit_ts, not just ts.
     * 
     * Scenario:
     * - Version 1: ts=100, commit_ts=200
     * - Version 2: ts=150, commit_ts=250
     * - readTimestamp=180
     * 
     * Expected: Should return Version 1 (commit_ts=200 > 180, so Version 2 is excluded)
     * Actually: Version 1 has commit_ts=200 > 180, so it should be excluded too!
     * 
     * Let me fix the scenario:
     * - Version 1: ts=100, commit_ts=150
     * - Version 2: ts=120, commit_ts=200
     * - readTimestamp=180
     * 
     * Expected: Should return Version 1 (commit_ts=150 <= 180, commit_ts=200 > 180)
     */
    @Test
    @Order(1)
    public void testGetFiltersByCommitTsNotTs() throws Exception {
        System.out.println("\n=== TEST: get() filters by commit_ts, not just ts ===");
        
        String testTableName = uniqueTableName("kvstore_get_test");
        queryService.execute("CREATE TABLE " + testTableName + " (id INT PRIMARY KEY, data TEXT)");
        TableMetadata testTable = schemaManager.getTable(testTableName);
        assertNotNull(testTable, "Test table should exist");
        long tableId = testTable.getTableId();
        
        byte[] key = KeyEncoder.encodeTableDataKey(tableId, java.util.Arrays.asList(1), 0);
        
        // Create versions with different ts and commit_ts
        // Version 1: ts=100, commit_ts=150 (committed before readTimestamp)
        long ts1 = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long commitTs1 = timestampOracle.allocateCommitTimestamp();
        
        // Version 2: ts=120, commit_ts=200 (committed after readTimestamp)
        long ts2 = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long commitTs2 = timestampOracle.allocateCommitTimestamp();
        
        // Insert versions
        kvStore.put(key, "value1".getBytes(), ts1, UUID.randomUUID(), commitTs1, false);
        Thread.sleep(50);
        kvStore.put(key, "value2".getBytes(), ts2, UUID.randomUUID(), commitTs2, false);
        Thread.sleep(100);
        
        // Read at timestamp between commitTs1 and commitTs2
        long readTimestamp = (commitTs1 + commitTs2) / 2;
        
        System.out.println("Version 1: ts=" + ts1 + ", commit_ts=" + commitTs1);
        System.out.println("Version 2: ts=" + ts2 + ", commit_ts=" + commitTs2);
        System.out.println("readTimestamp=" + readTimestamp);
        
        // get() should return Version 1 (commit_ts=150 <= readTimestamp)
        // Version 2 should be excluded (commit_ts=200 > readTimestamp)
        KvStore.KvEntry result = kvStore.get(key, readTimestamp);
        
        assertNotNull(result, "Should return a version");
        assertEquals("value1", new String(result.getValue()), 
            "Should return Version 1 (commit_ts <= readTimestamp)");
        assertEquals(commitTs1, result.getCommitTs().longValue(), 
            "Should return Version 1 with commit_ts=" + commitTs1);
        
        // Clean up
        queryService.execute("DROP TABLE IF EXISTS " + testTableName);
        
        System.out.println("✅ PASS: get() correctly filters by commit_ts");
    }
    
    /**
     * Test that get() handles cases where ts <= readTimestamp but commit_ts > readTimestamp.
     * 
     * Scenario:
     * - Version: ts=100, commit_ts=200
     * - readTimestamp=150
     * 
     * Expected: Should return null (commit_ts=200 > 150, so version is not visible)
     */
    @Test
    @Order(2)
    public void testGetExcludesVersionsWithCommitTsAfterReadTimestamp() throws Exception {
        System.out.println("\n=== TEST: get() excludes versions with commit_ts > readTimestamp ===");
        
        String testTableName = uniqueTableName("kvstore_get_exclude_test");
        queryService.execute("CREATE TABLE " + testTableName + " (id INT PRIMARY KEY, data TEXT)");
        TableMetadata testTable = schemaManager.getTable(testTableName);
        assertNotNull(testTable, "Test table should exist");
        long tableId = testTable.getTableId();
        
        byte[] key = KeyEncoder.encodeTableDataKey(tableId, java.util.Arrays.asList(2), 0);
        
        // Create version with ts < readTimestamp but commit_ts > readTimestamp
        long ts = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long commitTs = timestampOracle.allocateCommitTimestamp();
        
        // Insert version
        kvStore.put(key, "value".getBytes(), ts, UUID.randomUUID(), commitTs, false);
        Thread.sleep(100);
        
        // Read at timestamp before commit_ts
        long readTimestamp = ts + (commitTs - ts) / 2; // Between ts and commit_ts
        
        System.out.println("Version: ts=" + ts + ", commit_ts=" + commitTs);
        System.out.println("readTimestamp=" + readTimestamp);
        System.out.println("ts <= readTimestamp: " + (ts <= readTimestamp));
        System.out.println("commit_ts > readTimestamp: " + (commitTs > readTimestamp));
        
        // get() should return null (commit_ts > readTimestamp, so version is not visible)
        KvStore.KvEntry result = kvStore.get(key, readTimestamp);
        
        assertNull(result, 
            "Should return null because commit_ts=" + commitTs + " > readTimestamp=" + readTimestamp);
        
        // Clean up
        queryService.execute("DROP TABLE IF EXISTS " + testTableName);
        
        System.out.println("✅ PASS: get() correctly excludes versions with commit_ts > readTimestamp");
    }
    
    /**
     * Test that get() returns the latest committed version with commit_ts <= readTimestamp.
     * 
     * Scenario:
     * - Version 1: ts=100, commit_ts=150
     * - Version 2: ts=120, commit_ts=180
     * - Version 3: ts=140, commit_ts=200
     * - readTimestamp=190
     * 
     * Expected: Should return Version 2 (latest with commit_ts <= 190)
     */
    @Test
    @Order(3)
    public void testGetReturnsLatestCommittedVersion() throws Exception {
        System.out.println("\n=== TEST: get() returns latest committed version ===");
        
        String testTableName = uniqueTableName("kvstore_get_latest_test");
        queryService.execute("CREATE TABLE " + testTableName + " (id INT PRIMARY KEY, data TEXT)");
        TableMetadata testTable = schemaManager.getTable(testTableName);
        assertNotNull(testTable, "Test table should exist");
        long tableId = testTable.getTableId();
        
        byte[] key = KeyEncoder.encodeTableDataKey(tableId, java.util.Arrays.asList(3), 0);
        
        // Create multiple versions
        long ts1 = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long commitTs1 = timestampOracle.allocateCommitTimestamp();
        
        long ts2 = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long commitTs2 = timestampOracle.allocateCommitTimestamp();
        
        long ts3 = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long commitTs3 = timestampOracle.allocateCommitTimestamp();
        
        // Insert versions
        kvStore.put(key, "value1".getBytes(), ts1, UUID.randomUUID(), commitTs1, false);
        Thread.sleep(50);
        kvStore.put(key, "value2".getBytes(), ts2, UUID.randomUUID(), commitTs2, false);
        Thread.sleep(50);
        kvStore.put(key, "value3".getBytes(), ts3, UUID.randomUUID(), commitTs3, false);
        Thread.sleep(100);
        
        // Read at timestamp that includes Version 2 but not Version 3
        long readTimestamp = (commitTs2 + commitTs3) / 2;
        
        System.out.println("Version 1: ts=" + ts1 + ", commit_ts=" + commitTs1);
        System.out.println("Version 2: ts=" + ts2 + ", commit_ts=" + commitTs2);
        System.out.println("Version 3: ts=" + ts3 + ", commit_ts=" + commitTs3);
        System.out.println("readTimestamp=" + readTimestamp);
        
        // get() should return Version 2 (latest with commit_ts <= readTimestamp)
        KvStore.KvEntry result = kvStore.get(key, readTimestamp);
        
        assertNotNull(result, "Should return a version");
        assertEquals("value2", new String(result.getValue()), 
            "Should return Version 2 (latest with commit_ts <= readTimestamp)");
        assertEquals(commitTs2, result.getCommitTs().longValue(), 
            "Should return Version 2 with commit_ts=" + commitTs2);
        
        // Clean up
        queryService.execute("DROP TABLE IF EXISTS " + testTableName);
        
        System.out.println("✅ PASS: get() returns latest committed version");
    }
    
    /**
     * Test that get() correctly handles uncommitted versions.
     * 
     * Scenario:
     * - Version 1: ts=100, commit_ts=150 (committed)
     * - Version 2: ts=120, commit_ts=NULL (uncommitted)
     * - readTimestamp=200
     * 
     * Expected: Should return Version 1 (uncommitted Version 2 should be skipped)
     */
    @Test
    @Order(4)
    public void testGetSkipsUncommittedVersions() throws Exception {
        System.out.println("\n=== TEST: get() skips uncommitted versions ===");
        
        String testTableName = uniqueTableName("kvstore_get_uncommitted_test");
        queryService.execute("CREATE TABLE " + testTableName + " (id INT PRIMARY KEY, data TEXT)");
        TableMetadata testTable = schemaManager.getTable(testTableName);
        assertNotNull(testTable, "Test table should exist");
        long tableId = testTable.getTableId();
        
        byte[] key = KeyEncoder.encodeTableDataKey(tableId, java.util.Arrays.asList(4), 0);
        
        // Create committed version
        long ts1 = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long commitTs1 = timestampOracle.allocateCommitTimestamp();
        
        // Create uncommitted version (commit_ts=NULL)
        long ts2 = timestampOracle.allocateStartTimestamp();
        
        // Insert versions
        kvStore.put(key, "value1".getBytes(), ts1, UUID.randomUUID(), commitTs1, false);
        Thread.sleep(50);
        kvStore.put(key, "value2".getBytes(), ts2, UUID.randomUUID(), null, false); // Uncommitted
        Thread.sleep(100);
        
        // Read at timestamp that includes both
        long readTimestamp = commitTs1 + 100;
        
        System.out.println("Version 1: ts=" + ts1 + ", commit_ts=" + commitTs1 + " (committed)");
        System.out.println("Version 2: ts=" + ts2 + ", commit_ts=NULL (uncommitted)");
        System.out.println("readTimestamp=" + readTimestamp);
        
        // get() should return Version 1 (uncommitted Version 2 should be skipped)
        KvStore.KvEntry result = kvStore.get(key, readTimestamp);
        
        assertNotNull(result, "Should return a version");
        assertEquals("value1", new String(result.getValue()), 
            "Should return Version 1 (uncommitted Version 2 should be skipped)");
        assertEquals(commitTs1, result.getCommitTs().longValue(), 
            "Should return Version 1 with commit_ts=" + commitTs1);
        
        // Clean up
        queryService.execute("DROP TABLE IF EXISTS " + testTableName);
        
        System.out.println("✅ PASS: get() correctly skips uncommitted versions");
    }
}

