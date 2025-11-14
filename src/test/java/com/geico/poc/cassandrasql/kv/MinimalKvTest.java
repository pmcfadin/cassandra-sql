package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test to debug INSERT/SELECT issues in KV mode
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=KV",
    "logging.level.org.cassandrasql=DEBUG"
})
public class MinimalKvTest extends KvTestBase{
    
    @Autowired
    private QueryService queryService;
    
    private final String connectionId = "test-connection";
    private String TABLE_NAME;
    
    @Test
    public void testBasicInsertSelect() throws Exception {
        TABLE_NAME = uniqueTableName("minimal_test");
        System.out.println("\n=== TEST: Basic INSERT/SELECT ===\n");
        
        // Create table
        System.out.println("1. Creating table...");
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + TABLE_NAME + " (id INT PRIMARY KEY, name TEXT)",
            connectionId
        );
        System.out.println("CREATE response: " + createResp);
        assertNull(createResp.getError(), "Create should succeed: " + createResp.getError());
        
        // Insert data
        System.out.println("\n2. Inserting data...");
        QueryResponse insertResp = queryService.execute(
            "INSERT INTO " + TABLE_NAME + " (id, name) VALUES (1, 'test')",
            connectionId
        );
        System.out.println("INSERT response: " + insertResp);
        assertNull(insertResp.getError(), "Insert should succeed: " + insertResp.getError());
        
        // Select data
        System.out.println("\n3. Selecting data...");
        QueryResponse selectResp = queryService.execute(
            "SELECT * FROM " + TABLE_NAME + " ",
            connectionId
        );
        System.out.println("SELECT response: " + selectResp);
        System.out.println("Error: " + selectResp.getError());
        System.out.println("Rows: " + (selectResp.getRows() != null ? selectResp.getRows().size() : "null"));
        
        if (selectResp.getRows() != null && !selectResp.getRows().isEmpty()) {
            System.out.println("First row: " + selectResp.getRows().get(0));
        }
        
        assertNull(selectResp.getError(), "Select should succeed: " + selectResp.getError());
        assertNotNull(selectResp.getRows(), "Rows should not be null");
        assertEquals(1, selectResp.getRows().size(), "Should have 1 row");
        
        System.out.println("\n=== TEST PASSED ===\n");
    }
}

