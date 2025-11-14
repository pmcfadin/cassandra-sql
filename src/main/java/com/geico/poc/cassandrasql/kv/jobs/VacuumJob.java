package com.geico.poc.cassandrasql.kv.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import com.geico.poc.cassandrasql.kv.KvStore;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TimestampOracle;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import com.geico.poc.cassandrasql.kv.KeyEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Vacuum job for MVCC cleanup in KV mode.
 * 
 * Based on PostgreSQL's VACUUM behavior:
 * 
 * 1. NEVER deletes the latest committed version of a row
 * 2. Deletes old MVCC versions when multiple versions exist for the same key
 * 3. Deletes tombstones (deleted=true) only after retention period
 * 4. Preserves data within retention period for point-in-time queries
 * 
 * Safety guarantees:
 * - Minimum 1 hour retention (configurable, but enforced minimum)
 * - Never deletes live data
 * - Only cleans up superseded versions
 */
@Component
public class VacuumJob implements BackgroundJob {
    
    private static final Logger log = LoggerFactory.getLogger(VacuumJob.class);
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Value("${cassandra-sql.kv.vacuum.enabled:true}")
    private boolean enabled;
    
    @Value("${cassandra-sql.kv.vacuum.period-minutes:5}")
    private int periodMinutes;
    
    @Value("${cassandra-sql.kv.vacuum.retention-hours:1}")
    private int retentionHours;
    
    private PreparedStatement deleteVersionStmt;
    private PreparedStatement scanAllVersionsStmt;
    private RateLimiter rateLimiter;
    
    @Override
    public void execute() {
        try {
            // Initialize rate limiter if needed
            if (rateLimiter == null && config.getBackgroundJobs().isRateLimitEnabled()) {
                rateLimiter = new RateLimiter(config.getBackgroundJobs().getKeysPerHour());
                log.info("   Rate limiter initialized: " + rateLimiter.getKeysPerHour() + " keys/hour");
            }
            
            // Prepare statements if needed
            if (deleteVersionStmt == null) {
                try {
                    prepareStatements();
                } catch (Exception e) {
                    log.error("   Warning: Could not prepare vacuum statements: " + e.getMessage());
                    return; // Skip this run
                }
            }
            
            // Calculate minimum safe timestamp
            long minSafeTs = calculateMinSafeTimestamp();
            log.info("   Vacuum threshold: " + minSafeTs + 
                              " (retention: " + Math.max(1, retentionHours) + "h)");
            
            // Vacuum old versions
            int deletedCount = vacuumOldVersions(minSafeTs);
            
            if (deletedCount == 0) {
                log.info("   No old versions to vacuum");
            } else {
                log.info("   Vacuum freed: " + deletedCount + " old versions");
            }
            
            // Vacuum truncated data
            int truncatedCount = vacuumTruncatedData();
            if (truncatedCount > 0) {
                log.info("   Vacuum freed: " + truncatedCount + " truncated rows");
            }
            
            // Vacuum dropped tables
            int droppedTableCount = vacuumDroppedTables();
            if (droppedTableCount > 0) {
                log.info("   Vacuum cleaned up: " + droppedTableCount + " dropped tables");
            }
            
        } catch (Exception e) {
            log.error("   Vacuum failed: " + e.getMessage());
            e.printStackTrace();
            // Don't rethrow - let scheduler continue
        }
    }
    
    /**
     * Calculate minimum safe timestamp for vacuum.
     * 
     * SAFETY: Always keeps at least 1 hour of history, even if configured to 0
     */
    private long calculateMinSafeTimestamp() {
        long currentTs = timestampOracle.getCurrentTimestamp();
        
        // SAFETY CHECK: Never allow retention < 1 hour to prevent accidental data loss
        int safeRetentionHours = Math.max(1, retentionHours);
        if (retentionHours < 1) {
            log.error("   WARNING: retention-hours=" + retentionHours + 
                              " is too low! Using minimum of 1 hour for safety.");
        }
        
        long retentionTs = currentTs - (safeRetentionHours * 3600L * 1000L * 1000L); // Convert hours to microseconds
        
        // TODO: Get min active transaction timestamp from transaction coordinator
        // For now, just use retention period
        return retentionTs;
    }
    
