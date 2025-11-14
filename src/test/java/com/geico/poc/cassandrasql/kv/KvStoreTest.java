package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.kv.jobs.VacuumJob;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KvStore, specifically testing version storage and retrieval.
 * 
 * Critical tests:
 * - getAllVersions() returns all versions for a key
 * - Multiple versions with same key but different timestamps are stored correctly
 * - Versions are retrievable immediately after insertion
 * 
 * NOTE: Vacuum is disabled in test profile to prevent interference with version tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "cassandra-sql.kv.vacuum.enabled=false"  // Explicitly disable vacuum for these tests
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KvStoreTest extends KvTestBase {
    
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
        // Ensure KV store tables exist
        kvStore.initialize();
        
        // Verify vacuum is disabled for these tests
        if (vacuumJob != null && vacuumJob.isEnabled()) {
            System.out.println("⚠️  WARNING: Vacuum is enabled - this may interfere with version tests");
        } else {
            System.out.println("✅ Vacuum is disabled - safe for version tests");
        }
    }
    
    @Test
    @Order(1)
    public void testGetAllVersionsReturnsAllVersions() throws Exception {
        System.out.println("\n=== TEST: getAllVersions returns all versions ===");
        
        // Create a test table to get a valid table ID
        String testTableName = uniqueTableName("kvstore_test");
        queryService.execute("CREATE TABLE " + testTableName + " (id INT PRIMARY KEY, data TEXT)");
        TableMetadata testTable = schemaManager.getTable(testTableName);
        assertNotNull(testTable, "Test table should exist");
        long tableId = testTable.getTableId();
        
        // Create a key that belongs to this table
        byte[] key = KeyEncoder.encodeTableDataKey(tableId, java.util.Arrays.asList(123), 0);
        
        // Insert 3 versions with different timestamps
        // Use allocateStartTimestamp() to guarantee unique timestamps
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        UUID txId3 = UUID.randomUUID();
        
        long ts1 = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long ts2 = timestampOracle.allocateStartTimestamp();
        Thread.sleep(10);
        long ts3 = timestampOracle.allocateStartTimestamp();
        
        // Insert versions with different timestamps
        System.out.println("Inserting version 1: ts=" + ts1);
        kvStore.put(key, "value1".getBytes(), ts1, txId1, ts1, false);
        Thread.sleep(100); // Ensure first version is persisted
        
        // Verify first version was stored
        List<KvStore.KvEntry> afterFirst = kvStore.getAllVersions(key);
        System.out.println("After first insert: " + afterFirst.size() + " versions");
        assertEquals(1, afterFirst.size(), "Should have 1 version after first insert");
        
        System.out.println("Inserting version 2: ts=" + ts2 + " (diff from ts1: " + (ts2 - ts1) + ")");
        kvStore.put(key, "value2".getBytes(), ts2, txId2, ts2, false);
        Thread.sleep(100); // Ensure second version is persisted
        
        // Verify both versions are stored
        List<KvStore.KvEntry> afterSecond = kvStore.getAllVersions(key);
        System.out.println("After second insert: " + afterSecond.size() + " versions");
        for (int i = 0; i < afterSecond.size(); i++) {
            KvStore.KvEntry v = afterSecond.get(i);
            System.out.println("  Version " + i + ": ts=" + v.getTimestamp() + 
                ", commitTs=" + v.getCommitTs() + 
                ", value=" + (v.getValue() != null ? new String(v.getValue()) : "null"));
        }
        
        // Check if timestamps are actually different
        if (afterSecond.size() == 1) {
            KvStore.KvEntry onlyVersion = afterSecond.get(0);
            System.out.println("ERROR: Only 1 version found. Its ts=" + onlyVersion.getTimestamp() + 
                ", expected ts1=" + ts1 + " or ts2=" + ts2);
            if (onlyVersion.getTimestamp() == ts1) {
                System.out.println("  -> Second insert (ts2) did not create a new row!");
            } else if (onlyVersion.getTimestamp() == ts2) {
                System.out.println("  -> Second insert (ts2) overwrote first insert (ts1)!");
            } else {
                System.out.println("  -> Unexpected timestamp!");
            }
        }
        
        assertEquals(2, afterSecond.size(), 
            "Should have 2 versions after second insert. Got: " + afterSecond.size() + 
            ". This indicates INSERT is overwriting instead of creating new rows.");
        
        System.out.println("Inserting version 3: ts=" + ts3);
        kvStore.put(key, "value3".getBytes(), ts3, txId3, ts3, false);
        Thread.sleep(200); // Ensure all versions are persisted
        
        // Verify getAllVersions returns all 3 versions
        List<KvStore.KvEntry> allVersions = kvStore.getAllVersions(key);
        
        System.out.println("Retrieved " + allVersions.size() + " versions");
        for (int i = 0; i < allVersions.size(); i++) {
            KvStore.KvEntry v = allVersions.get(i);
            System.out.println("  Version " + i + ": ts=" + v.getTimestamp() + 
                ", commitTs=" + v.getCommitTs() + 
                ", value=" + (v.getValue() != null ? new String(v.getValue()) : "null"));
        }
        
        assertEquals(3, allVersions.size(), 
            "getAllVersions should return all 3 versions. Got: " + allVersions.size() + 
            ". This indicates a bug in version storage or retrieval.");
        
        // Verify all versions have correct values
        boolean foundValue1 = false, foundValue2 = false, foundValue3 = false;
        for (KvStore.KvEntry entry : allVersions) {
            if (entry.getValue() != null) {
                String value = new String(entry.getValue());
                if (value.equals("value1")) foundValue1 = true;
                if (value.equals("value2")) foundValue2 = true;
                if (value.equals("value3")) foundValue3 = true;
            }
        }
        
        assertTrue(foundValue1, "Should find value1");
        assertTrue(foundValue2, "Should find value2");
        assertTrue(foundValue3, "Should find value3");
        
        // Clean up
        queryService.execute("DROP TABLE IF EXISTS " + testTableName);
        
        System.out.println("✅ PASS: getAllVersions returns all versions");
    }
    
    @Test
    @Order(2)
    public void testGetAllVersionsWithOldTimestamps() throws Exception {
        System.out.println("\n=== TEST: getAllVersions with old timestamps ===");
        
        // Create a test table
        String testTableName = uniqueTableName("kvstore_old_ts_test");
        queryService.execute("CREATE TABLE " + testTableName + " (id INT PRIMARY KEY, data TEXT)");
        TableMetadata testTable = schemaManager.getTable(testTableName);
        assertNotNull(testTable, "Test table should exist");
        long tableId = testTable.getTableId();
        
        // Create a key
        byte[] key = KeyEncoder.encodeTableDataKey(tableId, java.util.Arrays.asList(456), 0);
        
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        UUID txId3 = UUID.randomUUID();
        
        // Create timestamps: old, older, newest
        // Use allocateStartTimestamp() to guarantee unique timestamps
        long baseTs = timestampOracle.allocateStartTimestamp();
        long oldTs1 = baseTs - (2L * 3600L * 1000L * 1000L); // 2 hours ago
        long oldTs2 = baseTs - (3L * 3600L * 1000L * 1000L); // 3 hours ago
        Thread.sleep(10);
        long newTs = timestampOracle.allocateStartTimestamp(); // Now
        
        // Insert versions with old timestamps
        kvStore.put(key, "old1".getBytes(), oldTs1, txId1, oldTs1, false);
        Thread.sleep(50);
        kvStore.put(key, "old2".getBytes(), oldTs2, txId2, oldTs2, false);
        Thread.sleep(50);
        kvStore.put(key, "new".getBytes(), newTs, txId3, newTs, false);
        Thread.sleep(100);
        
        // Verify getAllVersions returns all 3 versions
        List<KvStore.KvEntry> allVersions = kvStore.getAllVersions(key);
        
        System.out.println("Retrieved " + allVersions.size() + " versions with old timestamps");
        for (int i = 0; i < allVersions.size(); i++) {
            KvStore.KvEntry v = allVersions.get(i);
            System.out.println("  Version " + i + ": ts=" + v.getTimestamp() + 
                ", commitTs=" + v.getCommitTs() + 
                ", value=" + (v.getValue() != null ? new String(v.getValue()) : "null"));
        }
        
        assertEquals(3, allVersions.size(), 
            "getAllVersions should return all 3 versions even with old timestamps. Got: " + allVersions.size());
        
        // Clean up
        queryService.execute("DROP TABLE IF EXISTS " + testTableName);
        
        System.out.println("✅ PASS: getAllVersions works with old timestamps");
    }
    
    @Test
    @Order(3)
    public void testGetAllVersionsWithSameKeyDifferentTimestamps() throws Exception {
        System.out.println("\n=== TEST: getAllVersions with same key, different timestamps ===");
        
        // Create a test table
        String testTableName = uniqueTableName("kvstore_same_key_test");
        queryService.execute("CREATE TABLE " + testTableName + " (id INT PRIMARY KEY, data TEXT)");
        TableMetadata testTable = schemaManager.getTable(testTableName);
        assertNotNull(testTable, "Test table should exist");
        long tableId = testTable.getTableId();
        
        // Use the same key for all versions
        byte[] key = KeyEncoder.encodeTableDataKey(tableId, java.util.Arrays.asList(789), 0);
        
        // Insert 5 versions with incrementally different timestamps
        // Use allocateStartTimestamp() to guarantee unique timestamps
        for (int i = 1; i <= 5; i++) {
            UUID txId = UUID.randomUUID();
            long ts = timestampOracle.allocateStartTimestamp();
            kvStore.put(key, ("version" + i).getBytes(), ts, txId, ts, false);
            Thread.sleep(20); // Small delay between inserts
        }
        Thread.sleep(100); // Ensure all are persisted
        
        // Verify getAllVersions returns all 5 versions
        List<KvStore.KvEntry> allVersions = kvStore.getAllVersions(key);
        
        System.out.println("Retrieved " + allVersions.size() + " versions for same key");
        
        assertEquals(5, allVersions.size(), 
            "getAllVersions should return all 5 versions. Got: " + allVersions.size());
        
        // Verify we can find all versions
        boolean[] found = new boolean[6]; // index 0 unused, 1-5 for versions
        for (KvStore.KvEntry entry : allVersions) {
            if (entry.getValue() != null) {
                String value = new String(entry.getValue());
                for (int i = 1; i <= 5; i++) {
                    if (value.equals("version" + i)) {
                        found[i] = true;
                    }
                }
            }
        }
        
        for (int i = 1; i <= 5; i++) {
            assertTrue(found[i], "Should find version" + i);
        }
        
        // Clean up
        queryService.execute("DROP TABLE IF EXISTS " + testTableName);
        
        System.out.println("✅ PASS: getAllVersions returns all versions for same key");
    }
}

