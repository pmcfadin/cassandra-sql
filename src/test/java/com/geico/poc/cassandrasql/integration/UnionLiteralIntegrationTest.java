package com.geico.poc.cassandrasql.integration;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for UNION queries with literals through QueryService
 * (simulates psql client flow)
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=KV"
})
public class UnionLiteralIntegrationTest {
    
    @Autowired
    private QueryService queryService;
    
    private String connId;
    
    @BeforeEach
    public void setup() {
        connId = "test-conn-" + System.nanoTime();
    }
    
    @Test
    public void testUnionWithLiteralsThroughQueryService() throws Exception {
        System.out.println("\n=== Integration Test: UNION with Literals (psql flow) ===\n");
        
        // Create tables with unique names
        String branches = "integ_branches_" + System.nanoTime();
        String tellers = "integ_tellers_" + System.nanoTime();
        
        queryService.execute("CREATE TABLE " + branches + " (bid INT PRIMARY KEY, bbalance INT)", connId);
        queryService.execute("CREATE TABLE " + tellers + " (tid INT PRIMARY KEY, tbalance INT)", connId);
        
        // Insert data
        queryService.execute("INSERT INTO " + branches + " (bid, bbalance) VALUES (1, 1000)", connId);
        queryService.execute("INSERT INTO " + branches + " (bid, bbalance) VALUES (2, 2000)", connId);
        queryService.execute("INSERT INTO " + tellers + " (tid, tbalance) VALUES (1, 500)", connId);
        queryService.execute("INSERT INTO " + tellers + " (tid, tbalance) VALUES (2, 600)", connId);
        
        // Execute UNION query with literals (exactly as psql would send it)
        String sql = "SELECT 'Branches' as table_name, COUNT(*) as row_count, SUM(bbalance) as total_balance FROM " + branches + " " +
                     "UNION ALL " +
                     "SELECT 'Tellers' as table_name, COUNT(*) as row_count, SUM(tbalance) as total_balance FROM " + tellers;
        
        System.out.println("Executing query through QueryService:");
        System.out.println(sql);
        
        QueryResponse response = queryService.execute(sql, connId);
        
        System.out.println("\nResponse:");
        System.out.println("  Error: " + response.getError());
        System.out.println("  Columns: " + response.getColumns());
        System.out.println("  Rows: " + response.getRows());
        
        // Verify success
        assertNull(response.getError(), "Query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(2, response.getRows().size(), "Should have 2 rows");
        
        // Verify literals are present
        for (Map<String, Object> row : response.getRows()) {
            Object tableName = row.get("table_name");
            if (tableName == null) tableName = row.get("TABLE_NAME");
            
            Object count = row.get("row_count");
            if (count == null) count = row.get("ROW_COUNT");
            
            Object total = row.get("total_balance");
            if (total == null) total = row.get("TOTAL_BALANCE");
            
            assertNotNull(tableName, "Should have table_name literal");
            assertNotNull(count, "Should have row_count");
            assertNotNull(total, "Should have total_balance");
            
            System.out.println("  " + tableName + ": count=" + count + ", total=" + total);
            
            // Verify literal values
            String tableNameStr = tableName.toString();
            assertTrue(tableNameStr.equals("Branches") || tableNameStr.equals("Tellers"), 
                      "table_name should be 'Branches' or 'Tellers', got: " + tableNameStr);
        }
        
        // Verify first row is Branches
        Map<String, Object> firstRow = response.getRows().get(0);
        Object firstTableName = firstRow.get("table_name");
        if (firstTableName == null) firstTableName = firstRow.get("TABLE_NAME");
        assertEquals("Branches", firstTableName.toString(), "First row should be Branches");
        
        // Verify second row is Tellers
        Map<String, Object> secondRow = response.getRows().get(1);
        Object secondTableName = secondRow.get("table_name");
        if (secondTableName == null) secondTableName = secondRow.get("TABLE_NAME");
        assertEquals("Tellers", secondTableName.toString(), "Second row should be Tellers");
        
        System.out.println("\n✅ Integration test passed! UNION with literals works through QueryService.");
    }
}

