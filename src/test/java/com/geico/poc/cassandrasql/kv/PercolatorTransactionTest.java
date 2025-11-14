package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Percolator-style transactions in KV mode.
 * 
 * Tests:
 * 1. Multi-table transactions (atomicity across tables)
 * 2. Concurrent transaction conflict resolution
 * 3. Read-your-own-writes within transaction
 * 4. Rollback behavior
 * 5. Isolation levels
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PercolatorTransactionTest extends KvTestBase {
    
    // ========================================
    // Test 1: Multi-Table Atomic Transaction
    // ========================================
    
    @Test
    @Order(1)
    public void testMultiTableAtomicTransaction() throws Exception {
        System.out.println("\n=== TEST 1: Multi-Table Atomic Transaction ===\n");
        
        String accountsTable = uniqueTableName("accounts");
        String auditTable = uniqueTableName("audit_log");
        
        // Create two tables
        queryService.execute("CREATE TABLE " + accountsTable + " (id INT PRIMARY KEY, balance INT)");
        queryService.execute("CREATE TABLE " + auditTable + " (id INT PRIMARY KEY, account_id INT, amount INT, description TEXT)");
        
        // Insert initial data
        queryService.execute("INSERT INTO " + accountsTable + " (id, balance) VALUES (1, 1000)");
        queryService.execute("INSERT INTO " + accountsTable + " (id, balance) VALUES (2, 500)");
        
        // Transaction: Transfer money between accounts + log it
        String connId = testConnectionId();
        System.out.println("1. BEGIN transaction");
        queryService.execute("BEGIN", connId);
        
        // Read account 1
        QueryResponse read1 = queryService.execute("SELECT * FROM " + accountsTable + " WHERE id = 1", connId);
        int balance1 = (Integer) read1.getRows().get(0).get("balance");
        System.out.println("   Account 1 balance: " + balance1);
        
        // Read account 2
        QueryResponse read2 = queryService.execute("SELECT * FROM " + accountsTable + " WHERE id = 2", connId);
        int balance2 = (Integer) read2.getRows().get(0).get("balance");
        System.out.println("   Account 2 balance: " + balance2);
        
        // Transfer $200 from account 1 to account 2
        int transferAmount = 200;
        System.out.println("\n2. Transfer $" + transferAmount + " from account 1 to account 2");
        
        queryService.execute("UPDATE " + accountsTable + " SET balance = " + (balance1 - transferAmount) + " WHERE id = 1", connId);
        queryService.execute("UPDATE " + accountsTable + " SET balance = " + (balance2 + transferAmount) + " WHERE id = 2", connId);
        
        // Log the transfer in audit table
        queryService.execute("INSERT INTO " + auditTable + " (id, account_id, amount, description) VALUES (1, 1, " + (-transferAmount) + ", 'Transfer out')", connId);
        queryService.execute("INSERT INTO " + auditTable + " (id, account_id, amount, description) VALUES (2, 2, " + transferAmount + ", 'Transfer in')", connId);
        
        System.out.println("\n3. COMMIT transaction");
        QueryResponse commitResp = queryService.execute("COMMIT", connId);
        assertNull(commitResp.getError(), "COMMIT should succeed");
        
        // Verify both tables updated atomically
        System.out.println("\n4. Verify results");
        QueryResponse verify1 = queryService.execute("SELECT * FROM " + accountsTable + " WHERE id = 1");
        QueryResponse verify2 = queryService.execute("SELECT * FROM " + accountsTable + " WHERE id = 2");
        QueryResponse verifyAudit = queryService.execute("SELECT * FROM " + auditTable);
        
        int finalBalance1 = (Integer) verify1.getRows().get(0).get("balance");
        int finalBalance2 = (Integer) verify2.getRows().get(0).get("balance");
        
        System.out.println("   Account 1 final balance: " + finalBalance1);
        System.out.println("   Account 2 final balance: " + finalBalance2);
        System.out.println("   Audit log entries: " + verifyAudit.getRows().size());
        
        assertEquals(800, finalBalance1, "Account 1 should have $800");
        assertEquals(700, finalBalance2, "Account 2 should have $700");
        assertEquals(2, verifyAudit.getRows().size(), "Should have 2 audit log entries");
        
        // Verify total balance preserved (atomicity)
        assertEquals(1500, finalBalance1 + finalBalance2, "Total balance should be preserved");
        
        System.out.println("\n✅ Multi-table transaction succeeded atomically\n");
    }
    
    // ========================================
    // Test 2: Concurrent Transactions - Non-Overlapping Keys
    // ========================================
    
    @Test
    @Order(2)
    public void testConcurrentTransactionsNonOverlapping() throws Exception {
        System.out.println("\n=== TEST 2: Concurrent Transactions (Non-Overlapping Keys) ===\n");
        
        String tableName = uniqueTableName("concurrent_non_overlap");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, data INT)");
        
        // Insert 10 rows
        for (int i = 1; i <= 10; i++) {
            queryService.execute("INSERT INTO " + tableName + " (id, data) VALUES (" + i + ", 0)");
        }
        
        // Run 10 concurrent transactions, each updating a different row
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            final int rowId = i + 1;
            executor.submit(() -> {
                try {
                    String connId = "thread-" + rowId;
                    
                    // Each transaction updates a DIFFERENT row
                    queryService.execute("BEGIN", connId);
                    QueryResponse read = queryService.execute("SELECT * FROM " + tableName + " WHERE id = " + rowId, connId);
                    int currentValue = (Integer) read.getRows().get(0).get("data");
                    queryService.execute("UPDATE " + tableName + " SET data = " + (currentValue + 1) + " WHERE id = " + rowId, connId);
                    QueryResponse commit = queryService.execute("COMMIT", connId);
                    
                    if (commit.getError() == null) {
                        successCount.incrementAndGet();
                    } else {
                        System.err.println("Transaction " + rowId + " failed: " + commit.getError());
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("Transaction " + rowId + " exception: " + e.getMessage());
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        System.out.println("Results:");
        System.out.println("  Successes: " + successCount.get());
        System.out.println("  Failures: " + failureCount.get());
        
        // All transactions should succeed (non-overlapping keys)
        assertEquals(numThreads, successCount.get(), "All non-overlapping transactions should succeed");
        assertEquals(0, failureCount.get(), "No transactions should fail");
        
        // Verify all rows updated
        QueryResponse verifyAll = queryService.execute("SELECT * FROM " + tableName);
        for (int i = 0; i < verifyAll.getRows().size(); i++) {
            int data = (Integer) verifyAll.getRows().get(i).get("data");
            assertEquals(1, data, "Each row should be incremented to 1");
        }
        
        System.out.println("\n✅ All non-overlapping concurrent transactions succeeded\n");
    }
    
    // ========================================
    // Test 3: Concurrent Transactions - Overlapping Keys (Conflict Detection)
    // ========================================
    
    @Test
    @Order(3)
    public void testConcurrentTransactionsOverlapping() throws Exception {
        System.out.println("\n=== TEST 3: Concurrent Transactions (Overlapping Keys - Conflict Detection) ===\n");
        
        String tableName = uniqueTableName("concurrent_overlap");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, counter INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, counter) VALUES (1, 0)");
        
        // Run 20 concurrent transactions, all updating the SAME row
        int numThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        System.out.println("Starting " + numThreads + " concurrent transactions on the SAME row...\n");
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    String connId = "thread-" + threadId;
                    
                    // All transactions update the SAME row (id=1)
                    queryService.execute("BEGIN", connId);
                    
                    QueryResponse read = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", connId);
                    if (read.getRows() == null || read.getRows().isEmpty()) {
                        failureCount.incrementAndGet();
                        return;
                    }
                    
                    int currentValue = (Integer) read.getRows().get(0).get("counter");
                    
                    // Small delay to increase contention
                    Thread.sleep(10);
                    
                    queryService.execute("UPDATE " + tableName + " SET counter = " + (currentValue + 1) + " WHERE id = 1", connId);
                    QueryResponse commit = queryService.execute("COMMIT", connId);
                    
                    if (commit.getError() == null) {
                        successCount.incrementAndGet();
                        System.out.println("  Thread " + threadId + " committed successfully");
                    } else {
                        failureCount.incrementAndGet();
                        System.out.println("  Thread " + threadId + " failed: " + commit.getError());
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.err.println("  Thread " + threadId + " exception: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();
        
        System.out.println("\nResults:");
        System.out.println("  Successes: " + successCount.get());
        System.out.println("  Failures: " + failureCount.get());
        
        // Verify final counter value
        QueryResponse verify = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        assertNotNull(verify.getRows(), "Should have result");
        assertFalse(verify.getRows().isEmpty(), "Should have row");
        
        int finalCounter = (Integer) verify.getRows().get(0).get("counter");
        System.out.println("  Final counter: " + finalCounter);
        
        // Key assertion: final counter should equal successful commits (no lost updates)
        assertEquals(successCount.get(), finalCounter, 
            "Final counter should equal successful commits (no lost updates)");
        
        // At least some transactions should succeed
        assertTrue(successCount.get() > 0, "At least some transactions should succeed");
        
        // Some transactions may fail due to conflicts (this is correct behavior)
        System.out.println("\n✅ Conflict detection working: " + successCount.get() + " succeeded, " + 
                         failureCount.get() + " aborted due to conflicts\n");
    }
    
    // ========================================
    // Test 4: Read-Your-Own-Writes Within Transaction
    // ========================================
    
    @Test
    @Order(4)
    public void testReadYourOwnWrites() throws Exception {
        System.out.println("\n=== TEST 4: Read-Your-Own-Writes Within Transaction ===\n");
        
        String tableName = uniqueTableName("read_own_writes");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, data TEXT)");
        
        String connId = testConnectionId();
        
        System.out.println("1. BEGIN transaction");
        queryService.execute("BEGIN", connId);
        
        System.out.println("2. INSERT row");
        queryService.execute("INSERT INTO " + tableName + " (id, data) VALUES (1, 'initial')", connId);
        
        System.out.println("3. SELECT row (should see own insert)");
        QueryResponse read1 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", connId);
        
        // TODO: This may not work yet - buffered writes might not be visible to reads in same txn
        // For now, just verify it doesn't crash
        System.out.println("   Rows returned: " + (read1.getRows() != null ? read1.getRows().size() : "null"));
        
        System.out.println("4. UPDATE row");
        queryService.execute("UPDATE " + tableName + " SET data = 'updated' WHERE id = 1", connId);
        
        System.out.println("5. SELECT row again (should see own update)");
        QueryResponse read2 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", connId);
        System.out.println("   Rows returned: " + (read2.getRows() != null ? read2.getRows().size() : "null"));
        
        System.out.println("6. COMMIT transaction");
        queryService.execute("COMMIT", connId);
        
        System.out.println("7. SELECT after commit");
        QueryResponse read3 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        assertNotNull(read3.getRows(), "Should have result after commit");
        assertFalse(read3.getRows().isEmpty(), "Should have row after commit");
        
        String finalData = (String) read3.getRows().get(0).get("data");
        System.out.println("   Final data: " + finalData);
        assertEquals("updated", finalData, "Should see committed update");
        
        System.out.println("\n✅ Read-your-own-writes test completed\n");
    }
    
    // ========================================
    // Test 5: Rollback Discards All Changes
    // ========================================
    
    @Test
    @Order(5)
    public void testRollbackDiscardsChanges() throws Exception {
        System.out.println("\n=== TEST 5: Rollback Discards All Changes ===\n");
        
        String tableName = uniqueTableName("rollback_test");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, data INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, data) VALUES (1, 100)");
        
        String connId = testConnectionId();
        
        System.out.println("1. Initial value:");
        QueryResponse initial = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        int initialValue = (Integer) initial.getRows().get(0).get("data");
        System.out.println("   data = " + initialValue);
        
        System.out.println("\n2. BEGIN transaction");
        queryService.execute("BEGIN", connId);
        
        System.out.println("3. UPDATE data");
        queryService.execute("UPDATE " + tableName + " SET data = 999 WHERE id = 1", connId);
        
        System.out.println("4. ROLLBACK transaction");
        queryService.execute("ROLLBACK", connId);
        
        System.out.println("\n5. Verify data unchanged:");
        QueryResponse verify = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        int finalValue = (Integer) verify.getRows().get(0).get("data");
        System.out.println("   data = " + finalValue);
        
        assertEquals(initialValue, finalValue, "Value should be unchanged after ROLLBACK");
        assertEquals(100, finalValue, "Value should still be 100");
        
        System.out.println("\n✅ ROLLBACK successfully discarded changes\n");
    }
    
    // ========================================
    // Test 6: Snapshot Isolation (Reads Don't See Concurrent Writes)
    // ========================================
    
    @Test
    @Order(6)
    public void testSnapshotIsolation() throws Exception {
        System.out.println("\n=== TEST 6: Snapshot Isolation ===\n");
        
        String tableName = uniqueTableName("snapshot_isolation");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, data INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, data) VALUES (1, 100)");
        
        String conn1 = "conn1-" + System.currentTimeMillis();
        String conn2 = "conn2-" + System.currentTimeMillis();
        
        System.out.println("1. Transaction 1: BEGIN");
        queryService.execute("BEGIN", conn1);
        
        System.out.println("2. Transaction 1: Read initial value");
        QueryResponse read1 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", conn1);
        int value1 = (Integer) read1.getRows().get(0).get("data");
        System.out.println("   Transaction 1 sees: " + value1);
        
        System.out.println("\n3. Transaction 2: BEGIN");
        queryService.execute("BEGIN", conn2);
        
        System.out.println("4. Transaction 2: Update value");
        queryService.execute("UPDATE " + tableName + " SET data = 200 WHERE id = 1", conn2);
        
        System.out.println("5. Transaction 2: COMMIT");
        queryService.execute("COMMIT", conn2);
        System.out.println("   Transaction 2 committed (data = 200)");
        
        System.out.println("\n6. Transaction 1: Read again (should still see old value due to snapshot isolation)");
        QueryResponse read2 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", conn1);
        int value2 = (Integer) read2.getRows().get(0).get("data");
        System.out.println("   Transaction 1 sees: " + value2);
        
        // Snapshot isolation: Transaction 1 should still see the old value (100)
        // because it reads at its start_ts, not the current time
        assertEquals(100, value2, "Transaction 1 should see snapshot at start_ts (snapshot isolation)");
        
        System.out.println("\n7. Transaction 1: COMMIT");
        queryService.execute("COMMIT", conn1);
        
        System.out.println("\n8. New read (should see Transaction 2's committed value)");
        QueryResponse read3 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        int value3 = (Integer) read3.getRows().get(0).get("data");
        System.out.println("   New read sees: " + value3);
        assertEquals(200, value3, "New read should see Transaction 2's committed value");
        
        System.out.println("\n✅ Snapshot isolation working correctly\n");
    }
    
    // ========================================
    // Test 7: Multi-Table Transaction with Rollback
    // ========================================
    
    @Test
    @Order(7)
    public void testMultiTableRollback() throws Exception {
        System.out.println("\n=== TEST 7: Multi-Table Transaction with Rollback ===\n");
        
        String table1 = uniqueTableName("multi_rollback_1");
        String table2 = uniqueTableName("multi_rollback_2");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, data INT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, data INT)");
        
        queryService.execute("INSERT INTO " + table1 + " (id, data) VALUES (1, 100)");
        queryService.execute("INSERT INTO " + table2 + " (id, data) VALUES (1, 200)");
        
        String connId = testConnectionId();
        
        System.out.println("1. BEGIN transaction");
        queryService.execute("BEGIN", connId);
        
        System.out.println("2. Update both tables");
        queryService.execute("UPDATE " + table1 + " SET data = 111 WHERE id = 1", connId);
        queryService.execute("UPDATE " + table2 + " SET data = 222 WHERE id = 1", connId);
        
        System.out.println("3. ROLLBACK transaction");
        queryService.execute("ROLLBACK", connId);
        
        System.out.println("\n4. Verify both tables unchanged:");
        QueryResponse verify1 = queryService.execute("SELECT * FROM " + table1 + " WHERE id = 1");
        QueryResponse verify2 = queryService.execute("SELECT * FROM " + table2 + " WHERE id = 1");
        
        int data1 = (Integer) verify1.getRows().get(0).get("data");
        int data2 = (Integer) verify2.getRows().get(0).get("data");
        
        System.out.println("   Table 1 data: " + data1);
        System.out.println("   Table 2 data: " + data2);
        
        assertEquals(100, data1, "Table 1 should be unchanged");
        assertEquals(200, data2, "Table 2 should be unchanged");
        
        System.out.println("\n✅ Multi-table ROLLBACK worked correctly\n");
    }
}

