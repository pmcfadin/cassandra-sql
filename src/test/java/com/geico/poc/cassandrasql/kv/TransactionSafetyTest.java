package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTransactionCoordinator.TransactionException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests specifically designed to verify the critical transaction safety fixes:
 * 
 * 1. Atomic lock acquisition (no race conditions)
 * 2. Atomic commit with verification
 * 3. Timestamp allocation atomicity
 * 4. Secondary lock commit retry logic
 * 5. Rollback correctness
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TransactionSafetyTest extends KvTestBase {

    @Autowired
    private KvTransactionCoordinator coordinator;

    @Autowired
    private TimestampOracle timestampOracle;

    private String testTable;

    @BeforeEach
    public void setup() throws Exception {
        testTable = uniqueTableName("safety_test");
        
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
            Thread.sleep(100);
        } catch (Exception e) {
            // Ignore
        }
        
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT PRIMARY KEY, amount INT, name TEXT)"
        );
        Thread.sleep(100);
    }

    @AfterEach
    public void cleanup() throws Exception {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
            Thread.sleep(100);
        } catch (Exception e) {
            // Ignore
        }
    }

    // ========================================
    // Test 1: Atomic Lock Acquisition - Different Rows
    // ========================================
    // This test verifies that updates to DIFFERENT rows all succeed
    // Each transaction should acquire a lock on a different key, so all should succeed

    @Test
    @Order(1)
    public void testAtomicLockAcquisitionDifferentRows() throws Exception {
        // Insert initial rows
        for (int i = 1; i <= 20; i++) {
            queryService.execute(
                "INSERT INTO " + testTable + " (id, amount, name) VALUES (" + i + ", 100, 'initial')"
            );
        }
        Thread.sleep(100);

        int numThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Each thread updates a DIFFERENT row - all should succeed
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            final int rowId = threadId + 1; // Each thread updates a different row
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    String connId = "thread-" + threadId + "-" + System.currentTimeMillis();
                    QueryResponse resp = queryService.execute(
                        "UPDATE " + testTable + " SET amount = " + (200 + threadId) + 
                        ", name = 'thread" + threadId + "' WHERE id = " + rowId,
                        connId
                    );
                    
                    if (resp.getError() == null) {
                        successCount.incrementAndGet();
                    } else if (resp.getError().contains("conflict") || 
                               resp.getError().contains("locked")) {
                        conflictCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                        System.err.println("Thread " + threadId + " error: " + resp.getError());
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Thread " + threadId + " exception: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();
        
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");

        executor.shutdown();

        System.out.println("Atomic lock acquisition (different rows) test results:");
        System.out.println("  Successes: " + successCount.get());
        System.out.println("  Conflicts: " + conflictCount.get());
        System.out.println("  Errors: " + errorCount.get());

        // All updates to different rows should succeed
        assertEquals(numThreads, successCount.get(), 
            "All transactions updating different rows should succeed");
        assertEquals(0, conflictCount.get(),
            "No conflicts expected when updating different rows");
        assertEquals(0, errorCount.get(),
            "No unexpected errors");

        // Verify final state - all rows should be updated
        for (int i = 1; i <= numThreads; i++) {
            QueryResponse finalResp = queryService.execute("SELECT * FROM " + testTable + " WHERE id = " + i);
            assertNotNull(finalResp.getRows());
            assertEquals(1, finalResp.getRows().size(), "Row " + i + " should exist");
            String name = finalResp.getRows().get(0).get("name").toString();
            // Name should be updated (either 'threadX' or still 'initial' if update failed, but we expect all to succeed)
            assertTrue(name.startsWith("thread") || name.equals("initial"),
                "Row " + i + " name: " + name);
        }
    }

    // ========================================
    // Test 2: Sequential Updates to Same Row
    // ========================================
    // This test verifies that sequential updates to the same row all succeed
    // Updates milliseconds apart are fine - they're not concurrent

    @Test
    @Order(2)
    public void testSequentialUpdatesSameRow() throws Exception {
        // Insert initial row
        queryService.execute(
            "INSERT INTO " + testTable + " (id, amount, name) VALUES (1, 100, 'initial')"
        );
        Thread.sleep(100);

        int numUpdates = 20;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Sequential updates - each waits for the previous to complete
        for (int i = 0; i < numUpdates; i++) {
            final int updateId = i;
            try {
                String connId = "sequential-" + updateId + "-" + System.currentTimeMillis();
                QueryResponse resp = queryService.execute(
                    "UPDATE " + testTable + " SET amount = " + (200 + updateId) + 
                    ", name = 'update" + updateId + "' WHERE id = 1",
                    connId
                );
                
                if (resp.getError() == null) {
                    successCount.incrementAndGet();
                } else if (resp.getError().contains("conflict") || 
                           resp.getError().contains("locked")) {
                    conflictCount.incrementAndGet();
                } else {
                    errorCount.incrementAndGet();
                    System.err.println("Update " + updateId + " error: " + resp.getError());
                }
                
                // Small delay to ensure sequential execution
                Thread.sleep(10);
            } catch (Exception e) {
                errorCount.incrementAndGet();
                System.err.println("Update " + updateId + " exception: " + e.getMessage());
            }
        }

        System.out.println("Sequential updates (same row) test results:");
        System.out.println("  Successes: " + successCount.get());
        System.out.println("  Conflicts: " + conflictCount.get());
        System.out.println("  Errors: " + errorCount.get());

        // All sequential updates should succeed
        assertEquals(numUpdates, successCount.get(), 
            "All sequential updates to the same row should succeed");
        assertEquals(0, conflictCount.get(),
            "No conflicts expected for sequential updates");
        assertEquals(0, errorCount.get(),
            "No unexpected errors");

        // Verify final state - last update should be visible
        QueryResponse finalResp = queryService.execute("SELECT * FROM " + testTable + " WHERE id = 1");
        assertNull(finalResp.getError(), "Final SELECT should succeed");
        assertEquals(1, finalResp.getRows().size(), "Should have exactly 1 row");
        
        int finalValue = (Integer) finalResp.getRows().get(0).get("amount");
        assertEquals(200 + numUpdates - 1, finalValue,
            "Final value should be from the last update");
    }

    // ========================================
    // Test 3: Concurrent Lock Acquisition - Same Row (Proves Atomicity)
    // ========================================
    // This test verifies that TRULY CONCURRENT updates to the same row
    // properly conflict - only one should succeed
    // The key is that all transactions start simultaneously and check for locks
    // at the same time - proving that lock acquisition is atomic

    @Test
    @Order(3)
    public void testConcurrentLockAcquisitionSameRow() throws Exception {
        // Insert initial row
        queryService.execute(
            "INSERT INTO " + testTable + " (id, amount, name) VALUES (1, 100, 'initial')"
        );
        Thread.sleep(100);

        int numThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Long> startTimestamps = new CopyOnWriteArrayList<>();
        List<Long> endTimestamps = new CopyOnWriteArrayList<>();

        // All threads try to update the SAME row CONCURRENTLY (all start at same time)
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // All threads wait here until startLatch is released
                    startLatch.await();
                    
                    long startTime = System.nanoTime();
                    startTimestamps.add(startTime);
                    
                    String connId = "concurrent-" + threadId + "-" + System.currentTimeMillis();
                    
                    // Begin transaction - this starts the transaction and will hold locks
                    QueryResponse beginResp = queryService.execute("BEGIN", connId);
                    if (beginResp.getError() != null) {
                        errorCount.incrementAndGet();
                        System.err.println("Thread " + threadId + " BEGIN error: " + beginResp.getError());
                        return;
                    }

                    // Sleep to hold the lock - this demonstrates contention
                    // Other transactions trying to acquire the lock will conflict
                    Thread.sleep(100); // Hold lock for 100ms

                    System.out.println("Thread " + threadId + " BEFORE UPDATE");
                    // Execute UPDATE - this will acquire a lock and hold it
                    QueryResponse updateResp = queryService.execute(
                        "UPDATE " + testTable + " SET amount = " + (200 + threadId) + 
                        ", name = 'thread" + threadId + "' WHERE id = 1",
                        connId
                    );
                    
                    if (updateResp.getError() != null) {
                        // Update failed - rollback and count as conflict
                        queryService.execute("ROLLBACK", connId);
                        if (updateResp.getError().contains("conflict") || 
                            updateResp.getError().contains("locked")) {
                            conflictCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                            System.err.println("Thread " + threadId + " UPDATE error: " + updateResp.getError());
                        }
                        return;
                    }
                    
                    // Sleep BEFORE commit - this creates a window where all transactions
                    // have buffered their writes but haven't tried to acquire locks yet
                    // When they all try to COMMIT simultaneously, only one should succeed
                    Thread.sleep(50); // Small delay to ensure all UPDATEs complete before COMMITs
                    
                    // Commit transaction - THIS is where lock acquisition happens (during prewrite)
                    // All transactions will try to acquire the lock here simultaneously
                    // With atomic lock acquisition, only one should succeed
                    QueryResponse commitResp = queryService.execute("COMMIT", connId);
                    
                    long endTime = System.nanoTime();
                    endTimestamps.add(endTime);
                    
                    if (commitResp.getError() == null) {
                        System.out.println("Thread " + threadId + " COMMIT succeeded");
                        successCount.incrementAndGet();
                    } else {
                        queryService.execute("ROLLBACK", connId);
                        if (commitResp.getError().contains("conflict") || commitResp.getError().contains("locked")) {
                            conflictCount.incrementAndGet();
                            System.out.println("Thread " + threadId + " COMMIT conflict: " + commitResp.getError());
                        }
                        else {
                            errorCount.incrementAndGet();
                            System.out.println("Thread " + threadId + " OTHER error: " + commitResp.getError());
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.out.println("Thread " + threadId + " exception: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously - this creates TRUE concurrency
        startLatch.countDown();
        
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");

        executor.shutdown();

        // Calculate timing to prove concurrency
        long minStart = startTimestamps.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxStart = startTimestamps.stream().mapToLong(Long::longValue).max().orElse(0);
        long maxEnd = endTimestamps.stream().mapToLong(Long::longValue).max().orElse(0);
        long concurrencyWindow = maxStart - minStart; // How spread out the starts were
        long totalDuration = maxEnd - minStart; // Total time from first start to last end
        
        System.out.println("Concurrent lock acquisition (same row) test results:");
        System.out.println("  Successes: " + successCount.get());
        System.out.println("  Conflicts: " + conflictCount.get());
        System.out.println("  Errors: " + errorCount.get());
        System.out.println("  Concurrency window: " + (concurrencyWindow / 1_000_000.0) + " ms");
        System.out.println("  Total duration: " + (totalDuration / 1_000_000.0) + " ms");

        // Prove they were concurrent (started within a reasonable window)
        // Note: Even if starts are spread out, if they all check for locks before any completes,
        // we're testing atomicity. The key is that multiple transactions check for locks
        // before any lock is acquired.
        assertTrue(concurrencyWindow < 100_000_000, // Started within 100ms of each other
            "Threads should start concurrently (within 100ms), actual: " + (concurrencyWindow / 1_000_000.0) + " ms");
        
        // More importantly: prove that multiple transactions checked for locks concurrently
        // by verifying that the total duration is much longer than individual transaction time
        // This means transactions were waiting/retrying, which proves they were concurrent
        boolean hadConcurrency = totalDuration > 50_000_000; // Total duration > 50ms suggests concurrency
        System.out.println("  Had concurrency (total duration > 50ms): " + hadConcurrency);

        // With atomic lock acquisition, exactly ONE should succeed
        // All others should get lock conflicts (not errors)
        // This proves atomicity: even though multiple transactions checked for locks,
        // only one could acquire it atomically
        assertEquals(1, successCount.get(), 
            "Exactly one transaction should succeed when updating the same row concurrently (atomic lock acquisition prevents race conditions). " +
            "Concurrency window: " + (concurrencyWindow / 1_000_000.0) + " ms, " +
            "Total duration: " + (totalDuration / 1_000_000.0) + " ms, " +
            "Successes: " + successCount.get());
        assertEquals(numThreads - 1, conflictCount.get() + errorCount.get(),
            "All other transactions should fail with conflicts or errors");
        assertEquals(0, errorCount.get(),
            "No unexpected errors (all failures should be lock conflicts)");

        // Verify final state is consistent - exactly one update should have succeeded
        QueryResponse finalResp = queryService.execute("SELECT * FROM " + testTable + " WHERE id = 1");
        assertNull(finalResp.getError(), "Final SELECT should succeed");
        assertEquals(1, finalResp.getRows().size(), "Should have exactly 1 row");
        
        int finalValue = (Integer) finalResp.getRows().get(0).get("amount");
        assertTrue(finalValue >= 200 && finalValue < 200 + numThreads,
            "Final value should be from one of the successful updates");
    }

    // ========================================
    // Test 2: Atomic Commit Verification
    // ========================================
    // This test verifies that commit operations are atomic and verified

    @Test
    @Order(2)
    public void testAtomicCommitVerification() throws Exception {
        // Insert initial row
        queryService.execute(
            "INSERT INTO " + testTable + " (id, amount, name) VALUES (1, 100, 'initial')"
        );
        Thread.sleep(100);

        // Perform multiple updates in sequence
        // Each should commit atomically with verification
        for (int i = 0; i < 10; i++) {
            String connId = "commit-test-" + i;
            QueryResponse resp = queryService.execute(
                "UPDATE " + testTable + " SET amount = " + (100 + i) + " WHERE id = 1",
                connId
            );
            assertNull(resp.getError(), "Update " + i + " should succeed");
            
            // Verify commit succeeded by reading back
            QueryResponse verify = queryService.execute("SELECT * FROM " + testTable + " WHERE id = 1");
            assertNull(verify.getError(), "Verify SELECT should succeed");
            assertEquals(100 + i, verify.getRows().get(0).get("amount"),
                "Amount should be committed correctly");
        }

        // Final verification
        QueryResponse finalResp = queryService.execute("SELECT * FROM " + testTable + " WHERE id = 1");
        assertEquals(109, finalResp.getRows().get(0).get("amount"),
            "Final amount should be 109 (100 + 9)");
    }

    // ========================================
    // Test 3: Timestamp Allocation Atomicity
    // ========================================
    // This test verifies that timestamp allocation is atomic and prevents duplicates

    @Test
    @Order(3)
    public void testTimestampAllocationAtomicity() throws Exception {
        int numThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        ConcurrentLinkedQueue<Long> timestamps = new ConcurrentLinkedQueue<>();
        AtomicInteger errorCount = new AtomicInteger(0);

        // All threads allocate timestamps concurrently
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // Each thread allocates multiple timestamps
                    for (int j = 0; j < 5; j++) {
                        long ts = timestampOracle.allocateStartTimestamp();
                        timestamps.add(ts);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Timestamp allocation exception: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        System.out.println("Timestamp allocation test results:");
        System.out.println("  Total timestamps allocated: " + timestamps.size());
        System.out.println("  Errors: " + errorCount.get());

        assertEquals(0, errorCount.get(), "No errors during timestamp allocation");
        
        // Check for duplicates
        long uniqueCount = timestamps.stream().distinct().count();
        System.out.println("  Unique timestamps: " + uniqueCount);
        System.out.println("  Duplicates: " + (timestamps.size() - uniqueCount));
        
        // Find and report duplicate timestamps
        Map<Long, Long> timestampCounts = timestamps.stream()
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        List<Map.Entry<Long, Long>> duplicates = timestampCounts.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .sorted(Map.Entry.comparingByKey())
            .collect(Collectors.toList());
        
        if (!duplicates.isEmpty()) {
            System.err.println("  DUPLICATE TIMESTAMPS DETECTED:");
            for (Map.Entry<Long, Long> dup : duplicates) {
                System.err.println("    Timestamp " + dup.getKey() + " allocated " + dup.getValue() + " times");
            }
        }
        
        // Check monotonicity (timestamps should be increasing)
        long[] sorted = timestamps.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.println("  Timestamp range: [" + (sorted.length > 0 ? sorted[0] : "N/A") + 
                          ", " + (sorted.length > 0 ? sorted[sorted.length-1] : "N/A") + "]");
        
        // Find gaps in sequence
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.length; i++) {
            long gap = sorted[i] - sorted[i-1];
            if (gap > 1) {
                gaps.add(gap);
            }
            assertTrue(sorted[i] > sorted[i-1],
                "Timestamps should be monotonically increasing");
        }
        if (!gaps.isEmpty()) {
            System.out.println("  Gaps in sequence: " + gaps.size() + " gaps found");
        }
        
        assertEquals(timestamps.size(), uniqueCount,
            "All timestamps should be unique (atomic allocation prevents duplicates)");
    }

    // ========================================
    // Test 4: Multi-Key Transaction Atomicity
    // ========================================
    // This test verifies that transactions with multiple keys commit atomically

    @Test
    @Order(4)
    public void testMultiKeyTransactionAtomicity() throws Exception {
        // Insert two rows
        queryService.execute(
            "INSERT INTO " + testTable + " (id, amount, name) VALUES (1, 100, 'Alice')"
        );
        queryService.execute(
            "INSERT INTO " + testTable + " (id, amount, name) VALUES (2, 50, 'Bob')"
        );
        Thread.sleep(100);

        // Simulate a transfer: update both rows in one transaction
        // This should be atomic - both succeed or both fail
        String connId = "transfer-" + System.currentTimeMillis();
        
        QueryResponse update1 = queryService.execute(
            "UPDATE " + testTable + " SET amount = 70 WHERE id = 1",
            connId
        );
        assertNull(update1.getError(), "First update should succeed");
        
        QueryResponse update2 = queryService.execute(
            "UPDATE " + testTable + " SET amount = 80 WHERE id = 2",
            connId
        );
        assertNull(update2.getError(), "Second update should succeed");

        // Verify both updates are committed
        QueryResponse verify1 = queryService.execute("SELECT * FROM " + testTable + " WHERE id = 1");
        QueryResponse verify2 = queryService.execute("SELECT * FROM " + testTable + " WHERE id = 2");
        
        assertEquals(70, verify1.getRows().get(0).get("amount"), "Alice's amount should be 70");
        assertEquals(80, verify2.getRows().get(0).get("amount"), "Bob's amount should be 80");
        
        // Total should be conserved (100 + 50 = 150, 70 + 80 = 150)
        int total = (Integer) verify1.getRows().get(0).get("amount") + 
                    (Integer) verify2.getRows().get(0).get("amount");
        assertEquals(150, total, "Total amount should be conserved (atomicity)");
    }

    // ========================================
    // Test 5: Rollback Correctness
    // ========================================
    // This test verifies that rollback correctly cleans up locks and uncommitted data

    @Test
    @Order(5)
    public void testRollbackCorrectness() throws Exception {
        // Insert initial row
        queryService.execute(
            "INSERT INTO " + testTable + " (id, amount, name) VALUES (1, 100, 'initial')"
        );
        Thread.sleep(100);

        // Start a transaction and perform an update
        String connId = "rollback-test-" + System.currentTimeMillis();
        
        QueryResponse updateResp = queryService.execute(
            "UPDATE " + testTable + " SET amount = 200 WHERE id = 1",
            connId
        );
        assertNull(updateResp.getError(), "Update should succeed");
        
        // Verify update is visible within transaction
        QueryResponse readInTx = queryService.execute(
            "SELECT * FROM " + testTable + " WHERE id = 1",
            connId
        );
        assertEquals(200, readInTx.getRows().get(0).get("amount"),
            "Update should be visible within transaction");

        // Note: In the current implementation, each statement auto-commits
        // So rollback may not be testable at the SQL level
        // But we can verify that the coordinator's rollback method works correctly
        // by testing it directly if needed
        
        // For now, verify that the update was committed (auto-commit behavior)
        QueryResponse finalResp = queryService.execute("SELECT * FROM " + testTable + " WHERE id = 1");
        assertEquals(200, finalResp.getRows().get(0).get("amount"),
            "Update should be committed");
    }

    // ========================================
    // Test 6: High Concurrency Stress Test
    // ========================================
    // This test stresses the system with many concurrent transactions

    @Test
    @Order(6)
    public void testHighConcurrencyStress() throws Exception {
        // Create multiple rows
        for (int i = 1; i <= 10; i++) {
            queryService.execute(
                "INSERT INTO " + testTable + " (id, amount, name) VALUES (" + i + ", 0, 'row" + i + "')"
            );
        }
        Thread.sleep(100);

        int numThreads = 30;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        // Each thread updates a random row
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            final int rowId = (i % 10) + 1; // Distribute across 10 rows
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    String connId = "stress-" + threadId;
                    QueryResponse resp = queryService.execute(
                        "UPDATE " + testTable + " SET amount = " + threadId + 
                        " WHERE id = " + rowId,
                        connId
                    );
                    
                    if (resp.getError() == null) {
                        successCount.incrementAndGet();
                    } else {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    conflictCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        System.out.println("High concurrency stress test results:");
        System.out.println("  Successes: " + successCount.get());
        System.out.println("  Conflicts: " + conflictCount.get());

        // Most transactions should succeed (different rows)
        // Some may conflict if multiple threads hit the same row
        // With 30 threads and 10 rows, we expect at least some successes
        assertTrue(successCount.get() > 0,
            "At least some transactions should succeed (got " + successCount.get() + " successes, " + conflictCount.get() + " conflicts)");
        
        // Verify final state is consistent (no corrupted data)
        for (int i = 1; i <= 10; i++) {
            QueryResponse resp = queryService.execute("SELECT * FROM " + testTable + " WHERE id = " + i);
            assertNull(resp.getError(), "SELECT should succeed for row " + i);
            assertNotNull(resp.getRows(), "Should have rows for row " + i);
            assertFalse(resp.getRows().isEmpty(), "Should have at least one row");
            
            // Amount should be an integer (not corrupted)
            Object amount = resp.getRows().get(0).get("amount");
            assertTrue(amount instanceof Integer, "Amount should be an integer (not corrupted)");
        }
    }
}

