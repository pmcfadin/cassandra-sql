package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to debug UNION execution
 */
public class SimpleUnionDebugTest extends KvTestBase {
    
    @Test
    public void testSimpleUnion() throws Exception {
        System.out.println("\n=== Simple UNION Debug Test ===\n");
        
        // Create tables
        queryService.execute("CREATE TABLE t1 (id INT PRIMARY KEY, val INT)");
        queryService.execute("CREATE TABLE t2 (id INT PRIMARY KEY, val INT)");
        
        // Insert data
        queryService.execute("INSERT INTO t1 (id, val) VALUES (1, 100)");
        queryService.execute("INSERT INTO t2 (id, val) VALUES (2, 200)");
        
        // Simple UNION ALL
        String sql = "SELECT id, val FROM t1 UNION ALL SELECT id, val FROM t2";
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
        
        assertNull(response.getError(), "Should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(2, response.getRows().size(), "Should have 2 rows");
    }
}

