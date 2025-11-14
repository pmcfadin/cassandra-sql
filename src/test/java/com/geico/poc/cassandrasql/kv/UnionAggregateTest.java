package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test UNION with aggregates
 */
public class UnionAggregateTest extends KvTestBase {
    
    @Test
    public void testUnionWithAggregates() throws Exception {
        System.out.println("\n=== UNION with Aggregates Test ===\n");
        
        // Create tables
        queryService.execute("CREATE TABLE branches (bid INT PRIMARY KEY, bbalance INT)");
        queryService.execute("CREATE TABLE tellers (tid INT PRIMARY KEY, tbalance INT)");
        
        // Insert data
        queryService.execute("INSERT INTO branches (bid, bbalance) VALUES (1, 1000)");
        queryService.execute("INSERT INTO branches (bid, bbalance) VALUES (2, 2000)");
        queryService.execute("INSERT INTO tellers (tid, tbalance) VALUES (1, 500)");
        queryService.execute("INSERT INTO tellers (tid, tbalance) VALUES (2, 600)");
        
        // UNION ALL with aggregates
        String sql = "SELECT COUNT(*) as row_count, SUM(bbalance) as total FROM branches " +
                     "UNION ALL " +
                     "SELECT COUNT(*) as row_count, SUM(tbalance) as total FROM tellers";
        
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Error: " + response.getError());
        System.out.println("Columns: " + response.getColumns());
        System.out.println("Rows: " + response.getRows());
        
        if (response.getRows() != null) {
            for (Map<String, Object> row : response.getRows()) {
                System.out.println("  Row: " + row);
            }
        }
        
        assertNull(response.getError(), "Should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(2, response.getRows().size(), "Should have 2 rows");
        assertNotNull(response.getColumns(), "Should have columns");
        assertTrue(response.getColumns().size() > 0, "Should have column names");
        
        // Verify data
        Map<String, Object> row1 = response.getRows().get(0);
        Map<String, Object> row2 = response.getRows().get(1);
        
        System.out.println("\nRow 1: " + row1);
        System.out.println("Row 2: " + row2);
        
        // Check that we have the aggregate values
        assertTrue(row1.containsKey("row_count") || row1.containsKey("ROW_COUNT"), "Row 1 should have row_count");
        assertTrue(row2.containsKey("row_count") || row2.containsKey("ROW_COUNT"), "Row 2 should have row_count");
    }
}

