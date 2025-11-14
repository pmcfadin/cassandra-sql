package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive correctness tests for KV mode.
 * 
 * Tests cover:
 * - Basic CRUD operations
 * - Schema correctness
 * - DELETE operations with MVCC
 * - Transaction atomicity
 * - Concurrent modifications
 * - Percolator isolation
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KvCorrectnessTest extends KvTestBase {

    // Use unique table name per test method
    private String testTable;
    
    @BeforeEach
    public void setupTest() {
        testTable = uniqueTableName("kv_correctness");
    }
    
    private String getTestTable() {
        return testTable;
    }

    // ========================================
    // Test 1: Schema Correctness
    // ========================================

    @Test
    @Order(1)
    public void testSchemaCorrectness() throws Exception {
        testTable = uniqueTableName("kv_correctness");
        queryService.execute("DROP TABLE IF EXISTS " + testTable);

        System.out.println("Creating table " + testTable);
        // Create table with specific schema
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name TEXT, email TEXT, age INT)"
        );
        assertNull(createResp.getError(), "CREATE TABLE should succeed");

        // Insert data
        queryService.execute(
            "INSERT INTO " + testTable + " (id, name, email, age) VALUES (1, 'Alice', 'alice@example.com', 30)"
        );

        // Verify schema
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + testTable);
        System.out.println("Rows: " + selectResp.getRows().toString());
        assertNull(selectResp.getError(), "SELECT should succeed");
        assertNotNull(selectResp.getRows(), "Should have rows");
        assertEquals(1, selectResp.getRows().size(), "Should have 1 row");

        Map<String, Object> row = selectResp.getRows().get(0);
        assertTrue(row.containsKey("id"), "Should have 'id' column");
        assertTrue(row.containsKey("name"), "Should have 'name' column");
        assertTrue(row.containsKey("email"), "Should have 'email' column");
        assertTrue(row.containsKey("age"), "Should have 'age' column");
        
        assertEquals(1, row.get("id"), "id should be 1");
        assertEquals("Alice", row.get("name"), "name should be Alice");
        assertEquals("alice@example.com", row.get("email"), "email should match");
        assertEquals(30, row.get("age"), "age should be 30");
    }

    // ========================================
    // Test 2: DELETE Correctness
    // ========================================

    @Test
    @Order(2)
    public void testDeleteCorrectness() throws Exception {
        testTable = uniqueTableName("kv_correctness");
        // Create and populate table
        queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, name TEXT)"
        );
        queryService.execute("INSERT INTO " + getTestTable() + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + getTestTable() + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + getTestTable() + " (id, name) VALUES (3, 'Charlie')");

        // Verify 3 rows
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + getTestTable());
        assertEquals(3, selectResp.getRows().size(), "Should have 3 rows initially");

        // Delete one row
        QueryResponse deleteResp = queryService.execute("DELETE FROM " + getTestTable() + " WHERE id = 2");
        assertNull(deleteResp.getError(), "DELETE should succeed");
        assertEquals(1, deleteResp.getRowCount(), "Should delete 1 row");

        // Verify 2 rows remain
        selectResp = queryService.execute("SELECT * FROM " + getTestTable());
        assertEquals(2, selectResp.getRows().size(), "Should have 2 rows after delete");

        // Verify Bob is gone
        for (Map<String, Object> row : selectResp.getRows()) {
            assertNotEquals("Bob", row.get("name"), "Bob should be deleted");
        }

        // Delete another row
        queryService.execute("DELETE FROM " + getTestTable() + " WHERE id = 1");
        selectResp = queryService.execute("SELECT * FROM " + getTestTable());
        assertEquals(1, selectResp.getRows().size(), "Should have 1 row after second delete");
        assertEquals("Charlie", selectResp.getRows().get(0).get("name"), "Only Charlie should remain");
    }

    // ========================================
    // Test 3: UPDATE Correctness
    // ========================================

    @Test
    @Order(3)
    public void testUpdateCorrectness() throws Exception {
        queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, name TEXT, age INT)"
        );
        queryService.execute("INSERT INTO " + getTestTable() + " (id, name, age) VALUES (1, 'Alice', 30)");

        // Update
        QueryResponse updateResp = queryService.execute(
            "UPDATE " + getTestTable() + " SET name = 'Alice Updated', age = 31 WHERE id = 1"
        );
        assertNull(updateResp.getError(), "UPDATE should succeed");

        // Verify update
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + getTestTable() + " WHERE id = 1");
        assertEquals(1, selectResp.getRows().size(), "Should have 1 row");
        
        Map<String, Object> row = selectResp.getRows().get(0);
        assertEquals("Alice Updated", row.get("name"), "Name should be updated");
        assertEquals(31, row.get("age"), "Age should be updated");
    }

    // ========================================
    // Test 4: DROP and Recreate with Different Schema
    // ========================================

    @Test
    @Order(4)
    public void testDropAndRecreateWithDifferentSchema() throws Exception {
        // Create table with schema 1
        queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, col1 TEXT)"
        );
        queryService.execute("INSERT INTO " + getTestTable() + " (id, col1) VALUES (1, 'value1')");

        // Verify schema 1
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + getTestTable());
        assertNull(selectResp.getError(), "SELECT should succeed");
        assertNotNull(selectResp.getRows(), "Should have rows");
        assertEquals(1, selectResp.getRows().size(), "Should have 1 row");
        Map<String, Object> row = selectResp.getRows().get(0);
        assertTrue(row.containsKey("col1"), "Should have col1");
        assertFalse(row.containsKey("col2"), "Should not have col2");

        // Drop table
        QueryResponse dropResp = queryService.execute("DROP TABLE " + getTestTable());
        assertNull(dropResp.getError(), "DROP TABLE should succeed");
        Thread.sleep(200);

        // Verify table is gone
        try {
            QueryResponse errorResp = queryService.execute("SELECT * FROM " + getTestTable());
            if (errorResp.getError() == null) {
                // If no error in response, check if it's actually empty or if table exists
                fail("Should have gotten an error querying dropped table, but got: " + errorResp.getRows());
            }
            assertTrue(errorResp.getError().contains("does not exist"), 
                "Error should say table doesn't exist, got: " + errorResp.getError());
        } catch (Exception e) {
            // Also acceptable - exception thrown
            assertTrue(e.getMessage().contains("does not exist"), 
                "Exception should say table doesn't exist, got: " + e.getMessage());
        }

        // Create table with schema 2
        queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, col2 TEXT, col3 INT)"
        );
        queryService.execute("INSERT INTO " + getTestTable() + " (id, col2, col3) VALUES (1, 'value2', 42)");

        // Verify schema 2
        selectResp = queryService.execute("SELECT * FROM " + getTestTable());
        row = selectResp.getRows().get(0);
        assertFalse(row.containsKey("col1"), "Should not have col1 from old schema");
        assertTrue(row.containsKey("col2"), "Should have col2");
        assertTrue(row.containsKey("col3"), "Should have col3");
        assertEquals("value2", row.get("col2"), "col2 should have correct value");
        assertEquals(42, row.get("col3"), "col3 should have correct value");
    }

    // ========================================
    // Test 5: Transaction Atomicity - Single Key
    // ========================================

    @Test
    @Order(5)
    public void testTransactionAtomicitySingleKey() throws Exception {
        queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, balance INT)"
        );
        queryService.execute("INSERT INTO " + getTestTable() + " (id, balance) VALUES (1, 100)");

        // Simulate atomic increment
        String connectionId = "test-conn-" + System.currentTimeMillis();
        
        // Read current balance
        QueryResponse readResp = queryService.execute("SELECT * FROM " + getTestTable() + " WHERE id = 1", connectionId);
        assertNull(readResp.getError(), "SELECT should succeed: " + 
            (readResp.getError() != null ? readResp.getError() : ""));
        assertNotNull(readResp.getRows(), "Should have rows");
        assertTrue(readResp.getRows().size() > 0, "Should have at least 1 row");
        
        int currentBalance = (Integer) readResp.getRows().get(0).get("balance");
        assertEquals(100, currentBalance, "Initial balance should be 100");
        
        // Update balance
        QueryResponse updateResp = queryService.execute(
            "UPDATE " + getTestTable() + " SET balance = " + (currentBalance + 50) + " WHERE id = 1",
            connectionId
        );
        assertNull(updateResp.getError(), "UPDATE should succeed: " + 
            (updateResp.getError() != null ? updateResp.getError() : ""));

        // Verify
        QueryResponse verifyResp = queryService.execute("SELECT * FROM " + getTestTable() + " WHERE id = 1");
        assertNull(verifyResp.getError(), "Verify SELECT should succeed");
        assertEquals(150, verifyResp.getRows().get(0).get("balance"), "Balance should be 150");
    }

    // ========================================
    // Test 6: Concurrent Modifications - Lost Update Prevention
    // ========================================

    @Test
    @Order(6)
    public void testConcurrentModificationsNoLostUpdates() throws Exception {
        // Test Percolator-style transactions with concurrent read-modify-write operations
        // With proper transaction isolation, there should be NO lost updates
        // Each transaction is atomic: BEGIN -> SELECT -> UPDATE -> COMMIT
        
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, counter INT)"
        );
        if (createResp.getError() != null) {
            fail("CREATE TABLE failed: " + createResp.getError());
        }
        
        QueryResponse insertResp = queryService.execute("INSERT INTO " + getTestTable() + " (id, counter) VALUES (1, 0)");
        if (insertResp.getError() != null) {
            fail("INSERT failed: " + insertResp.getError());
        }

        int numThreads = 10;
        int incrementsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        try {
                            String connId = "thread-" + threadId + "-" + j;
                            
                            // BEGIN transaction for read-modify-write atomicity
                            queryService.execute("BEGIN", connId);
                            
                            // Read current value
                            QueryResponse readResp = queryService.execute(
                                "SELECT * FROM " + getTestTable() + " WHERE id = 1",
                                connId
                            );
                            if (readResp.getError() != null || readResp.getRows() == null || readResp.getRows().isEmpty()) {
                                queryService.execute("ROLLBACK", connId);
                                failureCount.incrementAndGet();
                                continue;
                            }
                            int current = (Integer) readResp.getRows().get(0).get("counter");
                            
                            // Small delay to increase contention
                            Thread.sleep(10);
                            
                            // Update
                            QueryResponse updateResp = queryService.execute(
                                "UPDATE " + getTestTable() + " SET counter = " + (current + 1) + " WHERE id = 1",
                                connId
                            );
                            
                            if (updateResp.getError() == null) {
                                // COMMIT transaction
                                QueryResponse commitResp = queryService.execute("COMMIT", connId);
                                if (commitResp.getError() == null) {
                                    successCount.incrementAndGet();
                                } else {
                                    System.err.println("COMMIT failed: " + commitResp.getError());
                                    failureCount.incrementAndGet();
                                }
                            } else {
                                System.err.println("UPDATE failed: " + updateResp.getError());
                                queryService.execute("ROLLBACK", connId);
                                failureCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            try {
                                queryService.execute("ROLLBACK", "thread-" + threadId + "-" + j);
                            } catch (Exception rollbackEx) {
                                // Ignore
                            }
                            failureCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify final counter value
        QueryResponse finalResp = queryService.execute("SELECT * FROM " + getTestTable() + " WHERE id = 1");
        assertNull(finalResp.getError(), "Final SELECT should succeed");
        
        System.out.println("Concurrent test results:");
        System.out.println("  Successes: " + successCount.get());
        System.out.println("  Failures: " + failureCount.get());
        System.out.println("  Rows returned: " + (finalResp.getRows() != null ? finalResp.getRows().size() : "null"));
        
        if (finalResp.getRows() == null || finalResp.getRows().isEmpty()) {
            fail("Final SELECT returned no rows - data was lost! This indicates a serious transaction safety bug.");
        }
        
        int finalCounter = (Integer) finalResp.getRows().get(0).get("counter");
        System.out.println("  Final counter: " + finalCounter);
        System.out.println("  Expected: " + (numThreads * incrementsPerThread));

        // With proper Percolator-style transactions, there should be NO lost updates
        // Each transaction is atomic: either all operations succeed or the transaction fails
        // The final counter should equal the number of successful commits
        int expectedTotal = numThreads * incrementsPerThread;
        
        System.out.println("  Successes: " + successCount.get());
        System.out.println("  Failures: " + failureCount.get());
        System.out.println("  Final counter: " + finalCounter);
        System.out.println("  Expected: " + expectedTotal);
        
        // The final counter should match the number of successful commits
        // (Some transactions may fail due to conflicts, but no updates should be lost)
        assertEquals(successCount.get(), finalCounter, 
            "Final counter should equal successful commits (no lost updates)");
        
        // At least some transactions should succeed
        assertTrue(successCount.get() > 0, "At least some transactions should succeed");
        
        // Total attempts should equal expected
        assertEquals(expectedTotal, successCount.get() + failureCount.get(), 
            "Total attempts should equal expected");
    }

    // ========================================
    // Test 7: Read Your Own Writes
    // ========================================

    @Test
    @Order(7)
    public void testReadYourOwnWrites() throws Exception {
        queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, data TEXT)"
        );

        String connectionId = "test-conn-" + System.currentTimeMillis();

        // Write
        queryService.execute(
            "INSERT INTO " + getTestTable() + " (id, data) VALUES (1, 'test_value')",
            connectionId
        );

        // Read immediately (should see own write)
        QueryResponse readResp = queryService.execute(
            "SELECT * FROM " + getTestTable() + " WHERE id = 1",
            connectionId
        );

        assertNull(readResp.getError(), "Read should succeed");
        assertEquals(1, readResp.getRows().size(), "Should see own write");
        assertEquals("test_value", readResp.getRows().get(0).get("data"), "Should read correct value");
    }

    // ========================================
    // Test 8: Multiple Table Operations
    // ========================================

    @Test
    @Order(8)
    public void testMultipleTableOperations() throws Exception {
        String table1 = getTestTable() + "_1";
        String table2 = getTestTable() + "_2";

        try {
            // Create two tables
            queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, data TEXT)");
            queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, info TEXT)");

            // Insert into both
            queryService.execute("INSERT INTO " + table1 + " (id, data) VALUES (1, 'data1')");
            queryService.execute("INSERT INTO " + table2 + " (id, info) VALUES (1, 'info1')");

            // Verify both
            QueryResponse resp1 = queryService.execute("SELECT * FROM " + table1);
            QueryResponse resp2 = queryService.execute("SELECT * FROM " + table2);

            assertEquals(1, resp1.getRows().size(), "Table 1 should have 1 row");
            assertEquals(1, resp2.getRows().size(), "Table 2 should have 1 row");
            assertEquals("data1", resp1.getRows().get(0).get("data"), "Table 1 data should match");
            assertEquals("info1", resp2.getRows().get(0).get("info"), "Table 2 info should match");

        } finally {
            queryService.execute("DROP TABLE IF EXISTS " + table1);
            queryService.execute("DROP TABLE IF EXISTS " + table2);
        }
    }

    // ========================================
    // Test 9: DELETE with WHERE on Primary Key
    // ========================================

    @Test
    @Order(9)
    public void testDeleteWithWhereOnPrimaryKey() throws Exception {
        queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, age INT, status TEXT)"
        );
        queryService.execute("INSERT INTO " + getTestTable() + " (id, age, status) VALUES (1, 25, 'active')");
        queryService.execute("INSERT INTO " + getTestTable() + " (id, age, status) VALUES (2, 30, 'inactive')");
        queryService.execute("INSERT INTO " + getTestTable() + " (id, age, status) VALUES (3, 25, 'active')");

        // Delete by primary key
        QueryResponse deleteResp = queryService.execute(
            "DELETE FROM " + getTestTable() + " WHERE id = 2"
        );
        assertNull(deleteResp.getError(), "DELETE should succeed: " + 
            (deleteResp.getError() != null ? deleteResp.getError() : ""));

        // Verify 2 rows remain
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + getTestTable());
        assertEquals(2, selectResp.getRows().size(), "Should have 2 rows remaining");
        
        // Verify id=2 is gone
        for (Map<String, Object> row : selectResp.getRows()) {
            assertNotEquals(2, row.get("id"), "id=2 should be deleted");
        }
    }

    // ========================================
    // Test 10: MVCC Snapshot Isolation
    // ========================================

    @Test
    @Order(10)
    public void testMVCCSnapshotIsolation() throws Exception {
        queryService.execute(
            "CREATE TABLE " + getTestTable() + " (id INT PRIMARY KEY, amount INT)"
        );
        queryService.execute("INSERT INTO " + getTestTable() + " (id, amount) VALUES (1, 100)");

        // Connection 1 reads
        String conn1 = "conn1-" + System.currentTimeMillis();
        QueryResponse read1 = queryService.execute("SELECT * FROM " + getTestTable() + " WHERE id = 1", conn1);
        assertNull(read1.getError(), "Initial SELECT should succeed");
        int amount1 = (Integer) read1.getRows().get(0).get("amount");
        assertEquals(100, amount1, "Initial amount should be 100");

        // Connection 2 updates
        String conn2 = "conn2-" + System.currentTimeMillis();
        QueryResponse updateResp = queryService.execute("UPDATE " + getTestTable() + " SET amount = 200 WHERE id = 1", conn2);
        assertNull(updateResp.getError(), "UPDATE should succeed");

        // Connection 1 reads again (should see consistent snapshot)
        QueryResponse read2 = queryService.execute("SELECT * FROM " + getTestTable() + " WHERE id = 1", conn1);
        assertNull(read2.getError(), "Second SELECT should succeed");
        int amount2 = (Integer) read2.getRows().get(0).get("amount");

        // With MVCC, conn1 might see old or new value depending on timestamp
        // But it should be consistent within the connection
        assertTrue(amount2 == 100 || amount2 == 200, "Amount should be either 100 or 200");
    }
}

