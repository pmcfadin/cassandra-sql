package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to understand why INSERT/SELECT is failing
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=KV",
    "logging.level.org.cassandrasql=DEBUG"
})
public class DebugInsertSelectTest {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private CqlSession session;
    
    @Test
    public void testInsertSelectDebug() throws Exception {
        System.out.println("\n========== DEBUG INSERT/SELECT TEST ==========\n");
        
        // 0. Cleanup
        System.out.println("0. Cleanup...");
        try {
            queryService.execute("DROP TABLE IF EXISTS debug_test");
            Thread.sleep(100);
        } catch (Exception e) {
            // Ignore
        }
        
        // 1. Create table
        System.out.println("1. Creating table...");
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE debug_test (id INT PRIMARY KEY, data TEXT)"
        );
        System.out.println("   CREATE response: " + createResp);
        System.out.println("   Error: " + createResp.getError());
        assertNull(createResp.getError());
        
        // 2. Wait for schema to propagate
        System.out.println("\n2. Waiting for schema...");
        Thread.sleep(100);
        
        // 3. Insert data
        System.out.println("\n3. Inserting data...");
        QueryResponse insertResp = queryService.execute(
            "INSERT INTO debug_test (id, data) VALUES (1, 'test_value')"
        );
        System.out.println("   INSERT response: " + insertResp);
        System.out.println("   Error: " + insertResp.getError());
        System.out.println("   Rows inserted: " + insertResp.getRowCount());
        assertNull(insertResp.getError());
        
        // 4. Check raw KV store
        System.out.println("\n4. Checking raw KV store...");
        ResultSet kvRs = session.execute(
            "SELECT key, value, ts, commit_ts, deleted FROM cassandra_sql.kv_store LIMIT 10"
        );
        int kvCount = 0;
        for (var row : kvRs) {
            kvCount++;
            System.out.println("   KV row " + kvCount + ":");
            System.out.println("     key: " + row.getByteBuffer("key"));
            System.out.println("     ts: " + row.getLong("ts"));
            System.out.println("     commit_ts: " + row.getLong("commit_ts"));
            System.out.println("     deleted: " + row.getBoolean("deleted"));
        }
        System.out.println("   Total KV rows: " + kvCount);
        
        // 5. Select data
        System.out.println("\n5. Selecting data...");
        QueryResponse selectResp = queryService.execute(
            "SELECT * FROM debug_test"
        );
        System.out.println("   SELECT response: " + selectResp);
        System.out.println("   Error: " + selectResp.getError());
        System.out.println("   Rows: " + (selectResp.getRows() != null ? selectResp.getRows().size() : "null"));
        
        if (selectResp.getRows() != null && !selectResp.getRows().isEmpty()) {
            System.out.println("   First row: " + selectResp.getRows().get(0));
        }
        
        assertNull(selectResp.getError(), "SELECT should succeed");
        assertNotNull(selectResp.getRows(), "Rows should not be null");
        
        System.out.println("\n6. ASSERTION: Expecting 1 row, got " + selectResp.getRows().size());
        assertEquals(1, selectResp.getRows().size(), "Should have 1 row");
        
        System.out.println("\n========== TEST PASSED ==========\n");
    }
}

