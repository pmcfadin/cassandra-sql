package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple transaction test to diagnose why transactions are failing
 */
public class SimpleTransactionTest extends KvTestBase {
    
    @Test
    public void testSimpleTransaction() throws Exception {
        System.out.println("\n=== SIMPLE TRANSACTION TEST ===\n");
        
        // Create table
        String tableName = uniqueTableName("simple_txn");
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, counter INT)"
        );
        System.out.println("1. CREATE TABLE: " + (createResp.getError() == null ? "SUCCESS" : "FAILED: " + createResp.getError()));
        assertNull(createResp.getError(), "CREATE should succeed");
        
        // Insert initial row
        QueryResponse insertResp = queryService.execute(
            "INSERT INTO " + tableName + " (id, counter) VALUES (1, 0)"
        );
        System.out.println("2. INSERT: " + (insertResp.getError() == null ? "SUCCESS" : "FAILED: " + insertResp.getError()));
        assertNull(insertResp.getError(), "INSERT should succeed");
        
        // Verify initial value
        QueryResponse selectResp1 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        System.out.println("3. SELECT (before txn): rows=" + (selectResp1.getRows() != null ? selectResp1.getRows().size() : "null"));
        if (selectResp1.getRows() != null && !selectResp1.getRows().isEmpty()) {
            System.out.println("   counter = " + selectResp1.getRows().get(0).get("counter"));
        }
        
        // Start transaction
        String connId = testConnectionId();
        System.out.println("\n4. BEGIN transaction (connId=" + connId + ")");
        QueryResponse beginResp = queryService.execute("BEGIN", connId);
        System.out.println("   BEGIN: " + (beginResp.getError() == null ? "SUCCESS" : "FAILED: " + beginResp.getError()));
        
        // Read in transaction
        QueryResponse selectResp2 = queryService.execute(
            "SELECT * FROM " + tableName + " WHERE id = 1",
            connId
        );
        System.out.println("5. SELECT (in txn): rows=" + (selectResp2.getRows() != null ? selectResp2.getRows().size() : "null"));
        if (selectResp2.getRows() != null && !selectResp2.getRows().isEmpty()) {
            int currentValue = (Integer) selectResp2.getRows().get(0).get("counter");
            System.out.println("   counter = " + currentValue);
            
            // Update in transaction
            System.out.println("\n6. UPDATE (in txn): SET counter = " + (currentValue + 1));
            QueryResponse updateResp = queryService.execute(
                "UPDATE " + tableName + " SET counter = " + (currentValue + 1) + " WHERE id = 1",
                connId
            );
            System.out.println("   UPDATE: " + (updateResp.getError() == null ? "SUCCESS" : "FAILED: " + updateResp.getError()));
            assertNull(updateResp.getError(), "UPDATE should succeed");
        } else {
            fail("SELECT in transaction returned no rows");
        }
        
        // Commit transaction
        System.out.println("\n7. COMMIT transaction");
        QueryResponse commitResp = queryService.execute("COMMIT", connId);
        System.out.println("   COMMIT: " + (commitResp.getError() == null ? "SUCCESS" : "FAILED: " + commitResp.getError()));
        assertNull(commitResp.getError(), "COMMIT should succeed");
        
        // Verify final value
        System.out.println("\n8. SELECT (after commit)");
        QueryResponse selectResp3 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        System.out.println("   rows=" + (selectResp3.getRows() != null ? selectResp3.getRows().size() : "null"));
        
        assertNotNull(selectResp3.getRows(), "Final SELECT should return rows");
        assertFalse(selectResp3.getRows().isEmpty(), "Final SELECT should not be empty");
        
        int finalCounter = (Integer) selectResp3.getRows().get(0).get("counter");
        System.out.println("   counter = " + finalCounter);
        
        assertEquals(1, finalCounter, "Counter should be incremented to 1");
        
        System.out.println("\n=== TEST PASSED ===\n");
    }
    
    @Test
    public void testAutoCommitUpdate() throws Exception {
        System.out.println("\n=== AUTO-COMMIT UPDATE TEST ===\n");
        
        // Create table
        String tableName = uniqueTableName("auto_commit");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, counter INT)");
        queryService.execute("INSERT INTO " + tableName + " (id, counter) VALUES (1, 0)");
        
        System.out.println("1. Initial value:");
        QueryResponse select1 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        int initial = (Integer) select1.getRows().get(0).get("counter");
        System.out.println("   counter = " + initial);
        
        // Update without explicit transaction (auto-commit)
        System.out.println("\n2. UPDATE (auto-commit)");
        QueryResponse updateResp = queryService.execute(
            "UPDATE " + tableName + " SET counter = " + (initial + 1) + " WHERE id = 1"
        );
        System.out.println("   UPDATE: " + (updateResp.getError() == null ? "SUCCESS" : "FAILED: " + updateResp.getError()));
        assertNull(updateResp.getError(), "UPDATE should succeed");
        
        // Verify
        System.out.println("\n3. Final value:");
        QueryResponse select2 = queryService.execute("SELECT * FROM " + tableName + " WHERE id = 1");
        int finalValue = (Integer) select2.getRows().get(0).get("counter");
        System.out.println("   counter = " + finalValue);
        
        assertEquals(initial + 1, finalValue, "Counter should be incremented");
        
        System.out.println("\n=== TEST PASSED ===\n");
    }
}

