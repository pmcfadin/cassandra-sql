package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;

/**
 * Manages database-level operations and metadata.
 * 
 * Responsibilities:
 * - Bootstrap default database on first startup
 * - Manage database registry in internal keyspace
 * - Provide database/schema ID allocation
 * - Track database-to-keyspace mappings
 * 
 * This component ensures the default database exists and is properly
 * registered in the cassandra_sql_internal.databases table.
 */
@Component
@DependsOn("internalKeyspaceManager")
public class DatabaseManager {
    
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private InternalKeyspaceManager internalKeyspaceManager;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    @Autowired
    private CassandraSqlConfig config;
    
    // Default database and schema IDs
    private static final int DEFAULT_DATABASE_ID = 1;
    private static final int DEFAULT_SCHEMA_ID = 1;  // "public" schema
    private static final String DEFAULT_DATABASE_NAME = "cassandra_sql";
    private static final String DEFAULT_SCHEMA_NAME = "public";
    
    @PostConstruct
    public void initialize() {
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV) {
            log.info("Initializing Database Manager...");
            try {
                bootstrapDefaultDatabase();
                log.info("Database Manager initialized");
            } catch (Exception e) {
                log.error("Database Manager initialization failed: {}", e.getMessage(), e);
                // Don't fail startup - database will be created lazily
            }
        }
    }
    
    /**
     * Bootstrap default database on first startup.
     * 
     * Creates:
     * - Database entry in cassandra_sql_internal.databases
     * - Default "public" schema in cassandra_sql_internal.schemas
     * - Cassandra keyspace for the database
     */
    private void bootstrapDefaultDatabase() {
        String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
        
        // Check if default database exists
        String checkQuery = String.format(
            "SELECT database_id FROM %s.databases WHERE database_id = ?",
            internalKeyspace
        );
        ResultSet rs = session.execute(
            SimpleStatement.builder(checkQuery)
                .addPositionalValue(DEFAULT_DATABASE_ID)
                .build()
        );
        
        if (rs.one() != null) {
            log.info("Default database already exists");
            // Ensure all tables exist (in case they were dropped or schema changed)
            String keyspaceName = keyspaceConfig.getDefaultKeyspace();
            createKvTables(keyspaceName);
            return;
        }
        
        // Create default database
        createDefaultDatabase();
    }
    
    /**
     * Create the default database and schema.
     */
    private void createDefaultDatabase() {
        String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
        String defaultKeyspace = keyspaceConfig.getDefaultKeyspace();
        
        log.info("Creating default database: {}", DEFAULT_DATABASE_NAME);
        
        // 1. Create Cassandra keyspace for the database (if it doesn't exist)
        createDatabaseKeyspace(defaultKeyspace);
        
        // 2. Create database entry in internal keyspace
        String insertDb = String.format(
            "INSERT INTO %s.databases (database_id, database_name, cassandra_keyspace, created_at, owner) " +
            "VALUES (?, ?, ?, ?, ?)",
            internalKeyspace
        );
        session.execute(
            SimpleStatement.builder(insertDb)
                .addPositionalValue(DEFAULT_DATABASE_ID)
                .addPositionalValue(DEFAULT_DATABASE_NAME)
                .addPositionalValue(defaultKeyspace)
                .addPositionalValue(Instant.now())
                .addPositionalValue("system")
                .build()
        );
        log.info("Created database entry: {} (ID={})", DEFAULT_DATABASE_NAME, DEFAULT_DATABASE_ID);
        
        // 3. Create default "public" schema
        String insertSchema = String.format(
            "INSERT INTO %s.schemas (database_id, schema_id, schema_name, created_at) " +
            "VALUES (?, ?, ?, ?)",
            internalKeyspace
        );
        session.execute(
            SimpleStatement.builder(insertSchema)
                .addPositionalValue(DEFAULT_DATABASE_ID)
                .addPositionalValue(DEFAULT_SCHEMA_ID)
                .addPositionalValue(DEFAULT_SCHEMA_NAME)
                .addPositionalValue(Instant.now())
                .build()
        );
        log.info("Created schema: {} (ID={})", DEFAULT_SCHEMA_NAME, DEFAULT_SCHEMA_ID);
    }
    
    /**
     * Create Cassandra keyspace for a database.
     */
    private void createDatabaseKeyspace(String keyspaceName) {
        // Check if keyspace already exists
        String checkQuery = "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?";
        ResultSet rs = session.execute(
            SimpleStatement.builder(checkQuery)
                .addPositionalValue(keyspaceName)
                .build()
        );
        
        if (rs.one() != null) {
            log.info("Keyspace already exists: {}", keyspaceName);
            // Ensure all tables exist (in case they were dropped or schema changed)
            createKvTables(keyspaceName);
            return;
        }
        
        // Create keyspace with NetworkTopologyStrategy
        String createKeyspace = String.format(
            "CREATE KEYSPACE IF NOT EXISTS %s WITH replication = {" +
            "  'class': 'NetworkTopologyStrategy'," +
            "  'datacenter1': 3" +
            "} AND durable_writes = true",
            keyspaceName
        );
        session.execute(createKeyspace);
        log.info("Created keyspace: {}", keyspaceName);
        
        // Create KV tables in the keyspace
        createKvTables(keyspaceName);
    }
    
    /**
     * Create KV store tables in a database keyspace.
     */
    private void createKvTables(String keyspaceName) {
        // Create kv_store table
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.%s (" +
            "  key BLOB," +
            "  ts BIGINT," +
            "  commit_ts BIGINT," +
            "  value BLOB," +
            "  deleted BOOLEAN," +
            "  tx_id UUID," +
            "  PRIMARY KEY (key, ts)" +
            ") WITH CLUSTERING ORDER BY (ts DESC)" +
            "  AND transactional_mode='full'",
            keyspaceName, KeyspaceConfig.TABLE_KV_STORE
        ));
        log.info("Created table: {}.{}", keyspaceName, KeyspaceConfig.TABLE_KV_STORE);
        
        // Create kv_locks table (for Percolator-style locks)
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.kv_locks (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID," +
            "  start_ts BIGINT," +
            "  primary_key BLOB," +
            "  lock_type TEXT," +
            "  write_type TEXT," +
            "  created_at TIMESTAMP" +
            ") WITH transactional_mode='full'",
            keyspaceName
        ));
        log.info("Created table: {}.kv_locks", keyspaceName);
        
        // Create tx_locks table
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.%s (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID," +
            "  ts BIGINT," +
            "  lock_type TEXT" +
            ") WITH transactional_mode='full'",
            keyspaceName, KeyspaceConfig.TABLE_TX_LOCKS
        ));
        log.info("Created table: {}.{}", keyspaceName, KeyspaceConfig.TABLE_TX_LOCKS);
        
        // Create kv_writes table (for Percolator commit records)
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.kv_writes (" +
            "  key BLOB," +
            "  commit_ts BIGINT," +
            "  start_ts BIGINT," +
            "  tx_id UUID," +
            "  write_type TEXT," +
            "  PRIMARY KEY (key, commit_ts)" +
            ") WITH CLUSTERING ORDER BY (commit_ts DESC)" +
            "  AND transactional_mode='full'",
            keyspaceName
        ));
        log.info("Created table: {}.kv_writes", keyspaceName);
        
        // Create tx_writes table
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.%s (" +
            "  tx_id UUID," +
            "  key BLOB," +
            "  value BLOB," +
            "  deleted BOOLEAN," +
            "  PRIMARY KEY (tx_id, key)" +
            ") WITH transactional_mode='full'",
            keyspaceName, KeyspaceConfig.TABLE_TX_WRITES
        ));
        log.info("Created table: {}.{}", keyspaceName, KeyspaceConfig.TABLE_TX_WRITES);
    }
    
    /**
     * Get the default database ID.
     */
    public int getDefaultDatabaseId() {
        return DEFAULT_DATABASE_ID;
    }
    
    /**
     * Get the default schema ID.
     */
    public int getDefaultSchemaId() {
        return DEFAULT_SCHEMA_ID;
    }
    
    /**
     * Get the default database name.
     */
    public String getDefaultDatabaseName() {
        return DEFAULT_DATABASE_NAME;
    }
    
    /**
     * Get the default schema name.
     */
    public String getDefaultSchemaName() {
        return DEFAULT_SCHEMA_NAME;
    }
    
    /**
     * Get the Cassandra keyspace for a database ID.
     */
    public String getDatabaseKeyspace(int databaseId) {
        if (databaseId == DEFAULT_DATABASE_ID) {
            return keyspaceConfig.getDefaultKeyspace();
        }
        
        // Query from internal keyspace
        String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
        String query = String.format(
            "SELECT cassandra_keyspace FROM %s.databases WHERE database_id = ?",
            internalKeyspace
        );
        ResultSet rs = session.execute(
            SimpleStatement.builder(query)
                .addPositionalValue(databaseId)
                .build()
        );
        
        Row row = rs.one();
        if (row == null) {
            throw new IllegalArgumentException("Database not found: " + databaseId);
        }
        
        return row.getString("cassandra_keyspace");
    }
}



