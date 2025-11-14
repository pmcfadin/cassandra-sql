package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused test to diagnose concurrent transaction behavior.
 * Tests whether writes are properly staged via Percolator or bypassing to base table.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TransactionCorrectnessTest extends KvTestBase {
    
    @Test
    @Order(1)
    public void testSingleTransactionWriteStaging() throws Exception {
        System.out.println("\n=== TEST: Single Transaction Write Staging ===\n");
        
        String tableName = uniqueTableName("staging_test");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, data INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, data) VALUES (1, 0)");
        
        String connId = testConnectionId();
        
        System.out.println("1. Initial state:");
        QueryResponse initial = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        System.out.println("   data = " + initial.getRows().get(0).get("data"));
        
        System.out.println("\n2. BEGIN transaction");
        queryService.execute("BEGIN", connId);
        
        System.out.println("3. UPDATE in transaction (should be staged, not committed)");
        QueryResponse updateResp = queryService.execute(
            "UPDATE " + tableName + " SET data = 999 WHERE id = 1", 
            connId
        );
        System.out.println("   UPDATE response: " + (updateResp.getError() == null ? "SUCCESS" : "FAILED: " + updateResp.getError()));
        
        System.out.println("\n4. Read from OUTSIDE transaction (should see old value if staging works)");
        QueryResponse outsideRead = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        assertNotNull(outsideRead.getRows(), "Should have rows");
        assertFalse(outsideRead.getRows().isEmpty(), "Should have data");
        int outsideValue = (Integer) outsideRead.getRows().get(0).get("data");
        System.out.println("   Outside transaction sees: " + outsideValue);
        
        if (outsideValue == 999) {
            System.err.println("   ❌ BUG: Write is visible BEFORE commit! Bypassing Percolator staging!");
            fail("Write should NOT be visible before COMMIT - Percolator staging is broken!");
        } else {
            System.out.println("   ✅ CORRECT: Write is NOT visible (properly staged)");
        }
        
        System.out.println("\n5. COMMIT transaction");
        QueryResponse commitResp = queryService.execute("COMMIT", connId);
        System.out.println("   COMMIT response: " + (commitResp.getError() == null ? "SUCCESS" : "FAILED: " + commitResp.getError()));
        
        System.out.println("\n6. Read after commit (should see new value)");
        QueryResponse afterCommit = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        assertNotNull(afterCommit.getRows(), "Should have rows after commit");
        assertFalse(afterCommit.getRows().isEmpty(), "Should have data after commit");
        int finalValue = (Integer) afterCommit.getRows().get(0).get("data");
        System.out.println("   After commit sees: " + finalValue);
        
        assertEquals(999, finalValue, "Should see committed value");
        
        System.out.println("\n✅ Write staging test PASSED\n");
    }
    
    @Test
    @Order(2)
    public void testTwoSequentialTransactions() throws Exception {
        System.out.println("\n=== TEST: Two Sequential Transactions ===\n");
        
        String tableName = uniqueTableName("sequential_test");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, counter INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, counter) VALUES (1, 0)");
        
        // Transaction 1
        String conn1 = "conn1-" + System.nanoTime();
        System.out.println("1. Transaction 1: BEGIN");
        queryService.execute("BEGIN", conn1);
        
        QueryResponse read1 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", conn1);
        int value1 = (Integer) read1.getRows().get(0).get("counter");
        System.out.println("   Read: counter = " + value1);
        
        System.out.println("   UPDATE: counter = " + (value1 + 1));
        queryService.execute("UPDATE " + tableName + " SET counter = " + (value1 + 1) + " WHERE id = 1", conn1);
        
        System.out.println("   COMMIT");
        QueryResponse commit1 = queryService.execute("COMMIT", conn1);
        System.out.println("   Result: " + (commit1.getError() == null ? "SUCCESS" : "FAILED: " + commit1.getError()));
        
        // Verify
        QueryResponse verify1 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        int afterTxn1 = (Integer) verify1.getRows().get(0).get("counter");
        System.out.println("   After txn1: counter = " + afterTxn1);
        assertEquals(1, afterTxn1, "Counter should be 1 after first transaction");
        
        // Transaction 2
        String conn2 = "conn2-" + System.nanoTime();
        System.out.println("\n2. Transaction 2: BEGIN");
        queryService.execute("BEGIN", conn2);
        
        QueryResponse read2 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", conn2);
        int value2 = (Integer) read2.getRows().get(0).get("counter");
        System.out.println("   Read: counter = " + value2);
        
        System.out.println("   UPDATE: counter = " + (value2 + 1));
        queryService.execute("UPDATE " + tableName + " SET counter = " + (value2 + 1) + " WHERE id = 1", conn2);
        
        System.out.println("   COMMIT");
        QueryResponse commit2 = queryService.execute("COMMIT", conn2);
        System.out.println("   Result: " + (commit2.getError() == null ? "SUCCESS" : "FAILED: " + commit2.getError()));
        
        // Verify final
        QueryResponse verify2 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        assertNotNull(verify2.getRows(), "Should have rows");
        assertFalse(verify2.getRows().isEmpty(), "Should have data");
        int afterTxn2 = (Integer) verify2.getRows().get(0).get("counter");
        System.out.println("   After txn2: counter = " + afterTxn2);
        assertEquals(2, afterTxn2, "Counter should be 2 after second transaction");
        
        System.out.println("\n✅ Sequential transactions test PASSED\n");
    }
    
    @Test
    @Order(3)
    public void testConcurrentTransactionsDetailed() throws Exception {
        System.out.println("\n=== TEST: Concurrent Transactions (Detailed Logging) ===\n");
        
        String tableName = uniqueTableName("concurrent_detailed");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, counter INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, counter) VALUES (1, 0)");
        
        System.out.println("Initial state:");
        QueryResponse initial = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        System.out.println("  counter = " + initial.getRows().get(0).get("counter"));
        
        int numThreads = 5;  // Start with just 5 to see what happens
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        ConcurrentHashMap<Integer, String> results = new ConcurrentHashMap<>();
        
        System.out.println("\nStarting " + numThreads + " concurrent transactions...\n");
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    String connId = "thread-" + threadId + "-" + System.nanoTime();
                    
                    System.out.println("  Thread " + threadId + ": BEGIN");
                    queryService.execute("BEGIN", connId);
                    
                    System.out.println("  Thread " + threadId + ": SELECT");
                    QueryResponse read = queryService.execute(
                        "SELECT * FROM " + tableName + " WHERE id = 1", 
                        connId
                    );
                    
                    if (read.getRows() == null || read.getRows().isEmpty()) {
                        System.err.println("  Thread " + threadId + ": ❌ SELECT returned no rows!");
                        results.put(threadId, "SELECT_FAILED");
                        failureCount.incrementAndGet();
                        return;
                    }
                    
                    int currentValue = (Integer) read.getRows().get(0).get("counter");
                    System.out.println("  Thread " + threadId + ": Read counter = " + currentValue);
                    
                    // Small delay to increase contention
                    Thread.sleep(50);
                    
                    System.out.println("  Thread " + threadId + ": UPDATE to " + (currentValue + 1));
                    QueryResponse update = queryService.execute(
                        "UPDATE " + tableName + " SET counter = " + (currentValue + 1) + " WHERE id = 1",
                        connId
                    );
                    
                    if (update.getError() != null) {
                        System.err.println("  Thread " + threadId + ": ❌ UPDATE failed: " + update.getError());
                        results.put(threadId, "UPDATE_FAILED: " + update.getError());
                        failureCount.incrementAndGet();
                        return;
                    }
                    
                    System.out.println("  Thread " + threadId + ": COMMIT");
                    QueryResponse commit = queryService.execute("COMMIT", connId);
                    
                    if (commit.getError() == null) {
                        System.out.println("  Thread " + threadId + ": ✅ COMMITTED");
                        results.put(threadId, "SUCCESS");
                        successCount.incrementAndGet();
                    } else {
                        System.err.println("  Thread " + threadId + ": ❌ COMMIT failed: " + commit.getError());
                        results.put(threadId, "COMMIT_FAILED: " + commit.getError());
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("  Thread " + threadId + ": ❌ Exception: " + e.getMessage());
                    e.printStackTrace();
                    results.put(threadId, "EXCEPTION: " + e.getMessage());
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        System.out.println("\n=== Results ===");
        System.out.println("Completed: " + completed);
        System.out.println("Successes: " + successCount.get());
        System.out.println("Failures: " + failureCount.get());
        
        System.out.println("\nPer-thread results:");
        for (int i = 0; i < numThreads; i++) {
            System.out.println("  Thread " + i + ": " + results.getOrDefault(i, "NO_RESULT"));
        }
        
        // Verify final state
        System.out.println("\nFinal state:");
        QueryResponse finalRead = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        
        if (finalRead.getRows() == null || finalRead.getRows().isEmpty()) {
            System.err.println("  ❌ CRITICAL: Row disappeared! This is a correctness bug!");
            fail("Row disappeared - this indicates writes are corrupting data or bypassing Percolator");
        }
        
        int finalCounter = (Integer) finalRead.getRows().get(0).get("counter");
        System.out.println("  Final counter: " + finalCounter);
        System.out.println("  Expected: " + successCount.get() + " (one per successful commit)");
        
        // Key assertion: final counter must equal successful commits
        if (finalCounter != successCount.get()) {
            System.err.println("\n❌ CORRECTNESS BUG DETECTED!");
            System.err.println("  Final counter (" + finalCounter + ") != Successful commits (" + successCount.get() + ")");
            System.err.println("  This means either:");
            System.err.println("    1. Lost updates (writes not atomic)");
            System.err.println("    2. Writes bypassing Percolator staging");
            System.err.println("    3. Conflict detection not working");
            fail("Correctness bug: final counter doesn't match successful commits");
        }
        
        System.out.println("\n✅ Concurrent transactions test PASSED");
        System.out.println("   No lost updates detected");
        System.out.println("   Percolator staging working correctly\n");
    }
}

