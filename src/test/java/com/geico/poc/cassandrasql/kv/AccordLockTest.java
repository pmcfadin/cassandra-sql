package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test to explore Accord transactions and iterate on lock acquisition mechanism.
 * 
 * This test creates a simple lock table and tests various Accord transaction patterns
 * to understand how to properly implement atomic lock acquisition.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=KV"
})
public class AccordLockTest extends KvTestBase {
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    private String testTable;
    private String keyspace;
    
    @BeforeEach
    public void setup() throws Exception {
        keyspace = keyspaceConfig.getDefaultKeyspace();
        testTable = "accord_lock_test_" + System.nanoTime();
        
        // Create a simple lock table similar to kv_locks
        String createTable = String.format(
            "CREATE TABLE %s.%s (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID," +
            "  created_at TIMESTAMP" +
            ") WITH transactional_mode='full'",
            keyspace, testTable
        );
        
        System.out.println("\n=== Creating test table ===");
        System.out.println("Query: " + createTable);
        session.execute(createTable);
        System.out.println("✓ Table created: " + testTable);
    }
    
    @Test
    public void testBasicAccordSelect() throws Exception {
        System.out.println("\n=== Test 1: Basic Accord SELECT ===");
        
        // Test selecting from empty table
        String query = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET lock = (SELECT tx_id FROM %s.%s WHERE key = 0x010000000000000001 LIMIT 1);\n" +
            "  SELECT lock.tx_id;\n" +
            "COMMIT TRANSACTION",
            keyspace, testTable
        );
        
        System.out.println("Query:\n" + query);
        ResultSet result = session.execute(query);
        
        System.out.println("Result columns: " + result.getColumnDefinitions().size());
        List<Row> rows = new ArrayList<>();
        for (Row row : result) {
            rows.add(row);
            System.out.println("Row: " + row);
            if (!row.isNull(0)) {
                System.out.println("  tx_id: " + row.getUuid(0));
            } else {
                System.out.println("  tx_id: NULL");
            }
        }
        
