package com.geico.poc.cassandrasql.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralized configuration for Cassandra keyspaces and table names.
 * 
 * This ensures consistency across the codebase and allows configuration-driven
 * keyspace names for different environments (dev, test, prod).
 */
@Component
public class KeyspaceConfig {
    
    // ========================================
    // User Database Keyspace (configurable)
    // ========================================
    
    @Value("${cassandra-sql.keyspace:cassandra_sql}")
    private String defaultKeyspace;
    
    /**
     * Get the default user database keyspace name.
     * This is the keyspace where user tables are stored.
     * 
     * Default: "cassandra_sql"
     * Configurable via: cassandra-sql.keyspace
     */
    public String getDefaultKeyspace() {
        return defaultKeyspace;
    }
    
    // ========================================
    // Internal Keyspace (constant)
    // ========================================
    
    /**
     * Internal keyspace for SQL layer metadata.
     * This stores cluster metadata, timestamp oracle, leader locks, etc.
     * 
     * This is intentionally NOT configurable to ensure consistency
     * across all instances of the SQL layer.
     */
    public static final String INTERNAL_KEYSPACE = "cassandra_sql_internal";
    
    /**
     * PostgreSQL catalog keyspace.
     * This stores pg_catalog tables (pg_class, pg_namespace, pg_attribute, etc.)
     * in the KV store format, separate from user tables.
     * 
     * This is intentionally NOT configurable to ensure consistency.
     */
    public static final String PG_CATALOG_KEYSPACE = "pg_catalog";
    
    /**
     * Get the internal keyspace name.
     */
    public String getInternalKeyspace() {
        return INTERNAL_KEYSPACE;
    }
    
    /**
     * Get the PostgreSQL catalog keyspace name.
     */
    public String getPgCatalogKeyspace() {
        return PG_CATALOG_KEYSPACE;
    }
    
    // ========================================
    // Table Names (constants)
    // ========================================
    
    // KV Store Tables (per-database keyspace)
    public static final String TABLE_KV_STORE = "kv_store";
    public static final String TABLE_TX_LOCKS = "tx_locks";
    public static final String TABLE_TX_WRITES = "tx_writes";
    
    // Internal Tables (internal keyspace)
    public static final String TABLE_DATABASES = "databases";
    public static final String TABLE_SCHEMAS = "schemas";
    public static final String TABLE_TABLES = "tables";
    public static final String TABLE_TIMESTAMP_ORACLE = "timestamp_oracle";
    public static final String TABLE_LEADER_LOCKS = "leader_locks";
    public static final String TABLE_PG_TABLES = "pg_tables";
    public static final String TABLE_INFORMATION_SCHEMA_TABLES = "information_schema_tables";
    public static final String TABLE_INFORMATION_SCHEMA_COLUMNS = "information_schema_columns";
    
    // ========================================
    // Convenience Methods
    // ========================================
    
    /**
     * Get fully qualified table name for KV store.
     * Format: keyspace.table_name
     */
    public String getKvStoreTable() {
        return defaultKeyspace + "." + TABLE_KV_STORE;
    }
    
    /**
     * Get fully qualified table name for transaction locks.
     */
    public String getTxLocksTable() {
        return defaultKeyspace + "." + TABLE_TX_LOCKS;
    }
    
    /**
     * Get fully qualified table name for transaction writes.
     */
    public String getTxWritesTable() {
        return defaultKeyspace + "." + TABLE_TX_WRITES;
    }
    
    /**
     * Get fully qualified table name for pg_tables (internal).
     */
    public String getPgTablesTable() {
        return INTERNAL_KEYSPACE + "." + TABLE_PG_TABLES;
    }
    
    /**
     * Get fully qualified table name for information_schema.tables (internal).
     */
    public String getInformationSchemaTablesTable() {
        return INTERNAL_KEYSPACE + "." + TABLE_INFORMATION_SCHEMA_TABLES;
    }
    
    /**
     * Get fully qualified table name for information_schema.columns (internal).
     */
    public String getInformationSchemaColumnsTable() {
        return INTERNAL_KEYSPACE + "." + TABLE_INFORMATION_SCHEMA_COLUMNS;
    }
    
    /**
     * Get fully qualified table name for timestamp oracle (internal).
     */
    public String getTimestampOracleTable() {
        return INTERNAL_KEYSPACE + "." + TABLE_TIMESTAMP_ORACLE;
    }
    
    /**
     * Get fully qualified table name for databases registry (internal).
     */
    public String getDatabasesTable() {
        return INTERNAL_KEYSPACE + "." + TABLE_DATABASES;
    }
    
    /**
     * Get fully qualified table name for schemas registry (internal).
     */
    public String getSchemasTable() {
        return INTERNAL_KEYSPACE + "." + TABLE_SCHEMAS;
    }
    
    /**
     * Get fully qualified table name for tables registry (internal).
     */
    public String getTablesTable() {
        return INTERNAL_KEYSPACE + "." + TABLE_TABLES;
    }
    
    /**
     * Get fully qualified table name for leader locks (internal).
     */
    public String getLeaderLocksTable() {
        return INTERNAL_KEYSPACE + "." + TABLE_LEADER_LOCKS;
    }
    
    /**
     * Get fully qualified table name for a specific table in a keyspace.
     */
    public String getQualifiedTableName(String keyspace, String tableName) {
        return keyspace + "." + tableName;
    }
    
    // ========================================
    // Keyspace Naming Utilities
    // ========================================
    
    /**
     * Convert PostgreSQL database name to Cassandra keyspace name.
     * 
     * Rules:
     * - Prefix with "pg_"
     * - Replace hyphens with underscores
     * - Convert to lowercase
     * - Validate against Cassandra naming rules
     * 
     * Examples:
     * - "production" → "pg_production"
     * - "my-app" → "pg_my_app"
     * - "Test_DB" → "pg_test_db"
     */
    public static String databaseNameToKeyspace(String databaseName) {
        if (databaseName == null || databaseName.isEmpty()) {
            throw new IllegalArgumentException("Database name cannot be null or empty");
        }
        
        // Sanitize: lowercase, replace hyphens with underscores
        String sanitized = databaseName.toLowerCase()
            .replace('-', '_')
            .replace(' ', '_');
        
        // Add prefix
        String keyspace = "pg_" + sanitized;
        
        // Validate Cassandra keyspace naming rules
        if (!keyspace.matches("^[a-z][a-z0-9_]*$")) {
            throw new IllegalArgumentException(
                "Invalid database name '" + databaseName + "': " +
                "must start with a letter and contain only letters, numbers, and underscores"
            );
        }
        
        if (keyspace.length() > 48) {
            throw new IllegalArgumentException(
                "Database name '" + databaseName + "' is too long: " +
                "keyspace name '" + keyspace + "' exceeds 48 characters"
            );
        }
        
        return keyspace;
    }
    
    /**
     * Extract PostgreSQL database name from Cassandra keyspace name.
     * 
     * Examples:
     * - "pg_production" → "production"
     * - "pg_my_app" → "my_app"
     */
    public static String keyspaceToDatabaseName(String keyspace) {
        if (keyspace == null || !keyspace.startsWith("pg_")) {
            throw new IllegalArgumentException(
                "Invalid keyspace name '" + keyspace + "': must start with 'pg_'"
            );
        }
        return keyspace.substring(3);  // Remove "pg_" prefix
    }
}

