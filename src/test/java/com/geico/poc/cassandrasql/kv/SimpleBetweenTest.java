package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to debug BETWEEN operator
 */
public class SimpleBetweenTest extends KvTestBase {
    
    @Test
    public void testBasicWhere() throws Exception {
        String table = uniqueTableName("test_where");
        queryService.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, val INT)");
        
        queryService.execute("INSERT INTO " + table + " (id, val) VALUES (1, 10)");
        queryService.execute("INSERT INTO " + table + " (id, val) VALUES (2, 20)");
        queryService.execute("INSERT INTO " + table + " (id, val) VALUES (3, 30)");
        
        // Test basic WHERE
        String sql = "SELECT * FROM " + table + " WHERE val > 15";
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Basic WHERE - Error: " + response.getError());
        System.out.println("Basic WHERE - Rows: " + response.getRows().size());
        
        assertNull(response.getError());
        assertEquals(2, response.getRows().size(), "Should find 2 rows (20, 30)");
    }
    
    @Test
    public void testSimpleBetween() throws Exception {
        String table = uniqueTableName("test_between");
        queryService.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, val INT)");
        
        queryService.execute("INSERT INTO " + table + " (id, val) VALUES (1, 10)");
        queryService.execute("INSERT INTO " + table + " (id, val) VALUES (2, 20)");
        queryService.execute("INSERT INTO " + table + " (id, val) VALUES (3, 30)");
        queryService.execute("INSERT INTO " + table + " (id, val) VALUES (4, 40)");
        queryService.execute("INSERT INTO " + table + " (id, val) VALUES (5, 50)");
        
        // Test BETWEEN with SELECT *
        String sql = "SELECT * FROM " + table + " WHERE val BETWEEN 20 AND 40";
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Error: " + response.getError());
        System.out.println("Rows: " + response.getRows());
        
        assertNull(response.getError());
        assertNotNull(response.getRows());
        assertEquals(3, response.getRows().size(), "Should find 3 rows (20, 30, 40)");
    }
}

