package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to check catalog setup
 */
public class CatalogDebugTest extends KvTestBase {
    
    @Autowired(required = false)
    private PgCatalogManager pgCatalogManager;
    
    @Test
    public void testCatalogManagerExists() throws Exception {
        System.out.println("\n=== Catalog Manager Debug ===\n");
        System.out.println("PgCatalogManager: " + (pgCatalogManager != null ? "EXISTS" : "NULL"));
        System.out.println("SchemaManager: " + (schemaManager != null ? "EXISTS" : "NULL"));
        
        assertNotNull(pgCatalogManager, "PgCatalogManager should be injected");
        
        // Check if pg_namespace is registered
        System.out.println("\nChecking if pg_namespace is registered...");
        TableMetadata pgNamespace = schemaManager.getTable("pg_namespace");
        System.out.println("pg_namespace table: " + (pgNamespace != null ? "EXISTS (id=" + pgNamespace.getTableId() + ")" : "NULL"));
        
        // Manually call ensureInitialized
        System.out.println("\nCalling pgCatalogManager.ensureInitialized()...");
        pgCatalogManager.ensureInitialized();
        System.out.println("Done");
        
        // Check again
        pgNamespace = schemaManager.getTable("pg_namespace");
        System.out.println("pg_namespace after init: " + (pgNamespace != null ? "EXISTS (id=" + pgNamespace.getTableId() + ")" : "NULL"));
        
        // Try to create a table
        String tableName = uniqueTableName("debug");
        System.out.println("\nCreating table: " + tableName);
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + tableName + " (id INT PRIMARY KEY)"
        );
        System.out.println("Create result: " + (createResp.getError() == null ? "SUCCESS" : "FAILED: " + createResp.getError()));
        
        // Try to query pg_catalog keyspace directly
        System.out.println("\nQuerying pg_catalog.kv_store directly...");
        QueryResponse directResp = queryService.execute(
            "SELECT key FROM pg_catalog.kv_store LIMIT 10"
        );
        System.out.println("Direct query result: " + (directResp.getError() == null ? 
            "SUCCESS (" + (directResp.getRows() != null ? directResp.getRows().size() : 0) + " rows)" : 
            "FAILED: " + directResp.getError()));
        
        // Try to query pg_class
        System.out.println("\nQuerying pg_class...");
        QueryResponse pgClassResp = queryService.execute(
            "SELECT relname FROM pg_class LIMIT 10"
        );
        System.out.println("pg_class query result: " + (pgClassResp.getError() == null ? 
            "SUCCESS (" + (pgClassResp.getRows() != null ? pgClassResp.getRows().size() : 0) + " rows)" : 
            "FAILED: " + pgClassResp.getError()));
        
        if (pgClassResp.getRows() != null && !pgClassResp.getRows().isEmpty()) {
            System.out.println("  Tables in pg_class:");
            for (Map<String, Object> row : pgClassResp.getRows()) {
                System.out.println("    - " + row.get("relname"));
            }
        }
        
        // Try to query pg_class with explicit table name
        System.out.println("\nQuerying pg_class for our table...");
        QueryResponse specificResp = queryService.execute(
            "SELECT relname, relkind FROM pg_class WHERE relname = '" + tableName + "'"
        );
        System.out.println("Specific query result: " + (specificResp.getError() == null ? 
            "SUCCESS (" + (specificResp.getRows() != null ? specificResp.getRows().size() : 0) + " rows)" : 
            "FAILED: " + specificResp.getError()));
    }
}

