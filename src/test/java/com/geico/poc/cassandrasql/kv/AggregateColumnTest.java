package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test aggregate column names
 */
public class AggregateColumnTest extends KvTestBase {
    
    @Test
    public void testAggregateColumns() throws Exception {
        System.out.println("\n=== Aggregate Column Test ===\n");
        
        // Create table
        queryService.execute("CREATE TABLE t (id INT PRIMARY KEY, val INT)");
        queryService.execute("INSERT INTO t (id, val) VALUES (1, 100)");
        queryService.execute("INSERT INTO t (id, val) VALUES (2, 200)");
        
        // Aggregate with alias
        String sql = "SELECT COUNT(*) as row_count, SUM(val) as total FROM t";
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Error: " + response.getError());
        System.out.println("Columns: " + response.getColumns());
        System.out.println("Rows: " + response.getRows());
        
        if (response.getRows() != null && !response.getRows().isEmpty()) {
            Map<String, Object> row = response.getRows().get(0);
            System.out.println("  Row keys: " + row.keySet());
            System.out.println("  Row: " + row);
        }
        
        assertNull(response.getError(), "Should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 row");
    }
}

