package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify catalog DELETE works correctly.
 */
@SpringBootTest
@ActiveProfiles({"kv-test"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SimpleCatalogDeleteTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    private String testTable;
    
    @BeforeEach
    public void setupTest() {
        testTable = uniqueTableName("simple_cat_" + System.nanoTime());
        
        // Clean up
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
            Thread.sleep(200);
        } catch (Exception e) {}
    }
    
    @AfterEach
    public void cleanupTest() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
            Thread.sleep(200);
        } catch (Exception e) {}
    }
    
    @Test
    @Order(1)
    public void testDropTableRemovesFromPgClass() throws Exception {
        System.out.println("\n=== TEST: DROP TABLE removes from pg_class ===\n");
        
        // Create table
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name TEXT)");
        
        // Verify it's in pg_class
        QueryResponse beforeResp = queryService.execute(
            "SELECT relname FROM pg_class WHERE relname = '" + testTable + "'"
        );
        System.out.println("Before DROP - pg_class rows: " + beforeResp.getRows());
        assertNull(beforeResp.getError(), "Query should succeed");
        assertEquals(1, beforeResp.getRows().size(), "Table should be in pg_class");
        
        // Drop table
        QueryResponse dropResp = queryService.execute("DROP TABLE " + testTable);
        System.out.println("DROP response: error=" + dropResp.getError());
        assertNull(dropResp.getError(), "DROP should succeed");
        
        // Give it time to process
        Thread.sleep(200);
        
        // Verify it's removed from pg_class
        QueryResponse afterResp = queryService.execute(
            "SELECT relname FROM pg_class WHERE relname = '" + testTable + "'"
        );
        System.out.println("After DROP - pg_class rows: " + afterResp.getRows());
        System.out.println("After DROP - error: " + afterResp.getError());
        
        assertNull(afterResp.getError(), "Query should succeed");
        assertEquals(0, afterResp.getRows().size(), "Table should be removed from pg_class");
        
        System.out.println("✅ DROP TABLE correctly removes from pg_class");
    }
}

