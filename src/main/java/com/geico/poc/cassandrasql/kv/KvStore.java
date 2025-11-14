package com.geico.poc.cassandrasql.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Key-Value store implementation on top of Cassandra.
 * 
 * Manages the underlying Cassandra tables and provides
 * basic put/get/scan operations with MVCC support.
 */
@Component
@DependsOn("databaseManager")  // Ensure DatabaseManager creates tables first
public class KvStore {
    
    private static final Logger log = LoggerFactory.getLogger(KvStore.class);
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    // Prepared statements (default keyspace)
    private PreparedStatement putStatement;
    private PreparedStatement getStatement;
    private PreparedStatement scanStatement;
    private PreparedStatement deleteStatement;
    
    // Prepared statements (pg_catalog keyspace)
    private PreparedStatement catalogScanStatement;
    private PreparedStatement catalogDeleteStatement;
    
    @PostConstruct
    public void initialize() {
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV) {
            log.debug("🔧 Initializing KV storage mode...");
            createTables();
            prepareCqlStatements();
            log.debug("✅ KV storage mode initialized");
        }
    }
    
    /**
     * Create KV storage tables
     */
    private void createTables() {
        // Check if tables exist and have correct schema
        migrateTableIfNeeded(KeyspaceConfig.TABLE_KV_STORE, 
            "key BLOB, ts BIGINT",  // Expected primary key columns
            "transactional_mode='full'");  // Expected options
        
        migrateTableIfNeeded(KeyspaceConfig.TABLE_TX_LOCKS,
            "key BLOB, tx_id UUID",  // Check for actual primary key columns
            "transactional_mode='full'");
        
        migrateTableIfNeeded(KeyspaceConfig.TABLE_TX_WRITES,
            "key BLOB, commit_ts BIGINT",  // Check for actual primary key columns
            "transactional_mode='full'");
        
        // Main KV store table with MVCC support
        // Uses compound primary key: (key, ts) to store multiple versions
        // ByteOrderedPartitioner allows ordered range scans
        // transactional_mode='full' enables Accord transactions
        String createKvStore = String.format(
            "CREATE TABLE IF NOT EXISTS %s.%s (" +
            "  key BLOB," +
            "  ts BIGINT," +
            "  value BLOB," +
            "  tx_id UUID," +
            "  commit_ts BIGINT," +
            "  deleted BOOLEAN," +
            "  PRIMARY KEY (key, ts)" +
            ") WITH CLUSTERING ORDER BY (ts DESC) AND transactional_mode='full'",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_KV_STORE
        );
        session.execute(createKvStore);
        log.debug("  ✅ Table ready: " + KeyspaceConfig.TABLE_KV_STORE + " (transactional_mode='full')");
        
        // Locks table
        String createLocks = String.format(
            "CREATE TABLE IF NOT EXISTS %s.%s (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID," +
            "  start_ts BIGINT," +
            "  primary_key BLOB," +
            "  lock_type TEXT," +
            "  write_type TEXT," +
            "  created_at TIMESTAMP" +
            ") WITH transactional_mode='full'",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_TX_LOCKS
        );
        session.execute(createLocks);
        log.debug("  ✅ Created table: " + KeyspaceConfig.TABLE_TX_LOCKS + " (transactional_mode='full')");
        
        // Writes table (for commit records)
        String createWrites = String.format(
            "CREATE TABLE IF NOT EXISTS %s.%s (" +
            "  key BLOB," +
            "  commit_ts BIGINT," +
            "  start_ts BIGINT," +
            "  tx_id UUID," +
            "  write_type TEXT," +
            "  PRIMARY KEY (key, commit_ts)" +
            ") WITH CLUSTERING ORDER BY (commit_ts DESC) AND transactional_mode='full'",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_TX_WRITES
        );
        session.execute(createWrites);
        log.debug("  ✅ Created table: " + KeyspaceConfig.TABLE_TX_WRITES + " (transactional_mode='full')");
    }
    
    /**
     * Migrate table if needed - only drop/recreate if primary key is wrong,
     * otherwise use ALTER for transactional_mode
     */
    private void migrateTableIfNeeded(String tableName, String expectedPrimaryKey, String expectedOptions) {
        try {
            // Try to query the table to see if it exists and has correct structure
            try {
                // Parse expectedPrimaryKey to extract column names
                // Format: "key BLOB, ts BIGINT" -> "key, ts"
                String[] parts = expectedPrimaryKey.split(",");
                StringBuilder columnNames = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    String columnName = parts[i].trim().split("\\s+")[0];
                    if (i > 0) columnNames.append(", ");
                    columnNames.append(columnName);
                }
                
                String validationQuery = String.format(
                    "SELECT %s FROM %s.%s LIMIT 1", columnNames.toString(), keyspaceConfig.getDefaultKeyspace(), tableName);
                log.debug("  🔍 Validating " + tableName + " with query: " + validationQuery);
                session.execute(validationQuery);
                // Table exists and has correct primary key columns
                log.debug("  ✅ Table " + tableName + " exists with correct schema");
                
                // Check and update transactional_mode if needed
                if (expectedOptions.contains("transactional_mode='full'")) {
                    try {
                        session.execute(String.format(
                            "ALTER TABLE %s.%s WITH transactional_mode='full'",
                            keyspaceConfig.getDefaultKeyspace(), tableName
                        ));
                        log.debug("  ✅ Updated transactional_mode for " + tableName);
                    } catch (Exception e) {
                        // Might already be set, that's fine
                        log.debug("  ℹ️  transactional_mode already set for " + tableName);
                    }
                }
            } catch (Exception e) {
                // Table doesn't exist or has wrong schema
                if (e.getMessage() != null && e.getMessage().contains("does not exist")) {
                    log.debug("  ℹ️  Table " + tableName + " does not exist, will be created");
                } else {
                    // Table exists but has wrong schema - drop it
                    log.debug("  🔄 Table " + tableName + " has incompatible schema, recreating");
                    log.debug("  ⚠️  Validation error: " + e.getMessage());
                    try {
                        session.execute(String.format("DROP TABLE %s.%s", keyspaceConfig.getDefaultKeyspace(), tableName));
                        log.debug("  ✅ Dropped old table " + tableName);
                    } catch (Exception dropEx) {
                        log.error("  ⚠️  Could not drop table: " + dropEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("  ⚠️  Error checking table " + tableName + ": " + e.getMessage());
        }
    }
    
    /**
     * Prepare CQL statements
     */
    private void prepareCqlStatements() {
        putStatement = session.prepare(String.format(
            "INSERT INTO %s.%s (key, value, ts, tx_id, commit_ts, deleted) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_KV_STORE
        ));
        
        getStatement = session.prepare(String.format(
            "SELECT key, value, ts, tx_id, commit_ts, deleted " +
            "FROM %s.%s WHERE key = ?",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_KV_STORE
        ));
        
        // For compound primary key (key, ts), we can query by key range
        // Cassandra will return all versions (ts values) for keys in the range
        // ALLOW FILTERING is needed because we're querying on partition key range
        scanStatement = session.prepare(String.format(
            "SELECT key, value, ts, tx_id, commit_ts, deleted " +
            "FROM %s.%s WHERE key >= ? AND key <= ? ALLOW FILTERING",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_KV_STORE
        ));
        
        // Note: catalogScanStatement is prepared lazily (see ensureCatalogScanStatement)
        // because pg_catalog keyspace might not exist yet during initialization
        
        deleteStatement = session.prepare(String.format(
            "DELETE FROM %s.%s WHERE key = ?",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_KV_STORE
        ));
    }
    
    /**
     * Ensure catalog scan statement is prepared (lazy initialization)
     */
    private void ensureCatalogScanStatement() {
        if (catalogScanStatement == null) {
            synchronized (this) {
                if (catalogScanStatement == null) {
                    catalogScanStatement = session.prepare(String.format(
                        "SELECT key, value, ts, tx_id, commit_ts, deleted " +
                        "FROM %s.%s WHERE key >= ? AND key <= ? ALLOW FILTERING",
                        keyspaceConfig.getPgCatalogKeyspace(), KeyspaceConfig.TABLE_KV_STORE
                    ));
                    log.debug("Prepared catalog scan statement for pg_catalog keyspace");
                }
            }
        }
    }
    
    /**
     * Lazily prepare catalog delete statement (for pg_catalog keyspace)
     */
    private void ensureCatalogDeleteStatement() {
        if (catalogDeleteStatement == null) {
            synchronized (this) {
                if (catalogDeleteStatement == null) {
                    catalogDeleteStatement = session.prepare(String.format(
                        "DELETE FROM %s.%s WHERE key = ?",
                        keyspaceConfig.getPgCatalogKeyspace(), KeyspaceConfig.TABLE_KV_STORE
                    ));
                    log.debug("Prepared catalog delete statement for pg_catalog keyspace");
                }
            }
        }
    }
    
    /**
     * Put a key-value pair (with MVCC)
     */
    public void put(byte[] key, byte[] value, long timestamp, UUID txId, Long commitTs, boolean deleted) {
        BoundStatement bound = putStatement.bind(
            ByteBuffer.wrap(key),
            value != null ? ByteBuffer.wrap(value) : null,
            timestamp,
            txId,
            commitTs,
            deleted
        );
        session.execute(bound);
    }
    
    /**
     * Conditional put (IF NOT EXISTS) - for schema operations with Accord
     * This satisfies the "conditional update" requirement for SERIAL consistency
     * 
     * @return true if the insert succeeded, false if the key already exists
     */
    public boolean putIfNotExists(byte[] key, byte[] value, long timestamp, UUID txId, Long commitTs, boolean deleted) {
        String cql = String.format(
            "INSERT INTO %s.%s (key, value, ts, tx_id, commit_ts, deleted) " +
            "VALUES (?, ?, ?, ?, ?, ?) IF NOT EXISTS",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_KV_STORE
        );
        
        BoundStatement bound = session.prepare(cql).bind(
            ByteBuffer.wrap(key),
            value != null ? ByteBuffer.wrap(value) : null,
            timestamp,
            txId,
            commitTs,
            deleted
        );
        
        ResultSet rs = session.execute(bound);
        Row row = rs.one();
        // LWT returns a row with [applied] column
        return row != null && row.getBoolean("[applied]");
    }
    
    /**
     * Conditional update (IF value = expectedValue) - for CAS operations with Accord
     * This satisfies the "conditional update" requirement for SERIAL consistency
     * 
     * For sequences, we actually need to INSERT a new version with a new timestamp
     * rather than UPDATE the existing row (since ts is part of the primary key)
     * 
     * We use IF NOT EXISTS on the new (key, ts) pair to make it conditional for Accord
     * 
     * @return true if the update succeeded, false if the condition failed
     */
    public boolean updateIf(byte[] key, byte[] oldValue, byte[] newValue, long timestamp, UUID txId, Long commitTs) {
        // For CAS on sequences, we need to find the current version first
        KvEntry current = get(key, timestamp);
        if (current == null) {
            // Key doesn't exist, can't update
            return false;
        }
        
        // Verify the current value matches the expected old value
        if (!Arrays.equals(current.getValue(), oldValue)) {
            // Value changed, CAS failed
            return false;
        }
        
        // Insert new version with new timestamp using IF NOT EXISTS
        // This satisfies Accord's conditional update requirement
        String cql = String.format(
            "INSERT INTO %s.%s (key, value, ts, tx_id, commit_ts, deleted) " +
            "VALUES (?, ?, ?, ?, ?, ?) IF NOT EXISTS",
            keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_KV_STORE
        );
        
        BoundStatement bound = session.prepare(cql).bind(
            ByteBuffer.wrap(key),
            newValue != null ? ByteBuffer.wrap(newValue) : null,
            timestamp,
            txId,
            commitTs,
            false
        );
        
        ResultSet rs = session.execute(bound);
        Row row = rs.one();
        // LWT returns a row with [applied] column
        return row != null && row.getBoolean("[applied]");
    }
    
    /**
     * Get a value for a key (MVCC-aware)
     * Returns the latest committed version at or before the given timestamp
     */
    public KvEntry get(byte[] key, long readTimestamp) {
        BoundStatement bound = getStatement.bind(ByteBuffer.wrap(key));
        ResultSet rs = session.execute(bound);
        
        // Find the latest version that is:
        // 1. Committed (commit_ts != null)
        // 2. Committed before or at readTimestamp
        // 
        // If the latest version is deleted, return null (tombstone)
        
        KvEntry latestEntry = null;
        long latestCommitTs = -1;
        
        for (Row row : rs) {
            Long commitTs = row.getLong("commit_ts");
            
            // Skip uncommitted versions
            if (commitTs == null) {
                continue;
            }
            
            // Skip versions committed after readTimestamp
            if (commitTs > readTimestamp) {
                continue;
            }
            
            // Keep the latest version (even if deleted - we need to know about tombstones)
            if (commitTs > latestCommitTs) {
                latestCommitTs = commitTs;
                
                boolean deleted = row.getBoolean("deleted");
                
                // If this version is deleted, return null (tombstone)
                if (deleted) {
                    return null;
                }
                
                ByteBuffer valueBuffer = row.getByteBuffer("value");
                byte[] value = valueBuffer != null ? new byte[valueBuffer.remaining()] : null;
                if (value != null) {
                    valueBuffer.get(value);
                }
                
                latestEntry = new KvEntry(
                    key,
                    value,
                    row.getLong("ts"),
                    row.getUuid("tx_id"),
                    commitTs,
                    deleted
                );
            }
        }
        
        return latestEntry;
    }
    
    /**
     * Scan a range of keys (MVCC-aware)
     */
    public List<KvEntry> scan(byte[] startKey, byte[] endKey, long readTimestamp, int limit) {
        return scan(startKey, endKey, readTimestamp, limit, null);
    }
    
    /**
     * Scan a range of keys (MVCC-aware with TRUNCATE support).
     * 
     * @param startKey Start of key range
     * @param endKey End of key range
     * @param readTimestamp MVCC read timestamp
     * @param limit Maximum number of entries to return
     * @param truncateTimestamp If not null, skip all data committed before this timestamp (TRUNCATE support)
     * @return List of visible entries
     */
    public List<KvEntry> scan(byte[] startKey, byte[] endKey, long readTimestamp, int limit, Long truncateTimestamp) {
        // Detect if this is a catalog table scan (negative table IDs)
        // Catalog tables are stored in pg_catalog keyspace, user tables in default keyspace
        long tableId = KeyEncoder.extractTableId(startKey);
        boolean isCatalogTable = (tableId < 0);
        
        // Prepare catalog statement lazily (pg_catalog keyspace might not exist during initialization)
        if (isCatalogTable) {
            ensureCatalogScanStatement();
        }
        
        PreparedStatement statement = isCatalogTable ? catalogScanStatement : scanStatement;
        
        BoundStatement bound = statement.bind(
            ByteBuffer.wrap(startKey),
            ByteBuffer.wrap(endKey)
        );
        
        ResultSet rs = session.execute(bound);
        
        // Group by key and find latest version for each
        Map<ByteBuffer, KvEntry> latestVersions = new HashMap<>();
        Set<ByteBuffer> deletedKeys = new HashSet<>();  // Track keys that have been deleted
        
        int totalRows = 0;
        int skippedUncommitted = 0;
        int skippedFuture = 0;
        int skippedDeleted = 0;
        int skippedTruncated = 0;
        
        for (Row row : rs) {
            totalRows++;
            ByteBuffer keyBuffer = row.getByteBuffer("key");
            
            // CRITICAL: Validate that the returned key belongs to the expected table
            // This prevents data corruption where rows from different tables get mixed
            byte[] keyBytes = new byte[keyBuffer.remaining()];
            keyBuffer.duplicate().get(keyBytes);
            
            // Extract table ID from the key and validate it matches
            long returnedTableId = KeyEncoder.extractTableId(keyBytes);
            if (returnedTableId != tableId) {
                // This key belongs to a different table - skip it
                // This should not happen with proper key encoding, but we validate to be safe
                log.warn("Scan returned key from wrong table: expected tableId={}, got tableId={}, isCatalogTable={}", 
                    tableId, returnedTableId);
                continue;
            }
            
            Long commitTs = row.isNull("commit_ts") ? null : row.getLong("commit_ts");
            
            // Skip uncommitted versions
            if (commitTs == null) {
                skippedUncommitted++;
                continue;
            }
            
            // Skip versions committed after readTimestamp
            if (commitTs > readTimestamp) {
                skippedFuture++;
                continue;
            }
            
            // Skip versions committed before truncate timestamp (TRUNCATE support)
            if (truncateTimestamp != null && commitTs < truncateTimestamp) {
                skippedTruncated++;
                continue;
            }
            
            // If this version is deleted, mark the key as deleted and skip all versions
            if (row.getBoolean("deleted")) {
                deletedKeys.add(keyBuffer);
                skippedDeleted++;
                continue;
            }
            
            // Skip if this key has been deleted (we saw a tombstone for it)
            if (deletedKeys.contains(keyBuffer)) {
                skippedDeleted++;
                continue;
            }
            
            // Check if this is the latest version for this key
            KvEntry existing = latestVersions.get(keyBuffer);
            if (existing == null || commitTs > existing.getCommitTs()) {
                byte[] key = new byte[keyBuffer.remaining()];
                keyBuffer.duplicate().get(key);
                
                ByteBuffer valueBuffer = row.getByteBuffer("value");
                byte[] value = valueBuffer != null ? new byte[valueBuffer.remaining()] : null;
                if (value != null) {
                    valueBuffer.get(value);
                }
                
                KvEntry entry = new KvEntry(
                    key,
                    value,
                    row.getLong("ts"),
                    row.getUuid("tx_id"),
                    commitTs,
                    row.getBoolean("deleted")
                );
                
                latestVersions.put(keyBuffer, entry);
            }
            
            // Stop if we've reached the limit
            if (latestVersions.size() >= limit) {
                break;
            }
        }
        
        if (truncateTimestamp != null) {
            log.debug("[SCAN] readTs={}, truncateTs={}, total={}, uncommitted={}, future={}, truncated={}, deleted={}, returned={}",
                readTimestamp, truncateTimestamp, totalRows, skippedUncommitted, skippedFuture, skippedTruncated, skippedDeleted, latestVersions.size());
        } else {
            log.debug("[SCAN] readTs={}, total={}, uncommitted={}, future={}, deleted={}, returned={}",
                readTimestamp, totalRows, skippedUncommitted, skippedFuture, skippedDeleted, latestVersions.size());
        }
        
        return new ArrayList<>(latestVersions.values());
    }
    
    /**
     * Delete a key
     */
    public void delete(byte[] key) {
        // Detect if this is a catalog table (negative table IDs)
        long tableId = KeyEncoder.extractTableId(key);
        boolean isCatalogTable = (tableId < 0);
        
        if (isCatalogTable) {
            // Ensure catalog delete statement is prepared
            ensureCatalogDeleteStatement();
            BoundStatement bound = catalogDeleteStatement.bind(ByteBuffer.wrap(key));
            session.execute(bound);
        } else {
            BoundStatement bound = deleteStatement.bind(ByteBuffer.wrap(key));
            session.execute(bound);
        }
    }
    
    /**
     * Check if a key exists (at given timestamp)
     */
    public boolean exists(byte[] key, long readTimestamp) {
        return get(key, readTimestamp) != null;
    }
    
    /**
     * Get all versions of keys in a range (for catalog cleanup)
     */
    public List<KvEntry> getAllVersionsInRange(byte[] startKey, byte[] endKey) {
        // Detect if this is a catalog table
        long tableId = KeyEncoder.extractTableId(startKey);
        boolean isCatalogTable = (tableId < 0);
        
        if (isCatalogTable) {
            ensureCatalogScanStatement();
        }
        
        PreparedStatement statement = isCatalogTable ? catalogScanStatement : scanStatement;
        
        BoundStatement bound = statement.bind(
            ByteBuffer.wrap(startKey),
            ByteBuffer.wrap(endKey)
        );
        
        ResultSet rs = session.execute(bound);
        
        List<KvEntry> versions = new ArrayList<>();
        for (Row row : rs) {
            ByteBuffer keyBuffer = row.getByteBuffer("key");
            byte[] keyBytes = new byte[keyBuffer.remaining()];
            keyBuffer.duplicate().get(keyBytes);
            
            ByteBuffer valueBuffer = row.getByteBuffer("value");
            byte[] value = valueBuffer != null ? new byte[valueBuffer.remaining()] : null;
            if (value != null) {
                valueBuffer.duplicate().get(value);
            }
            
            versions.add(new KvEntry(
                keyBytes,
                value,
                row.getLong("ts"),
                row.getUuid("tx_id"),
                row.getLong("commit_ts"),
                row.getBoolean("deleted")
            ));
        }
        
        return versions;
    }
    
    /**
     * Get all versions of a key (for debugging/admin)
     */
    public List<KvEntry> getAllVersions(byte[] key) {
        BoundStatement bound = getStatement.bind(ByteBuffer.wrap(key));
        ResultSet rs = session.execute(bound);
        
        List<KvEntry> versions = new ArrayList<>();
        for (Row row : rs) {
            ByteBuffer valueBuffer = row.getByteBuffer("value");
            byte[] value = valueBuffer != null ? new byte[valueBuffer.remaining()] : null;
            if (value != null) {
                valueBuffer.get(value);
            }
            
            versions.add(new KvEntry(
                key,
                value,
                row.getLong("ts"),
                row.getUuid("tx_id"),
                row.getLong("commit_ts"),
                row.getBoolean("deleted")
            ));
        }
        
        return versions;
    }
    
    /**
     * Entry in the KV store
     */
    public static class KvEntry {
        private final byte[] key;
        private final byte[] value;
        private final long timestamp;
        private final UUID txId;
        private final Long commitTs;
        private final boolean deleted;
        
        public KvEntry(byte[] key, byte[] value, long timestamp, UUID txId, Long commitTs,
                      boolean deleted) {
            this.key = key;
            this.value = value;
            this.timestamp = timestamp;
            this.txId = txId;
            this.commitTs = commitTs;
            this.deleted = deleted;
        }
        
        public byte[] getKey() { return key; }
        public byte[] getValue() { return value; }
        public long getTimestamp() { return timestamp; }
        public UUID getTxId() { return txId; }
        public Long getCommitTs() { return commitTs; }
        public boolean isDeleted() { return deleted; }
    }
}