    /**
     * Prepare CQL statements for vacuum operations
     */
    private void prepareStatements() {
        String keyspace = keyspaceConfig.getDefaultKeyspace();
        String kvTable = KeyspaceConfig.TABLE_KV_STORE;
        
        // Delete a specific version
        deleteVersionStmt = session.prepare(
            String.format("DELETE FROM %s.%s WHERE key = ? AND ts = ?", keyspace, kvTable)
        );
        
        // Scan all versions (for grouping by key)
        scanAllVersionsStmt = session.prepare(
            String.format("SELECT key, ts, commit_ts, deleted FROM %s.%s ALLOW FILTERING", keyspace, kvTable)
        );
    }
    
    /**
     * Vacuum old versions following PostgreSQL semantics.
     * 
     * Algorithm:
     * 1. Group all versions by key
     * 2. For each key, sort versions by timestamp (newest first)
     * 3. Keep the latest version (ALWAYS)
     * 4. For older versions:
     *    - If deleted=true AND commit_ts < minSafeTs: DELETE
     *    - If deleted=false AND commit_ts < minSafeTs AND not latest: DELETE
     * 5. Never delete the latest committed version
     * 
     * OPTIMIZATION: Limit scan to avoid timeouts on large datasets.
     * Only scan versions that might be eligible for deletion (older than minSafeTs).
     */
    private int vacuumOldVersions(long minSafeTs) {
        int deletedCount = 0;
        
        try {
            // Scan all versions (with limit to avoid timeout)
            // TODO: Optimize to only scan versions older than minSafeTs
            ResultSet rs = session.execute(scanAllVersionsStmt.bind());
            
            // Group versions by key
            Map<ByteBuffer, List<VersionInfo>> versionsByKey = new HashMap<>();
            
            int scannedRows = 0;
            int maxScanRows = 100000; // Limit scan to avoid timeout
            
            for (Row row : rs) {
                scannedRows++;
                if (scannedRows > maxScanRows) {
                    log.warn("   Vacuum scan limit reached (" + maxScanRows + " rows), stopping scan to avoid timeout");
                    break;
                }
                
                // Rate limit: one key per iteration
                if (rateLimiter != null) {
                    try {
                        rateLimiter.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("   Vacuum interrupted during rate limiting");
                        return deletedCount;
                    }
                }
                
                ByteBuffer key = row.getByteBuffer("key");
                long ts = row.getLong("ts");
                Long commitTs = row.isNull("commit_ts") ? null : row.getLong("commit_ts");
                boolean deleted = row.getBoolean("deleted");
                
                // Skip versions that are too recent (optimization)
                if (commitTs != null && commitTs >= minSafeTs) {
                    continue; // Too recent, skip
                }
                
                versionsByKey.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new VersionInfo(key, ts, commitTs, deleted));
            }
            
            log.info("   Scanned " + scannedRows + " rows, found " + versionsByKey.size() + " unique keys with old versions");
            
            // Process each key
            for (Map.Entry<ByteBuffer, List<VersionInfo>> entry : versionsByKey.entrySet()) {
                List<VersionInfo> versions = entry.getValue();
                
                // Sort by timestamp (newest first)
                versions.sort((a, b) -> Long.compare(b.ts, a.ts));
                
                // Process versions
                deletedCount += vacuumKeyVersions(versions, minSafeTs);
            }
            
        } catch (Exception e) {
            log.error("   Error during vacuum: " + e.getMessage());
            e.printStackTrace();
        }
        
