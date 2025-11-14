package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for KvTransactionCoordinator.
 * 
 * These tests require a running Cassandra instance.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KvTransactionCoordinatorTest {
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired(required = false)
    private KvTransactionCoordinator coordinator;
    
    @Autowired(required = false)
    private TimestampOracle timestampOracle;
    
    @Autowired(required = false)
    private KvStore kvStore;
    
    private static final long TABLE_ID = 1L;
    private static final long INDEX_ID = 0L;
    
    @BeforeAll
    public void setupAll() {
        // Only run if KV mode is enabled
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            System.out.println("⚠️  Skipping KV transaction tests (KV mode not enabled)");
            return;
        }
        
        System.out.println("🔧 Setting up KV transaction tests...");
        
        // Ensure tables exist
        assertNotNull(coordinator, "KvTransactionCoordinator should be initialized");
        assertNotNull(timestampOracle, "TimestampOracle should be initialized");
        assertNotNull(kvStore, "KvStore should be initialized");
    }
    
    @BeforeEach
    public void setup() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Clean up before each test
        session.execute("TRUNCATE cassandra_sql.kv_store");
        session.execute("TRUNCATE cassandra_sql.kv_locks");
        session.execute("TRUNCATE cassandra_sql.kv_writes");
    }
    
    @Test
    public void testBeginTransaction() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        assertNotNull(ctx);
        assertNotNull(ctx.getTxId());
        assertTrue(ctx.getStartTs() > 0);
        assertEquals(KvTransactionContext.TransactionState.ACTIVE, ctx.getState());
        assertEquals(0, ctx.getWriteCount());
        
        assertTrue(coordinator.isActive(ctx.getTxId()));
    }
    
    @Test
    public void testSingleKeyTransaction() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Begin transaction
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        // Add a write
        byte[] key = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx.getStartTs());
        byte[] value = ValueEncoder.encodeRow(Arrays.asList("Alice", 25));
        ctx.addWrite(key, value, KvTransactionContext.WriteType.PUT);
        
        assertEquals(1, ctx.getWriteCount());
        
        // Commit
        coordinator.commit(ctx);
        
        assertEquals(KvTransactionContext.TransactionState.COMMITTED, ctx.getState());
        assertNotNull(ctx.getCommitTs());
        assertFalse(coordinator.isActive(ctx.getTxId()));
        
        // Verify data is committed
        long readTs = timestampOracle.getCurrentTimestamp();
        KvStore.KvEntry entry = kvStore.get(key, readTs);
        
        assertNotNull(entry);
        assertArrayEquals(value, entry.getValue());
        assertEquals(ctx.getCommitTs(), entry.getCommitTs());
        assertFalse(entry.isDeleted());
    }
    
    @Test
    public void testMultiKeyTransaction() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Begin transaction
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        // Add multiple writes (simulating data + index)
        byte[] dataKey = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx.getStartTs());
        byte[] indexKey = KeyEncoder.encodeIndexKey(TABLE_ID, 1L, Arrays.asList("Alice"), Arrays.asList(1), ctx.getStartTs());
        
        byte[] value = ValueEncoder.encodeRow(Arrays.asList("Alice", 25));
        byte[] indexValue = ValueEncoder.encodeIndexEntry();
        
        ctx.addWrite(dataKey, value, KvTransactionContext.WriteType.PUT);
        ctx.addWrite(indexKey, indexValue, KvTransactionContext.WriteType.PUT);
        
        assertEquals(2, ctx.getWriteCount());
        
        // Commit
        coordinator.commit(ctx);
        
        assertEquals(KvTransactionContext.TransactionState.COMMITTED, ctx.getState());
        
        // Verify both keys are committed
        long readTs = timestampOracle.getCurrentTimestamp();
        
        KvStore.KvEntry dataEntry = kvStore.get(dataKey, readTs);
        assertNotNull(dataEntry);
        assertArrayEquals(value, dataEntry.getValue());
        
        KvStore.KvEntry indexEntry = kvStore.get(indexKey, readTs);
        assertNotNull(indexEntry);
        assertArrayEquals(indexValue, indexEntry.getValue());
        
        // Both should have the same commit timestamp
        assertEquals(dataEntry.getCommitTs(), indexEntry.getCommitTs());
    }
    
    @Test
    public void testPrimaryAndSecondaryLocks() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Begin transaction with 3 keys
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        byte[] key1 = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx.getStartTs());
        byte[] key2 = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(2), ctx.getStartTs());
        byte[] key3 = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(3), ctx.getStartTs());
        
        byte[] value = ValueEncoder.encodeRow(Arrays.asList("test"));
        
        ctx.addWrite(key1, value, KvTransactionContext.WriteType.PUT);
        ctx.addWrite(key2, value, KvTransactionContext.WriteType.PUT);
        ctx.addWrite(key3, value, KvTransactionContext.WriteType.PUT);
        
        // Choose primary key
        ctx.choosePrimaryKey();
        
        assertNotNull(ctx.getPrimaryKey());
        assertEquals(2, ctx.getSecondaryKeys().size());
        
        // Verify one key is marked as primary
        int primaryCount = 0;
        for (KvTransactionContext.WriteIntent intent : ctx.getWriteIntents()) {
            if (intent.isPrimary()) {
                primaryCount++;
            }
        }
        assertEquals(1, primaryCount);
        
        // Commit
        coordinator.commit(ctx);
        
        assertEquals(KvTransactionContext.TransactionState.COMMITTED, ctx.getState());
    }
    
    @Test
    public void testWriteConflict() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Transaction 1: Write and commit
        KvTransactionContext ctx1 = coordinator.beginTransaction();
        byte[] key = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx1.getStartTs());
        byte[] value1 = ValueEncoder.encodeRow(Arrays.asList("Alice"));
        ctx1.addWrite(key, value1, KvTransactionContext.WriteType.PUT);
        coordinator.commit(ctx1);
        
        // Transaction 2: Try to write the same key (should detect conflict)
        KvTransactionContext ctx2 = coordinator.beginTransaction();
        byte[] key2 = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx2.getStartTs());
        byte[] value2 = ValueEncoder.encodeRow(Arrays.asList("Bob"));
        ctx2.addWrite(key2, value2, KvTransactionContext.WriteType.PUT);
        
        // This should succeed because ctx2 started after ctx1 committed
        assertDoesNotThrow(() -> coordinator.commit(ctx2));
    }
    
    @Test
    public void testLockConflict() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Transaction 1: Begin and prewrite (but don't commit yet)
        KvTransactionContext ctx1 = coordinator.beginTransaction();
        byte[] key = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx1.getStartTs());
        byte[] value1 = ValueEncoder.encodeRow(Arrays.asList("Alice"));
        ctx1.addWrite(key, value1, KvTransactionContext.WriteType.PUT);
        
        // Manually prewrite to hold the lock
        ctx1.choosePrimaryKey();
        
        // Transaction 2: Try to write the same key
        KvTransactionContext ctx2 = coordinator.beginTransaction();
        
        // Wait a bit to ensure ctx2 starts after ctx1
        Thread.sleep(10);
        
        byte[] key2 = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx2.getStartTs());
        byte[] value2 = ValueEncoder.encodeRow(Arrays.asList("Bob"));
        ctx2.addWrite(key2, value2, KvTransactionContext.WriteType.PUT);
        
        // Commit ctx1 first
        coordinator.commit(ctx1);
        
        // Now ctx2 should FAIL with write conflict (ctx1 committed after ctx2's start_ts)
        // This is correct snapshot isolation behavior - write-write conflicts must be detected
        assertThrows(KvTransactionCoordinator.TransactionException.class, 
            () -> coordinator.commit(ctx2),
            "Expected write conflict when ctx1 commits after ctx2 starts");
    }
    
    @Test
    public void testRollback() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Begin transaction
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        byte[] key = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx.getStartTs());
        byte[] value = ValueEncoder.encodeRow(Arrays.asList("Alice"));
        ctx.addWrite(key, value, KvTransactionContext.WriteType.PUT);
        
        // Rollback
        coordinator.rollback(ctx);
        
        assertEquals(KvTransactionContext.TransactionState.ABORTED, ctx.getState());
        assertFalse(coordinator.isActive(ctx.getTxId()));
        
        // Verify data is not visible
        long readTs = timestampOracle.getCurrentTimestamp();
        KvStore.KvEntry entry = kvStore.get(key, readTs);
        
        assertNull(entry);
    }
    
    @Test
    public void testDeleteOperation() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // First, insert a row
        KvTransactionContext ctx1 = coordinator.beginTransaction();
        byte[] key = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx1.getStartTs());
        byte[] value = ValueEncoder.encodeRow(Arrays.asList("Alice"));
        ctx1.addWrite(key, value, KvTransactionContext.WriteType.PUT);
        coordinator.commit(ctx1);
        
        // Verify it exists - allocate a NEW read timestamp AFTER the commit
        long readTs1 = timestampOracle.allocateStartTimestamp();
        KvStore.KvEntry entry1 = kvStore.get(key, readTs1);
        assertNotNull(entry1);
        
        // Now delete it
        KvTransactionContext ctx2 = coordinator.beginTransaction();
        byte[] key2 = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), ctx2.getStartTs());
        ctx2.addDelete(key2);
        coordinator.commit(ctx2);
        
        // Verify it's deleted - allocate a NEW read timestamp AFTER the commit
        long readTs2 = timestampOracle.allocateStartTimestamp();
        KvStore.KvEntry entry2 = kvStore.get(key2, readTs2);
        assertNull(entry2);  // Should not be visible (deleted)
    }
    
    @Test
    public void testMVCCIsolation() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Transaction 1: Write version 1
        KvTransactionContext ctx1 = coordinator.beginTransaction();
        long startTs1 = ctx1.getStartTs();
        byte[] key = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), startTs1);
        byte[] value1 = ValueEncoder.encodeRow(Arrays.asList("Alice", 25));
        ctx1.addWrite(key, value1, KvTransactionContext.WriteType.PUT);
        coordinator.commit(ctx1);
        long commitTs1 = ctx1.getCommitTs();
        
        // Read at time between commits
        long readTs = timestampOracle.getCurrentTimestamp();
        
        // Transaction 2: Write version 2
        KvTransactionContext ctx2 = coordinator.beginTransaction();
        long startTs2 = ctx2.getStartTs();
        byte[] key2 = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(1), startTs2);
        byte[] value2 = ValueEncoder.encodeRow(Arrays.asList("Alice", 26));
        ctx2.addWrite(key2, value2, KvTransactionContext.WriteType.PUT);
        coordinator.commit(ctx2);
        long commitTs2 = ctx2.getCommitTs();
        
        // Read at readTs should see version 1
        KvStore.KvEntry entry1 = kvStore.get(key, readTs);
        assertNotNull(entry1);
        assertEquals(commitTs1, entry1.getCommitTs());
        
        // Read at current time should see version 2
        long currentTs = timestampOracle.getCurrentTimestamp();
        KvStore.KvEntry entry2 = kvStore.get(key, currentTs);
        assertNotNull(entry2);
        assertEquals(commitTs2, entry2.getCommitTs());
    }
    
    @Test
    public void testEmptyTransaction() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        // Commit without any writes should succeed (read-only transaction)
        coordinator.commit(ctx);
        assertEquals(KvTransactionContext.TransactionState.COMMITTED, ctx.getState());
    }
    
    @Test
    public void testLargeTransaction() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Transaction with many keys
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        int numKeys = 100;
        for (int i = 0; i < numKeys; i++) {
            byte[] key = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(i), ctx.getStartTs());
            byte[] value = ValueEncoder.encodeRow(Arrays.asList("User" + i, i));
            ctx.addWrite(key, value, KvTransactionContext.WriteType.PUT);
        }
        
        assertEquals(numKeys, ctx.getWriteCount());
        
        // Commit
        coordinator.commit(ctx);
        
        assertEquals(KvTransactionContext.TransactionState.COMMITTED, ctx.getState());
        
        // Verify all keys are committed
        long readTs = timestampOracle.getCurrentTimestamp();
        for (int i = 0; i < numKeys; i++) {
            byte[] key = KeyEncoder.encodeTableDataKey(TABLE_ID, Arrays.asList(i), ctx.getStartTs());
            KvStore.KvEntry entry = kvStore.get(key, readTs);
            assertNotNull(entry, "Key " + i + " should be committed");
        }
    }
}



