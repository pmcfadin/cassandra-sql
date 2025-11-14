package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Manages the cassandra_sql_internal keyspace which stores all SQL layer internals.
 * 
 * This keyspace is completely isolated from user databases and contains:
 * - Cluster metadata (databases, schemas, tables)
 * - Timestamp oracle state
 * - Leader election locks
 * - PostgreSQL catalog tables
 * 
 * This ensures users cannot accidentally or maliciously interfere with SQL layer internals.
 */
@Component
public class InternalKeyspaceManager {
    
    private static final Logger log = LoggerFactory.getLogger(InternalKeyspaceManager.class);
    
    public static final String INTERNAL_KEYSPACE = "cassandra_sql_internal";
    
    @Autowired
    private CqlSession session;
    
    @PostConstruct
    public void initialize() {
        log.info("🔧 Initializing internal keyspace: " + INTERNAL_KEYSPACE);
        
        try {
            createInternalKeyspace();
            createMetadataTables();
            createTimestampOracleTable();
            createLeaderElectionTables();
            createPostgresCatalogTables();
            
            log.info("✅ Internal keyspace initialized successfully");
        } catch (Exception e) {
            log.error("❌ Failed to initialize internal keyspace: " + e.getMessage());
            throw new RuntimeException("Failed to initialize internal keyspace", e);
        }
    }
    
    /**
     * Create the internal keyspace with high replication
     */
    private void createInternalKeyspace() {
        String cql = String.format(
            "CREATE KEYSPACE IF NOT EXISTS %s WITH replication = {" +
            "  'class': 'NetworkTopologyStrategy'," +
            "  'datacenter1': 1" +
            "} AND durable_writes = true",
            INTERNAL_KEYSPACE
        );
        
        session.execute(cql);
        log.info("  ✓ Created keyspace: " + INTERNAL_KEYSPACE);
    }
    
    /**
     * Create cluster metadata tables
     */
    private void createMetadataTables() {
        // Database registry
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.databases (" +
            "  database_id INT PRIMARY KEY," +
            "  database_name TEXT," +
            "  cassandra_keyspace TEXT," +
            "  replication_config TEXT," +
            "  created_at TIMESTAMP," +
            "  owner TEXT" +
            ") WITH transactional_mode='full'",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: databases");
        
        // Schema registry
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.schemas (" +
            "  database_id INT," +
            "  schema_id INT," +
            "  schema_name TEXT," +
            "  created_at TIMESTAMP," +
            "  PRIMARY KEY (database_id, schema_id)" +
            ") WITH transactional_mode='full'",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: schemas");
        
        // Table registry
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.tables (" +
            "  database_id INT," +
            "  schema_id INT," +
            "  table_id BIGINT," +
            "  table_name TEXT," +
            "  table_metadata TEXT," +
            "  created_at TIMESTAMP," +
            "  PRIMARY KEY ((database_id, schema_id), table_id)" +
            ") WITH transactional_mode='full'",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: tables");
    }
    
    /**
     * Create timestamp oracle table
     */
    private void createTimestampOracleTable() {
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.timestamp_oracle (" +
            "  oracle_id TEXT PRIMARY KEY," +
            "  current_timestamp BIGINT," +
            "  last_allocated_batch BIGINT," +
            "  batch_size INT" +
            ") WITH transactional_mode='full'",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: timestamp_oracle");
        
        // Initialize the global oracle if it doesn't exist
        session.execute(String.format(
            "INSERT INTO %s.timestamp_oracle (oracle_id, current_timestamp, last_allocated_batch, batch_size) " +
            "VALUES ('global', 0, 0, 1000) IF NOT EXISTS",
            INTERNAL_KEYSPACE
        ));
    }
    
    /**
     * Create leader election tables
     */
    private void createLeaderElectionTables() {
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.leader_locks (" +
            "  role TEXT PRIMARY KEY," +
            "  instance_id TEXT," +
            "  acquired_at TIMESTAMP," +
            "  heartbeat_at TIMESTAMP," +
            "  lease_expires_at TIMESTAMP" +
            ") WITH transactional_mode='full'",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: leader_locks");
    }
    
    /**
     * Create PostgreSQL catalog tables for \dt, \d, etc.
     */
    private void createPostgresCatalogTables() {
        // pg_namespace - PostgreSQL schema/namespace catalog
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.pg_namespace (" +
            "  oid BIGINT PRIMARY KEY," +
            "  nspname TEXT," +
            "  nspowner BIGINT" +
            ")",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: pg_namespace");
        
        // Insert default 'public' schema
        session.execute(String.format(
            "INSERT INTO %s.pg_namespace (oid, nspname, nspowner) VALUES (2200, 'public', 10) IF NOT EXISTS",
            INTERNAL_KEYSPACE
        ));
        
        // pg_class - PostgreSQL table/relation catalog
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.pg_class (" +
            "  oid BIGINT PRIMARY KEY," +
            "  relname TEXT," +
            "  relnamespace BIGINT," +  // FK to pg_namespace.oid
            "  relkind TEXT," +  // 'r' = ordinary table, 'i' = index, 'v' = view
            "  relowner BIGINT," +
            "  relhasindex BOOLEAN" +
            ")",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: pg_class");
        
        // pg_tables - Simplified view of tables
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.pg_tables (" +
            "  schemaname TEXT," +
            "  tablename TEXT," +
            "  tableowner TEXT," +
            "  PRIMARY KEY (schemaname, tablename)" +
            ")",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: pg_tables");
        
        // information_schema_tables
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.information_schema_tables (" +
            "  table_schema TEXT," +
            "  table_name TEXT," +
            "  table_type TEXT," +
            "  PRIMARY KEY (table_schema, table_name)" +
            ")",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: information_schema_tables");
        
        // information_schema_columns
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.information_schema_columns (" +
            "  table_schema TEXT," +
            "  table_name TEXT," +
            "  column_name TEXT," +
            "  ordinal_position INT," +
            "  data_type TEXT," +
            "  is_nullable TEXT," +
            "  PRIMARY KEY ((table_schema, table_name), ordinal_position)" +
            ")",
            INTERNAL_KEYSPACE
        ));
        log.info("  ✓ Created table: information_schema_columns");
    }
    
    /**
     * Get the internal keyspace name
     */
    public String getInternalKeyspace() {
        return INTERNAL_KEYSPACE;
    }
}

