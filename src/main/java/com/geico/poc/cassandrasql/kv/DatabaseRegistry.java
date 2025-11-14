package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry for PostgreSQL databases.
 * 
 * Manages the mapping between PostgreSQL databases and Cassandra keyspaces.
 * Each database gets its own keyspace with configurable replication strategy.
 */
@Component
public class DatabaseRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseRegistry.class);
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    // Cache: DatabaseID → DatabaseMetadata
    private final Map<Integer, DatabaseMetadata> databaseCache = new ConcurrentHashMap<>();
    
    // Cache: DatabaseName → DatabaseID
    private final Map<String, Integer> nameToIdCache = new ConcurrentHashMap<>();
    
    // Cache: Keyspace → DatabaseID
    private final Map<String, Integer> keyspaceToIdCache = new ConcurrentHashMap<>();
    
    // Next database ID (auto-increment)
    private final AtomicInteger nextDatabaseId = new AtomicInteger(1);
    
    // Prepared statements
    private PreparedStatement insertDatabaseStmt;
    private PreparedStatement selectDatabaseByIdStmt;
    private PreparedStatement selectDatabaseByNameStmt;
    private PreparedStatement selectAllDatabasesStmt;
    private PreparedStatement deleteDatabaseStmt;
    
    @PostConstruct
    public void init() {
        logger.info("Initializing DatabaseRegistry");
        
        // Prepare statements
        String databasesTable = keyspaceConfig.getDatabasesTable();
        
        insertDatabaseStmt = session.prepare(
            String.format(
                "INSERT INTO %s (database_id, database_name, cassandra_keyspace, " +
                "replication_config, created_at, owner) VALUES (?, ?, ?, ?, ?, ?)",
                databasesTable
            )
        );
        
        selectDatabaseByIdStmt = session.prepare(
            String.format(
                "SELECT database_id, database_name, cassandra_keyspace, replication_config, " +
                "created_at, owner FROM %s WHERE database_id = ?",
                databasesTable
            )
        );
        
        selectDatabaseByNameStmt = session.prepare(
            String.format(
                "SELECT database_id, database_name, cassandra_keyspace, replication_config, " +
                "created_at, owner FROM %s WHERE database_name = ? ALLOW FILTERING",
                databasesTable
            )
        );
        
        selectAllDatabasesStmt = session.prepare(
            String.format(
                "SELECT database_id, database_name, cassandra_keyspace, replication_config, " +
                "created_at, owner FROM %s",
                databasesTable
            )
        );
        
        deleteDatabaseStmt = session.prepare(
            String.format("DELETE FROM %s WHERE database_id = ?", databasesTable)
        );
        
        // Load all databases into cache
        loadAllDatabases();
        
        logger.info("DatabaseRegistry initialized with {} databases", databaseCache.size());
    }
    
    /**
     * Create a new database.
     * 
     * @param databaseName PostgreSQL database name
     * @param replicationConfig Replication configuration
     * @param owner Database owner (username)
     * @return DatabaseMetadata for the created database
     */
    public DatabaseMetadata createDatabase(
            String databaseName,
            ReplicationConfig replicationConfig,
            String owner) {
        
        // Validate database name
        if (databaseName == null || databaseName.isEmpty()) {
            throw new IllegalArgumentException("Database name cannot be null or empty");
        }
        
        // Check if database already exists
        if (nameToIdCache.containsKey(databaseName)) {
            throw new IllegalArgumentException("Database '" + databaseName + "' already exists");
        }
        
        // Generate keyspace name
        String cassandraKeyspace = KeyspaceConfig.databaseNameToKeyspace(databaseName);
        
        // Check if keyspace already exists
        if (keyspaceToIdCache.containsKey(cassandraKeyspace)) {
            throw new IllegalArgumentException(
                "Keyspace '" + cassandraKeyspace + "' already exists"
            );
        }
        
        // Allocate database ID
        int databaseId = nextDatabaseId.getAndIncrement();
        
        // Create metadata
        DatabaseMetadata metadata = DatabaseMetadata.builder()
            .databaseId(databaseId)
            .databaseName(databaseName)
            .cassandraKeyspace(cassandraKeyspace)
            .replicationConfig(replicationConfig)
            .createdAt(Instant.now())
            .owner(owner)
            .build();
        
        // Insert into Cassandra
        session.execute(
            insertDatabaseStmt.bind(
                databaseId,
                databaseName,
                cassandraKeyspace,
                replicationConfig.toJson(),
                metadata.getCreatedAt(),
                owner
            )
        );
        
        // Update caches
        databaseCache.put(databaseId, metadata);
        nameToIdCache.put(databaseName, databaseId);
        keyspaceToIdCache.put(cassandraKeyspace, databaseId);
        
        logger.info("Created database: {} (ID={}, keyspace={})", 
            databaseName, databaseId, cassandraKeyspace);
        
        return metadata;
    }
    
    /**
     * Get database by ID.
     */
    public Optional<DatabaseMetadata> getDatabaseById(int databaseId) {
        // Check cache first
        DatabaseMetadata cached = databaseCache.get(databaseId);
        if (cached != null) {
            return Optional.of(cached);
        }
        
        // Query Cassandra
        ResultSet rs = session.execute(selectDatabaseByIdStmt.bind(databaseId));
        Row row = rs.one();
        
        if (row == null) {
            return Optional.empty();
        }
        
        DatabaseMetadata metadata = rowToMetadata(row);
        
        // Update cache
        databaseCache.put(databaseId, metadata);
        nameToIdCache.put(metadata.getDatabaseName(), databaseId);
        keyspaceToIdCache.put(metadata.getCassandraKeyspace(), databaseId);
        
        return Optional.of(metadata);
    }
    
    /**
     * Get database by name.
     */
    public Optional<DatabaseMetadata> getDatabaseByName(String databaseName) {
        // Check cache first
        Integer databaseId = nameToIdCache.get(databaseName);
        if (databaseId != null) {
            return getDatabaseById(databaseId);
        }
        
        // Query Cassandra
        ResultSet rs = session.execute(selectDatabaseByNameStmt.bind(databaseName));
        Row row = rs.one();
        
        if (row == null) {
            return Optional.empty();
        }
        
        DatabaseMetadata metadata = rowToMetadata(row);
        
        // Update cache
        databaseCache.put(metadata.getDatabaseId(), metadata);
        nameToIdCache.put(databaseName, metadata.getDatabaseId());
        keyspaceToIdCache.put(metadata.getCassandraKeyspace(), metadata.getDatabaseId());
        
        return Optional.of(metadata);
    }
    
    /**
     * Get database by Cassandra keyspace name.
     */
    public Optional<DatabaseMetadata> getDatabaseByKeyspace(String keyspace) {
        // Check cache first
        Integer databaseId = keyspaceToIdCache.get(keyspace);
        if (databaseId != null) {
            return getDatabaseById(databaseId);
        }
        
        // Keyspace not found
        return Optional.empty();
    }
    
    /**
     * List all databases.
     */
    public List<DatabaseMetadata> listDatabases() {
        return new ArrayList<>(databaseCache.values());
    }
    
    /**
     * Delete a database.
     * 
     * Note: This only removes the database from the registry.
     * The actual Cassandra keyspace must be dropped separately.
     */
    public void deleteDatabase(int databaseId) {
        DatabaseMetadata metadata = databaseCache.get(databaseId);
        if (metadata == null) {
            throw new IllegalArgumentException("Database ID " + databaseId + " not found");
        }
        
        // Delete from Cassandra
        session.execute(deleteDatabaseStmt.bind(databaseId));
        
        // Remove from caches
        databaseCache.remove(databaseId);
        nameToIdCache.remove(metadata.getDatabaseName());
        keyspaceToIdCache.remove(metadata.getCassandraKeyspace());
        
        logger.info("Deleted database: {} (ID={})", metadata.getDatabaseName(), databaseId);
    }
    
    /**
     * Invalidate cache for a specific database.
     */
    public void invalidateCache(int databaseId) {
        DatabaseMetadata metadata = databaseCache.remove(databaseId);
        if (metadata != null) {
            nameToIdCache.remove(metadata.getDatabaseName());
            keyspaceToIdCache.remove(metadata.getCassandraKeyspace());
        }
    }
    
    /**
     * Reload all databases from Cassandra.
     */
    public void reloadAll() {
        databaseCache.clear();
        nameToIdCache.clear();
        keyspaceToIdCache.clear();
        loadAllDatabases();
        logger.info("Reloaded all databases: {} total", databaseCache.size());
    }
    
    // ========================================
    // Private Helper Methods
    // ========================================
    
    private void loadAllDatabases() {
        try {
            ResultSet rs = session.execute(selectAllDatabasesStmt.bind());
            
            int maxId = 0;
            for (Row row : rs) {
                DatabaseMetadata metadata = rowToMetadata(row);
                int databaseId = metadata.getDatabaseId();
                
                databaseCache.put(databaseId, metadata);
                nameToIdCache.put(metadata.getDatabaseName(), databaseId);
                keyspaceToIdCache.put(metadata.getCassandraKeyspace(), databaseId);
                
                if (databaseId > maxId) {
                    maxId = databaseId;
                }
            }
            
            // Set next database ID
            nextDatabaseId.set(maxId + 1);
            
        } catch (Exception e) {
            logger.warn("Failed to load databases from registry (table may not exist yet): {}", 
                e.getMessage());
            // This is expected during initial setup
        }
    }
    
    private DatabaseMetadata rowToMetadata(Row row) {
        int databaseId = row.getInt("database_id");
        String databaseName = row.getString("database_name");
        String cassandraKeyspace = row.getString("cassandra_keyspace");
        String replicationConfigJson = row.getString("replication_config");
        Instant createdAt = row.getInstant("created_at");
        String owner = row.getString("owner");
        
        ReplicationConfig replicationConfig = ReplicationConfig.fromJson(replicationConfigJson);
        
        return DatabaseMetadata.builder()
            .databaseId(databaseId)
            .databaseName(databaseName)
            .cassandraKeyspace(cassandraKeyspace)
            .replicationConfig(replicationConfig)
            .createdAt(createdAt)
            .owner(owner)
            .build();
    }
}

