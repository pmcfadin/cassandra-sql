package com.geico.poc.cassandrasql.storage.kv;

import com.geico.poc.cassandrasql.kv.SchemaManager;

import java.util.List;

/**
 * Provides schema information for KV storage backend
 */
public class KvSchemaProvider {
    
    private final SchemaManager schemaManager;
    
    public KvSchemaProvider(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }
    
    public List<String> listTables() {
        return schemaManager.getAllTableNames();
    }
    
    public boolean tableExists(String tableName) {
        return schemaManager.getTable(tableName) != null;
    }
    
    public boolean columnExists(String tableName, String columnName) {
        com.geico.poc.cassandrasql.kv.TableMetadata table = schemaManager.getTable(tableName);
        if (table == null) {
            return false;
        }
        
        return table.getColumns().stream()
            .anyMatch(col -> col.getName().equalsIgnoreCase(columnName));
    }
    
    public List<String> getAvailableTableNames() {
        return schemaManager.getAllTableNames();
    }
}



