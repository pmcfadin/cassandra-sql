package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify catalog write and read flow
 */
public class CatalogWriteReadTest extends KvTestBase {
    
    @Autowired
    private PgCatalogManager pgCatalogManager;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Test
    public void testDirectCatalogWrite() throws Exception {
        System.out.println("\n=== Direct Catalog Write Test ===\n");
        
        // Ensure catalog is initialized
        pgCatalogManager.ensureInitialized();
        
        // Get pg_class metadata
        TableMetadata pgClass = schemaManager.getTable("pg_class");
        System.out.println("pg_class table: " + (pgClass != null ? "EXISTS (id=" + pgClass.getTableId() + ")" : "NULL"));
        assertNotNull(pgClass, "pg_class should exist");
        
        // Create a test table
        String tableName = uniqueTableName("direct_test");
        System.out.println("\nCreating table via SchemaManager: " + tableName);
        
        TableMetadata testTable = schemaManager.createTable(
            tableName,
            java.util.List.of(
                new TableMetadata.ColumnMetadata("id", "INT", false, false, null),
                new TableMetadata.ColumnMetadata("name", "TEXT", true, false, null)
            ),
            java.util.List.of("id")
        );
        
        System.out.println("Table created: id=" + testTable.getTableId());
        
        // Now query pg_class using KvQueryExecutor directly
        System.out.println("\nQuerying pg_class via SQL...");
        QueryResponse resp = queryService.execute(
            "SELECT oid, relname, relkind FROM pg_class WHERE relname = '" + tableName + "'"
        );
        
        System.out.println("Query result: " + (resp.getError() == null ? "SUCCESS" : "ERROR: " + resp.getError()));
        System.out.println("Rows returned: " + (resp.getRows() != null ? resp.getRows().size() : 0));
        
        if (resp.getRows() != null && !resp.getRows().isEmpty()) {
            System.out.println("Found in pg_class:");
            for (var row : resp.getRows()) {
                System.out.println("  oid=" + row.get("oid") + ", relname=" + row.get("relname") + ", relkind=" + row.get("relkind"));
            }
        } else {
            System.out.println("❌ Table NOT found in pg_class!");
            
            // Try to scan the KV store directly for pg_class data
            System.out.println("\nScanning KV store for pg_class entries...");
            byte[] startKey = KeyEncoder.createRangeStartKey(pgClass.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(pgClass.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            long readTs = timestampOracle.allocateStartTimestamp();
            var entries = kvStore.scan(startKey, endKey, readTs, 100, 0L);
            
            System.out.println("KV entries found: " + entries.size());
            if (entries.isEmpty()) {
                System.out.println("❌ No data in KV store for pg_class!");
            } else {
                System.out.println("✅ Found " + entries.size() + " entries in KV store");
                // Try to decode first entry
                if (!entries.isEmpty()) {
                    var entry = entries.get(0);
                    System.out.println("First entry key length: " + entry.getKey().length);
                    System.out.println("First entry value length: " + (entry.getValue() != null ? entry.getValue().length : 0));
                }
            }
        }
        
        assertNotNull(resp.getRows(), "Should have rows");
        assertFalse(resp.getRows().isEmpty(), "Should find table in pg_class");
    }
}

