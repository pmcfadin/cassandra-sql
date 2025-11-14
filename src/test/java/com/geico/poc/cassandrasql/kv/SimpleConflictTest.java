package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test to reproduce the "No write intents" bug
 */
public class SimpleConflictTest extends KvTestBase {
    
    @Test
    public void testTwoConflictingTransactions() throws Exception {
        System.out.println("\n=== Minimal Conflict Test ===\n");
        
        String tableName = uniqueTableName("conflict");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, data INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, data) VALUES (1, 0)");
        
        String conn1 = "conn1-" + System.nanoTime();
        String conn2 = "conn2-" + System.nanoTime();
        
        System.out.println("1. Transaction 1: BEGIN");
        queryService.execute("BEGIN", conn1);
        
        System.out.println("2. Transaction 2: BEGIN");
        queryService.execute("BEGIN", conn2);
        
        System.out.println("3. Transaction 1: SELECT");
        QueryResponse read1 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", conn1);
        int value1 = (Integer) read1.getRows().get(0).get("data");
        System.out.println("   Read: " + value1);
        
        System.out.println("4. Transaction 2: SELECT");
        QueryResponse read2 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1", conn2);
        int value2 = (Integer) read2.getRows().get(0).get("data");
        System.out.println("   Read: " + value2);
        
        System.out.println("5. Transaction 1: UPDATE");
        QueryResponse update1 = queryService.execute("UPDATE " + tableName + " SET data = " + (value1 + 1) + " WHERE id = 1", conn1);
        System.out.println("   Result: " + (update1.getError() == null ? "SUCCESS" : "FAILED: " + update1.getError()));
        
        System.out.println("6. Transaction 2: UPDATE");
        QueryResponse update2 = queryService.execute("UPDATE " + tableName + " SET data = " + (value2 + 1) + " WHERE id = 1", conn2);
        System.out.println("   Result: " + (update2.getError() == null ? "SUCCESS" : "FAILED: " + update2.getError()));
        
        System.out.println("7. Transaction 1: COMMIT");
        QueryResponse commit1 = queryService.execute("COMMIT", conn1);
        System.out.println("   Result: " + (commit1.getError() == null ? "SUCCESS" : "FAILED: " + commit1.getError()));
        
        System.out.println("8. Transaction 2: COMMIT");
        QueryResponse commit2 = queryService.execute("COMMIT", conn2);
        System.out.println("   Result: " + (commit2.getError() == null ? "SUCCESS" : "FAILED: " + commit2.getError()));
        
        // Verify final state
        QueryResponse finalRead = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        assertNotNull(finalRead.getRows(), "Should have rows");
        assertFalse(finalRead.getRows().isEmpty(), "Should have data");
        int finalValue = (Integer) finalRead.getRows().get(0).get("data");
        System.out.println("9. Final value: " + finalValue);
        
        // One should succeed, one should fail
        boolean oneSucceeded = (commit1.getError() == null) ^ (commit2.getError() == null);
        assertTrue(oneSucceeded, "Exactly one transaction should succeed");
        
        // Final value should be 1 (one increment)
        assertEquals(1, finalValue, "Final value should be 1");
        
        System.out.println("\n✅ Conflict test PASSED\n");
    }
}

