package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for KV mode tests with proper isolation and cleanup.
 * 
 * Features:
 * - Automatic cleanup of test tables
 * - Schema cache refresh between tests
 * - Unique table name generation
 * - Proper timing for schema propagation
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=KV",
    "logging.level.org.cassandrasql=WARN"  // Reduce noise in test output
})
public abstract class KvTestBase {
    
    @Autowired
    protected QueryService queryService;
    
    @Autowired
    protected SchemaManager schemaManager;
    
    // Track tables created during test for cleanup
    private final List<String> createdTables = new ArrayList<>();
    
    /**
     * Generate a unique table name for this test
     */
    public String uniqueTableName(String prefix) {
        String tableName = prefix + "_" + System.nanoTime();
        createdTables.add(tableName);
        return tableName;
    }
    
    /**
     * Register a table for cleanup (if not using uniqueTableName)
     */
    protected void registerTable(String tableName) {
        createdTables.add(tableName);
    }
    
    @BeforeEach
    public void baseSetup() throws Exception {
        // Clear any tables from previous tests
        createdTables.clear();
        
        // Refresh schema cache to ensure clean state
        schemaManager.refreshCache();
        
        // Small delay for schema propagation
        Thread.sleep(50);
    }
    
    @AfterEach
    public void baseCleanup() throws Exception {
        // Rollback any active transactions
        try {
            String connId = testConnectionId();
            queryService.execute("ROLLBACK", connId);
        } catch (Exception e) {
            // Ignore - no active transaction
        }
        
        // Drop all tables created during this test
        for (String tableName : createdTables) {
            try {
                queryService.execute("DROP TABLE IF EXISTS " + tableName);
            } catch (Exception e) {
                // Ignore - table may not exist
            }
        }
        
        // Refresh schema cache after cleanup
        if (!createdTables.isEmpty()) {
            Thread.sleep(150);  // Increased delay for cleanup propagation
            schemaManager.refreshCache();
        }
        
        createdTables.clear();
    }
    
    /**
     * Helper to create a test connection ID
     */
    protected String testConnectionId() {
        return "test-conn-" + System.nanoTime();
    }
}