        assertEquals(1, rows.size(), "Should return one row");
        assertTrue(rows.get(0).isNull(0), "Lock should be NULL (table is empty)");
    }
    
    @Test
    public void testAccordInsertIfNotExists() throws Exception {
        System.out.println("\n=== Test 2: Accord INSERT IF NOT EXISTS pattern ===");
        
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        byte[] key = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
        String keyHex = bytesToHex(key);
        
        // First transaction: insert if not exists
        String query1 = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  IF existing_lock IS NULL THEN\n" +
            "    INSERT INTO %s.%s (key, tx_id, created_at) VALUES (0x%s, %s, toTimestamp(now()));\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, testTable, keyHex,
            keyspace, testTable, keyHex, txId1.toString()
        );
        
        System.out.println("Query 1 (insert):\n" + query1);
        ResultSet result1 = session.execute(query1);
        System.out.println("Result 1 rows: " + result1.all().size());
        
        // Verify lock was inserted
        String verify1 = String.format(
            "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
            keyspace, testTable, keyHex
        );
        ResultSet verifyResult1 = session.execute(verify1);
        Row verifyRow1 = verifyResult1.one();
        assertNotNull(verifyRow1, "Lock should exist");
        assertEquals(txId1, verifyRow1.getUuid("tx_id"), "Lock should have tx_id1");
        System.out.println("✓ Lock inserted with tx_id: " + txId1);
        
        // Second transaction: should NOT insert (lock exists)
        String query2 = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  IF existing_lock IS NULL THEN\n" +
            "    INSERT INTO %s.%s (key, tx_id, created_at) VALUES (0x%s, %s, toTimestamp(now()));\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, testTable, keyHex,
            keyspace, testTable, keyHex, txId2.toString()
        );
        
        System.out.println("\nQuery 2 (should not insert):\n" + query2);
        ResultSet result2 = session.execute(query2);
        System.out.println("Result 2 rows: " + result2.all().size());
        
        // Verify lock still has tx_id1
        ResultSet verifyResult2 = session.execute(verify1);
        Row verifyRow2 = verifyResult2.one();
        assertNotNull(verifyRow2, "Lock should still exist");
        assertEquals(txId1, verifyRow2.getUuid("tx_id"), "Lock should still have tx_id1, not tx_id2");
        System.out.println("✓ Lock still has tx_id1 (tx_id2 was not inserted)");
    }
    
    @Test
    public void testConcurrentLockAcquisition() throws Exception {
        System.out.println("\n=== Test 3: Concurrent Lock Acquisition ===");
        
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<UUID> acquiredTxIds = new CopyOnWriteArrayList<>();
        
        byte[] key = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02};
        String keyHex = bytesToHex(key);
        
        // All threads try to acquire the same lock concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            final UUID txId = UUID.randomUUID();
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    String query = String.format(
                        "BEGIN TRANSACTION\n" +
                        "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
                        "  IF existing_lock IS NULL THEN\n" +
                        "    INSERT INTO %s.%s (key, tx_id, created_at) VALUES (0x%s, %s, toTimestamp(now()));\n" +
                        "  END IF\n" +
                        "COMMIT TRANSACTION",
                        keyspace, testTable, keyHex,
                        keyspace, testTable, keyHex, txId.toString()
                    );
                    
                    System.out.println("Thread " + threadId + " executing query:\n" + query);
                    ResultSet result = session.execute(query);
                    System.out.println("Thread " + threadId + " result rows: " + result.all().size());
                    
                    // Check if lock was acquired
                    String verify = String.format(
                        "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
                        keyspace, testTable, keyHex
                    );
                    ResultSet verifyResult = session.execute(verify);
                    Row verifyRow = verifyResult.one();
                    
                    if (verifyRow != null && verifyRow.getUuid("tx_id").equals(txId)) {
                        successCount.incrementAndGet();
                        acquiredTxIds.add(txId);
                        System.out.println("✓ Thread " + threadId + " acquired lock with tx_id: " + txId);
                    } else {
                        failureCount.incrementAndGet();
                        UUID existingTxId = verifyRow != null ? verifyRow.getUuid("tx_id") : null;
                        System.out.println("✗ Thread " + threadId + " failed to acquire lock. Existing tx_id: " + existingTxId);
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.err.println("Thread " + threadId + " error: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        System.out.println("Starting " + numThreads + " concurrent threads...");
        startLatch.countDown();
        
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        
        System.out.println("\n=== Results ===");
        System.out.println("Successes: " + successCount.get());
        System.out.println("Failures: " + failureCount.get());
        System.out.println("Acquired tx_ids: " + acquiredTxIds);
        
        // Verify final state
        String finalCheck = String.format(
            "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
            keyspace, testTable, keyHex
        );
        ResultSet finalResult = session.execute(finalCheck);
        Row finalRow = finalResult.one();
        
        if (finalRow != null) {
            System.out.println("Final lock tx_id: " + finalRow.getUuid("tx_id"));
        } else {
            System.out.println("Final lock: NULL (no lock acquired)");
        }
        
        // With proper atomicity, exactly ONE should succeed
        assertEquals(1, successCount.get(), 
            "Exactly one transaction should acquire the lock (atomic lock acquisition)");
        assertEquals(numThreads - 1, failureCount.get(),
            "All other transactions should fail to acquire the lock");
        assertNotNull(finalRow, "Lock should exist");
        assertTrue(acquiredTxIds.contains(finalRow.getUuid("tx_id")), 
            "Final lock should be one of the acquired tx_ids");
    }
    
    @Test
    public void testAccordSelectMultipleRows() throws Exception {
        System.out.println("\n=== Test 4: Accord SELECT Multiple Rows ===");
        
        // Insert some test data
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        UUID txId3 = UUID.randomUUID();
        
        byte[] key1 = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03};
        byte[] key2 = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04};
        byte[] key3 = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05};
        
        String key1Hex = bytesToHex(key1);
        String key2Hex = bytesToHex(key2);
        String key3Hex = bytesToHex(key3);
        
        // Insert locks
        session.execute(String.format(
            "INSERT INTO %s.%s (key, tx_id, created_at) VALUES (0x%s, %s, toTimestamp(now()))",
            keyspace, testTable, key1Hex, txId1.toString()
        ));
        session.execute(String.format(
            "INSERT INTO %s.%s (key, tx_id, created_at) VALUES (0x%s, %s, toTimestamp(now()))",
            keyspace, testTable, key2Hex, txId2.toString()
        ));
        session.execute(String.format(
            "INSERT INTO %s.%s (key, tx_id, created_at) VALUES (0x%s, %s, toTimestamp(now()))",
            keyspace, testTable, key3Hex, txId3.toString()
        ));
        
        System.out.println("Inserted 3 locks");
        
        // Select multiple rows in Accord transaction
        String query = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET lock1 = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  LET lock2 = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  LET lock3 = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  SELECT lock1.tx_id, lock2.tx_id, lock3.tx_id;\n" +
            "COMMIT TRANSACTION",
            keyspace, testTable, key1Hex,
            keyspace, testTable, key2Hex,
            keyspace, testTable, key3Hex
        );
        
        System.out.println("Query:\n" + query);
        ResultSet result = session.execute(query);
        
        List<Row> rows = new ArrayList<>();
        for (Row row : result) {
            rows.add(row);
            System.out.println("Row: " + row);
            System.out.println("  lock1.tx_id: " + (row.isNull(0) ? "NULL" : row.getUuid(0)));
            System.out.println("  lock2.tx_id: " + (row.isNull(1) ? "NULL" : row.getUuid(1)));
            System.out.println("  lock3.tx_id: " + (row.isNull(2) ? "NULL" : row.getUuid(2)));
        }
        
        assertEquals(1, rows.size(), "Should return one row");
        Row row = rows.get(0);
        assertEquals(txId1, row.getUuid(0), "lock1 should have txId1");
        assertEquals(txId2, row.getUuid(1), "lock2 should have txId2");
        assertEquals(txId3, row.getUuid(2), "lock3 should have txId3");
        
        System.out.println("✓ Successfully selected multiple rows in Accord transaction");
    }
    
    @Test
    public void testAccordLockExactKvLocksSchema() throws Exception {
        System.out.println("\n=== Test 6: Accord Lock with Exact kv_locks Schema ===");
        
        // Create a table matching kv_locks schema exactly
        String lockTable = "accord_kv_locks_test_" + System.nanoTime();
        
        String createTable = String.format(
            "CREATE TABLE %s.%s (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID," +
            "  start_ts BIGINT," +
            "  primary_key BLOB," +
            "  lock_type TEXT," +
            "  write_type TEXT," +
            "  created_at TIMESTAMP" +
            ") WITH transactional_mode='full'",
            keyspace, lockTable
        );
        
        System.out.println("Creating table: " + createTable);
        session.execute(createTable);
        
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        byte[] key = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07};
        String keyHex = bytesToHex(key);
        String primaryKeyHex = bytesToHex(key);
        long startTs1 = System.currentTimeMillis() * 1000;
        long startTs2 = startTs1 + 1;
        
        // First transaction: insert if not exists
        String query1 = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  IF existing_lock IS NULL THEN\n" +
            "    INSERT INTO %s.%s (key, tx_id, start_ts, primary_key, lock_type, write_type, created_at) " +
            "    VALUES (0x%s, %s, %d, 0x%s, 'primary', 'PUT', toTimestamp(now()));\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, lockTable, keyHex,
            keyspace, lockTable, keyHex, txId1.toString(), startTs1, primaryKeyHex
        );
        
        System.out.println("Query 1:\n" + query1);
        ResultSet result1 = session.execute(query1);
        System.out.println("Result 1 columns: " + result1.getColumnDefinitions().size());
        List<Row> rows1 = new ArrayList<>();
        for (Row row : result1) {
            rows1.add(row);
            System.out.println("Row 1: " + row);
        }
        
        // Verify lock was inserted
        String verify1 = String.format(
            "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
            keyspace, lockTable, keyHex
        );
        ResultSet verifyResult1 = session.execute(verify1);
        Row verifyRow1 = verifyResult1.one();
        assertNotNull(verifyRow1, "Lock should exist");
        assertEquals(txId1, verifyRow1.getUuid("tx_id"), "Lock should have tx_id1");
        System.out.println("✓ Lock inserted with tx_id: " + txId1);
        
        // Second transaction: should NOT insert (lock exists)
        String query2 = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  IF existing_lock IS NULL THEN\n" +
            "    INSERT INTO %s.%s (key, tx_id, start_ts, primary_key, lock_type, write_type, created_at) " +
            "    VALUES (0x%s, %s, %d, 0x%s, 'primary', 'PUT', toTimestamp(now()));\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, lockTable, keyHex,
            keyspace, lockTable, keyHex, txId2.toString(), startTs2, primaryKeyHex
        );
        
        System.out.println("\nQuery 2:\n" + query2);
        ResultSet result2 = session.execute(query2);
        System.out.println("Result 2 columns: " + result2.getColumnDefinitions().size());
        List<Row> rows2 = new ArrayList<>();
        for (Row row : result2) {
            rows2.add(row);
            System.out.println("Row 2: " + row);
        }
        
        // Verify lock still has tx_id1
        ResultSet verifyResult2 = session.execute(verify1);
        Row verifyRow2 = verifyResult2.one();
        assertNotNull(verifyRow2, "Lock should still exist");
        assertEquals(txId1, verifyRow2.getUuid("tx_id"), "Lock should still have tx_id1, not tx_id2");
        System.out.println("✓ Lock still has tx_id1 (tx_id2 was not inserted)");
    }
    
    @Test
    public void testAccordLockWithConflictCheck() throws Exception {
        System.out.println("\n=== Test 7: Accord Lock with Conflict Check ===");
        
        // Create tables matching kv_locks and kv_writes schema
        String lockTable = "accord_kv_locks_test2_" + System.nanoTime();
        String writesTable = "accord_kv_writes_test2_" + System.nanoTime();
        
        String createLockTable = String.format(
            "CREATE TABLE %s.%s (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID," +
            "  start_ts BIGINT" +
            ") WITH transactional_mode='full'",
            keyspace, lockTable
        );
        
        String createWritesTable = String.format(
            "CREATE TABLE %s.%s (" +
            "  key BLOB," +
            "  commit_ts BIGINT," +
            "  PRIMARY KEY (key, commit_ts)" +
            ") WITH CLUSTERING ORDER BY (commit_ts DESC) AND transactional_mode='full'",
            keyspace, writesTable
        );
        
        session.execute(createLockTable);
        session.execute(createWritesTable);
        
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        byte[] key = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08};
        String keyHex = bytesToHex(key);
        long startTs1 = System.currentTimeMillis() * 1000;
        long startTs2 = startTs1 + 1;
        long commitTs1 = startTs1 + 1000;
        
        // Insert a write conflict (commit_ts > startTs2)
        session.execute(String.format(
            "INSERT INTO %s.%s (key, commit_ts) VALUES (0x%s, %d)",
            keyspace, writesTable, keyHex, commitTs1
        ));
        System.out.println("Inserted write conflict: commit_ts=" + commitTs1 + " > startTs2=" + startTs2);
        
        // Try to acquire lock with conflict check
        String query = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  LET conflict = (SELECT commit_ts FROM %s.%s WHERE key = 0x%s AND commit_ts > %d LIMIT 1);\n" +
            "  IF existing_lock IS NULL AND conflict IS NULL THEN\n" +
            "    INSERT INTO %s.%s (key, tx_id, start_ts) VALUES (0x%s, %s, %d);\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, lockTable, keyHex,
            keyspace, writesTable, keyHex, startTs2,
            keyspace, lockTable, keyHex, txId2.toString(), startTs2
        );
        
        System.out.println("Query:\n" + query);
        ResultSet result = session.execute(query);
        System.out.println("Result columns: " + result.getColumnDefinitions().size());
        List<Row> rows = new ArrayList<>();
        for (Row row : result) {
            rows.add(row);
            System.out.println("Row: " + row);
        }
        
        // Verify lock was NOT inserted (conflict prevented it)
        String verify = String.format(
            "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
            keyspace, lockTable, keyHex
        );
        ResultSet verifyResult = session.execute(verify);
        Row verifyRow = verifyResult.one();
        assertNull(verifyRow, "Lock should NOT exist (conflict prevented insertion)");
        System.out.println("✓ Lock was NOT inserted due to conflict");
    }
    
    @Test
    public void testConcurrentLockAcquisitionWithConflictCheck() throws Exception {
        System.out.println("\n=== Test 8: Concurrent Lock Acquisition with Conflict Check ===");
        
        // Create tables matching kv_locks and kv_writes schema exactly
        String lockTable = "accord_kv_locks_test3_" + System.nanoTime();
        String writesTable = "accord_kv_writes_test3_" + System.nanoTime();
        
        String createLockTable = String.format(
            "CREATE TABLE %s.%s (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID," +
            "  start_ts BIGINT," +
            "  primary_key BLOB," +
            "  lock_type TEXT," +
            "  write_type TEXT," +
            "  created_at TIMESTAMP" +
            ") WITH transactional_mode='full'",
            keyspace, lockTable
        );
        
        String createWritesTable = String.format(
            "CREATE TABLE %s.%s (" +
            "  key BLOB," +
            "  commit_ts BIGINT," +
            "  PRIMARY KEY (key, commit_ts)" +
            ") WITH CLUSTERING ORDER BY (commit_ts DESC) AND transactional_mode='full'",
            keyspace, writesTable
        );
        
        session.execute(createLockTable);
        session.execute(createWritesTable);
        
        int numThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<UUID> acquiredTxIds = new CopyOnWriteArrayList<>();
        
        byte[] key = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x09};
        String keyHex = bytesToHex(key);
        String primaryKeyHex = bytesToHex(key);
        long baseStartTs = System.currentTimeMillis() * 1000;
        
        // All threads try to acquire the same lock concurrently with conflict check
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            final UUID txId = UUID.randomUUID();
            final long startTs = baseStartTs + i;
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // Exact pattern from KvTransactionCoordinator
                    String query = String.format(
                        "BEGIN TRANSACTION\n" +
                        "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
                        "  LET conflict = (SELECT commit_ts FROM %s.%s WHERE key = 0x%s AND commit_ts > %d LIMIT 1);\n" +
                        "  IF existing_lock IS NULL AND conflict IS NULL THEN\n" +
                        "    INSERT INTO %s.%s (key, tx_id, start_ts, primary_key, lock_type, write_type, created_at) " +
                        "    VALUES (0x%s, %s, %d, 0x%s, 'primary', 'PUT', toTimestamp(now()));\n" +
                        "  END IF\n" +
                        "COMMIT TRANSACTION",
                        keyspace, lockTable, keyHex,
                        keyspace, writesTable, keyHex, startTs,
                        keyspace, lockTable, keyHex, txId.toString(), startTs, primaryKeyHex
                    );
                    
                    System.out.println("Thread " + threadId + " executing Accord transaction");
                    ResultSet result = session.execute(query);
                    
                    // Check what the Accord transaction returned
                    System.out.println("Thread " + threadId + " result columns: " + result.getColumnDefinitions().size());
                    List<Row> resultRows = new ArrayList<>();
                    for (Row row : result) {
                        resultRows.add(row);
                        System.out.println("Thread " + threadId + " result row: " + row);
                    }
                    
                    // Check if lock was acquired
                    String verify = String.format(
                        "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
                        keyspace, lockTable, keyHex
                    );
                    ResultSet verifyResult = session.execute(verify);
                    Row verifyRow = verifyResult.one();
                    
                    if (verifyRow != null && verifyRow.getUuid("tx_id").equals(txId)) {
                        successCount.incrementAndGet();
                        acquiredTxIds.add(txId);
                        System.out.println("✓ Thread " + threadId + " acquired lock with tx_id: " + txId);
                    } else {
                        failureCount.incrementAndGet();
                        UUID existingTxId = verifyRow != null ? verifyRow.getUuid("tx_id") : null;
                        System.out.println("✗ Thread " + threadId + " failed to acquire lock. Existing tx_id: " + existingTxId);
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.err.println("Thread " + threadId + " error: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        System.out.println("Starting " + numThreads + " concurrent threads...");
        startLatch.countDown();
        
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        
        System.out.println("\n=== Results ===");
        System.out.println("Successes: " + successCount.get());
        System.out.println("Failures: " + failureCount.get());
        System.out.println("Acquired tx_ids: " + acquiredTxIds);
        
        // Verify final state
        String finalCheck = String.format(
            "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
            keyspace, lockTable, keyHex
        );
        ResultSet finalResult = session.execute(finalCheck);
        Row finalRow = finalResult.one();
        
        if (finalRow != null) {
            System.out.println("Final lock tx_id: " + finalRow.getUuid("tx_id"));
        } else {
            System.out.println("Final lock: NULL (no lock acquired)");
        }
        
        // With proper atomicity, exactly ONE should succeed
        assertEquals(1, successCount.get(), 
            "Exactly one transaction should acquire the lock (atomic lock acquisition)");
        assertEquals(numThreads - 1, failureCount.get(),
            "All other transactions should fail to acquire the lock");
        assertNotNull(finalRow, "Lock should exist");
        assertTrue(acquiredTxIds.contains(finalRow.getUuid("tx_id")), 
            "Final lock should be one of the acquired tx_ids");
    }
    
    @Test
    public void testAccordTransactionReturnValue() throws Exception {
        System.out.println("\n=== Test 9: Accord Transaction Return Value ===");
        
        String lockTable = "accord_return_test_" + System.nanoTime();
        
        String createTable = String.format(
            "CREATE TABLE %s.%s (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID" +
            ") WITH transactional_mode='full'",
            keyspace, lockTable
        );
        
        session.execute(createTable);
        
        UUID txId1 = UUID.randomUUID();
        UUID txId2 = UUID.randomUUID();
        byte[] key = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a};
        String keyHex = bytesToHex(key);
        
        // First transaction: insert if not exists (should succeed)
        String query1 = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  IF existing_lock IS NULL THEN\n" +
            "    INSERT INTO %s.%s (key, tx_id) VALUES (0x%s, %s);\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, lockTable, keyHex,
            keyspace, lockTable, keyHex, txId1.toString()
        );
        
        System.out.println("Query 1 (should succeed):\n" + query1);
        ResultSet result1 = session.execute(query1);
        System.out.println("Result 1:");
        System.out.println("  Column count: " + result1.getColumnDefinitions().size());
        List<String> colNames1 = new ArrayList<>();
        for (int i = 0; i < result1.getColumnDefinitions().size(); i++) {
            colNames1.add(result1.getColumnDefinitions().get(i).getName().toString());
        }
        System.out.println("  Column names: " + colNames1);
        List<Row> rows1 = new ArrayList<>();
        for (Row row : result1) {
            rows1.add(row);
            System.out.println("  Row: " + row);
            for (int i = 0; i < row.getColumnDefinitions().size(); i++) {
                System.out.println("    Column " + i + ": " + (row.isNull(i) ? "NULL" : row.getObject(i)));
            }
        }
        System.out.println("  Total rows: " + rows1.size());
        
        // Second transaction: insert if not exists (should fail - lock exists)
        String query2 = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  IF existing_lock IS NULL THEN\n" +
            "    INSERT INTO %s.%s (key, tx_id) VALUES (0x%s, %s);\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, lockTable, keyHex,
            keyspace, lockTable, keyHex, txId2.toString()
        );
        
        System.out.println("\nQuery 2 (should fail - lock exists):\n" + query2);
        ResultSet result2 = session.execute(query2);
        System.out.println("Result 2:");
        System.out.println("  Column count: " + result2.getColumnDefinitions().size());
        List<String> colNames2 = new ArrayList<>();
        for (int i = 0; i < result2.getColumnDefinitions().size(); i++) {
            colNames2.add(result2.getColumnDefinitions().get(i).getName().toString());
        }
        System.out.println("  Column names: " + colNames2);
        List<Row> rows2 = new ArrayList<>();
        for (Row row : result2) {
            rows2.add(row);
            System.out.println("  Row: " + row);
            for (int i = 0; i < row.getColumnDefinitions().size(); i++) {
                System.out.println("    Column " + i + ": " + (row.isNull(i) ? "NULL" : row.getObject(i)));
            }
        }
        System.out.println("  Total rows: " + rows2.size());
        
        // Verify final state
        String verify = String.format(
            "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
            keyspace, lockTable, keyHex
        );
        ResultSet verifyResult = session.execute(verify);
        Row verifyRow = verifyResult.one();
        assertNotNull(verifyRow, "Lock should exist");
        assertEquals(txId1, verifyRow.getUuid("tx_id"), "Lock should have tx_id1, not tx_id2");
        System.out.println("✓ Final lock has tx_id1 (tx_id2 was not inserted)");
    }
    
    @Test
    public void testAccordLockWithReturnValue() throws Exception {
        System.out.println("\n=== Test 5: Accord Lock with Return Value ===");
        
        UUID txId = UUID.randomUUID();
        byte[] key = new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06};
        String keyHex = bytesToHex(key);
        
        // Try to acquire lock and return whether we succeeded
        // Note: Can't use LET after END IF, so we select the lock before the IF
        String query = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing_lock = (SELECT tx_id FROM %s.%s WHERE key = 0x%s LIMIT 1);\n" +
            "  SELECT existing_lock.tx_id;\n" +
            "  IF existing_lock IS NULL THEN\n" +
            "    INSERT INTO %s.%s (key, tx_id, created_at) VALUES (0x%s, %s, toTimestamp(now()));\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, testTable, keyHex,
            keyspace, testTable, keyHex, txId.toString()
        );
        
        System.out.println("Query:\n" + query);
        ResultSet result = session.execute(query);
        
        List<Row> rows = new ArrayList<>();
        for (Row row : result) {
            rows.add(row);
            System.out.println("Row: " + row);
            if (!row.isNull(0)) {
                UUID returnedTxId = row.getUuid(0);
                System.out.println("  Returned tx_id: " + returnedTxId);
                assertEquals(txId, returnedTxId, "Returned tx_id should match inserted tx_id");
            } else {
                System.out.println("  Returned tx_id: NULL");
            }
        }
        
        assertEquals(1, rows.size(), "Should return one row");
        // After insert, existing_lock will still be NULL (it was NULL before insert)
        // So we need to check the lock separately
        String verify = String.format(
            "SELECT tx_id FROM %s.%s WHERE key = 0x%s",
            keyspace, testTable, keyHex
        );
        ResultSet verifyResult = session.execute(verify);
        Row verifyRow = verifyResult.one();
        assertNotNull(verifyRow, "Lock should exist");
        assertEquals(txId, verifyRow.getUuid("tx_id"), "Lock should have our tx_id");
        
        System.out.println("✓ Successfully acquired lock");
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

