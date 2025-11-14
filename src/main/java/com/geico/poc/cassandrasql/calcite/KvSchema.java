package com.geico.poc.cassandrasql.calcite;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Calcite schema adapter for KV storage mode.
 * Bridges between Calcite's schema model and our SchemaManager.
 */
public class KvSchema extends AbstractSchema {
    
    private static final Logger log = LoggerFactory.getLogger(KvSchema.class);
    
    private final SchemaManager schemaManager;
    private Map<String, Table> tableMap;
    
    public KvSchema(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
        this.tableMap = new HashMap<>();
        refreshTables();
    }
    
    @Override
    protected Map<String, Table> getTableMap() {
        // Always refresh from SchemaManager to ensure we have the latest tables
        // This is important because KvPlanner is initialized before SchemaManager loads its schema
        refreshTables();
        return tableMap;
    }
    
    /**
     * Refresh the table map from SchemaManager.
     * Call this when tables are added/dropped.
     */
    public void refreshTables() {
        Map<String, Table> tempMap = new HashMap<>();
        
        // Get all tables from SchemaManager
        java.util.Collection<TableMetadata> tables = schemaManager.getAllTables();
        
        log.debug("SchemaManager returned {} tables", tables.size());
        
        int catalogTableCount = 0;
        for (TableMetadata metadata : tables) {
            String tableName = metadata.getTableName();
            long tableId = metadata.getTableId();
            
            log.debug("  Table: {} (id={})", tableName, tableId);
            
            KvTable kvTable = new KvTable(metadata, schemaManager);
            // Register in uppercase to match Calcite's case-insensitive behavior
            // (Calcite converts identifiers to uppercase when caseSensitive=false)
            tempMap.put(tableName.toUpperCase(), kvTable);
            
            if (tableId < 0) {
                catalogTableCount++;
                log.debug("  ✅ Registered catalog table in Calcite schema: {} (id={})", tableName, tableId);
            } else {
                log.info("  ✅ Registered table in Calcite schema: {} (id={})", tableName, tableId);
            }
        }
        
        // Swap
        tableMap = tempMap;

        log.info("Refreshed Calcite schema with {} tables ({} user tables, {} catalog tables)", 
            tableMap.size(), tableMap.size() - catalogTableCount, catalogTableCount);
        
        if (tableMap.isEmpty()) {
            log.warn("⚠️  No tables found in schema! Available tables from SchemaManager: {}", 
                schemaManager.getAllTables().stream()
                    .map(t -> t.getTableName() + "(id=" + t.getTableId() + ")")
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
    }
    
    /**
     * Get the underlying SchemaManager
     */
    public SchemaManager getSchemaManager() {
        return schemaManager;
    }
}

