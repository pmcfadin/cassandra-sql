package com.geico.poc.cassandrasql.storage;

import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.dto.QueryResponse;

import java.util.List;

/**
 * Abstract storage backend interface.
 * Implementations provide different storage strategies (schema-based, KV-based, etc.)
 */
public interface StorageBackend {
    
    /**
     * Execute a parsed query
     */
    QueryResponse execute(ParsedQuery query) throws Exception;
    
    /**
     * Execute a query with connection context (for transactions)
     */
    QueryResponse execute(String connectionId, ParsedQuery query, String rawSql) throws Exception;
    
    /**
     * List all user-visible tables
     */
    List<String> listTables();
    
    /**
     * Check if a table exists
     */
    boolean tableExists(String tableName);
    
    /**
     * Check if a column exists in a table
     */
    boolean columnExists(String tableName, String columnName);
    
    /**
     * Get available table names for error messages
     */
    List<String> getAvailableTableNames();
    
    /**
     * Check if this backend requires special handling for catalog queries
     * (e.g., pg_tables, information_schema)
     */
    boolean shouldBypassForCatalogQuery(String sql);
    
    /**
     * Get the backend type name for logging
     */
    String getBackendType();
    
    /**
     * Initialize the backend (called on startup)
     */
    void initialize();
    
    /**
     * Shutdown the backend (called on shutdown)
     */
    void shutdown();
}