        return deletedCount;
    }
    
    /**
     * Vacuum versions for a single key.
     * 
     * Rules:
     * 1. ALWAYS keep the latest committed version (index 0 after sorting) UNLESS it's a tombstone
     * 2. Delete old versions (index > 0) if commit_ts < minSafeTs
     * 3. Delete tombstones (deleted=true) if commit_ts < minSafeTs, even if it's the latest version
     * 
     * Note: Tombstones (deleted=true) can be deleted even if they're the latest version,
     * because they represent deleted data and don't need to be preserved.
     */
    private int vacuumKeyVersions(List<VersionInfo> versions, long minSafeTs) {
        int deletedCount = 0;
        
        if (versions.isEmpty()) {
            return 0;
        }
        
        // Find the latest committed version
        VersionInfo latestCommitted = null;
        for (VersionInfo v : versions) {
            if (v.commitTs != null) {
                latestCommitted = v;
                break; // Already sorted by ts desc
            }
        }
        
        // Process each version
        for (int i = 0; i < versions.size(); i++) {
            VersionInfo version = versions.get(i);
            
            // Skip if not committed yet
            if (version.commitTs == null) {
                continue;
            }
            
            // SAFETY: Never delete the latest committed version UNLESS it's a tombstone
            // Tombstones can be deleted even if they're the latest version, because they
            // represent deleted data and don't need to be preserved for point-in-time queries
            if (version == latestCommitted && !version.deleted) {
                continue; // Keep latest non-deleted version
            }
            
            // Check if this version is old enough to vacuum
            if (version.commitTs < minSafeTs) {
                // Delete old version or tombstone
                try {
                    session.execute(deleteVersionStmt.bind(version.key, version.ts));
                    deletedCount++;
                } catch (Exception e) {
                    log.error("   Warning: Could not delete version: " + e.getMessage());
                }
            }
        }
        
        return deletedCount;
    }
    
    /**
     * Vacuum data that was truncated (committed before table's truncate timestamp).
     * 
     * For each table with a truncate_ts set, delete all rows where commit_ts < truncate_ts.
     * This is safe because TRUNCATE semantically removes all data from the table at that point in time.
     */
    private int vacuumTruncatedData() {
        int deletedCount = 0;
        
        try {
            // Get all tables
            java.util.Collection<TableMetadata> tables = schemaManager.getAllTables();
            
            for (TableMetadata table : tables) {
                Long truncateTs = table.getTruncateTimestamp();
                if (truncateTs == null) {
                    continue; // Table has not been truncated
                }
                
                log.info("   Vacuuming truncated data for table: " + table.getTableName() + 
                                  " (truncate_ts=" + truncateTs + ")");
                
                // Scan all data for this table
                byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
                byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
                
                // Scan all versions (use MAX_VALUE to see everything)
                List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, Long.MAX_VALUE, 100000);
                
                // Delete entries committed before truncate timestamp
                for (KvStore.KvEntry entry : entries) {
                    // Rate limit: one key per iteration
                    if (rateLimiter != null) {
                        try {
                            rateLimiter.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.error("   Vacuum interrupted during rate limiting");
                            return deletedCount;
                        }
                    }
                    
                    if (entry.getCommitTs() != null && entry.getCommitTs() < truncateTs) {
                        try {
                            ByteBuffer keyBuffer = ByteBuffer.wrap(entry.getKey());
                            session.execute(deleteVersionStmt.bind(keyBuffer, entry.getTimestamp()));
                            deletedCount++;
                        } catch (Exception e) {
                            log.error("   Warning: Could not delete truncated row: " + e.getMessage());
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("   Error during truncated data vacuum: " + e.getMessage());
            e.printStackTrace();
        }
        
        return deletedCount;
    }
    
    /**
     * Vacuum dropped tables (delete all data and schema metadata).
     * 
     * For each table with droppedTimestamp set:
     * 1. Delete all data rows (primary index)
     * 2. Delete all secondary index entries
     * 3. Delete the schema metadata
     * 
     * This completes the lazy drop operation started by SchemaManager.dropTable().
     * 
     * @return Number of dropped tables cleaned up
     */
    private int vacuumDroppedTables() {
        int cleanedCount = 0;
        
        try {
            List<TableMetadata> droppedTables = schemaManager.getAllDroppedTables();
            
            if (droppedTables.isEmpty()) {
                return 0;
            }
            
            log.info("   Found " + droppedTables.size() + " dropped table(s) to clean up");
            
            for (TableMetadata table : droppedTables) {
                try {
                    log.info("   Cleaning up dropped table: " + table.getTableName() + 
                                      " (id=" + table.getTableId() + ", droppedTs=" + table.getDroppedTimestamp() + ")");
                    
                    // Delete primary index data
                    byte[] primaryStartKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
                    byte[] primaryEndKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
                    int primaryDeleted = deleteKeyRange(primaryStartKey, primaryEndKey);
                    log.info("      Deleted " + primaryDeleted + " primary index entries");
                    
                    // Delete secondary index data
                    for (TableMetadata.IndexMetadata index : table.getIndexes()) {
                        byte[] indexStartKey = KeyEncoder.createRangeStartKey(table.getTableId(), index.getIndexId(), null);
                        byte[] indexEndKey = KeyEncoder.createRangeEndKey(table.getTableId(), index.getIndexId(), null);
                        int indexDeleted = deleteKeyRange(indexStartKey, indexEndKey);
                        log.info("      Deleted " + indexDeleted + " entries from index: " + index.getName());
                    }
                    
                    // Delete schema metadata
                    byte[] schemaKey = KeyEncoder.encodeSchemaKey(table.getTableId(), "schema");
                    kvStore.delete(schemaKey);
                    log.info("      Deleted schema metadata");
                    
                    // Remove from dropped tables cache
                    schemaManager.removeFromDroppedCache(table.getTableId());
                    
                    cleanedCount++;
                    log.info("   ✅ Cleaned up dropped table: " + table.getTableName());
                    
                } catch (Exception e) {
                    log.error("   ⚠️  Failed to clean up dropped table " + table.getTableName() + ": " + e.getMessage());
                    e.printStackTrace();
                    // Continue with other tables
                }
            }
            
        } catch (Exception e) {
            log.error("   Error during dropped table vacuum: " + e.getMessage());
            e.printStackTrace();
        }
        
        return cleanedCount;
    }
    
    /**
     * Delete all keys in a range (with rate limiting)
     * 
     * @return Number of entries deleted
     */
    private int deleteKeyRange(byte[] startKey, byte[] endKey) {
        int deletedCount = 0;
        
        try {
            // Scan all keys in range (using max timestamp to see all versions)
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, Long.MAX_VALUE, 100000);
            
            // Delete each key with rate limiting
            for (KvStore.KvEntry entry : entries) {
                // Rate limit: one key per iteration
                if (rateLimiter != null) {
                    try {
                        rateLimiter.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("   Vacuum interrupted during rate limiting");
                        return deletedCount;
                    }
                }
                
                try {
                    ByteBuffer keyBuffer = ByteBuffer.wrap(entry.getKey());
                    session.execute(deleteVersionStmt.bind(keyBuffer, entry.getTimestamp()));
                    deletedCount++;
                } catch (Exception e) {
                    log.error("   Warning: Could not delete key: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("   Error deleting key range: " + e.getMessage());
        }
        
        return deletedCount;
    }
    
    /**
     * Version information for a single MVCC version
     */
    private static class VersionInfo {
        final ByteBuffer key;
        final long ts;
        final Long commitTs;
        final boolean deleted;
        
        VersionInfo(ByteBuffer key, long ts, Long commitTs, boolean deleted) {
            this.key = key;
            this.ts = ts;
            this.commitTs = commitTs;
            this.deleted = deleted;
        }
    }
    
    @Override
    public String getName() {
        return "VacuumJob";
    }
    
    @Override
    public long getInitialDelayMs() {
        // Start after 1 minute
        return 60 * 1000;
    }
    
    @Override
    public long getPeriodMs() {
        return periodMinutes * 60 * 1000L;
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
