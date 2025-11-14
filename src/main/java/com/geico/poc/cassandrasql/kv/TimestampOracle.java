package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Timestamp Oracle for MVCC.
 * 
 * Provides monotonically increasing timestamps for transactions.
 * 
 * In KV mode, this uses Cassandra/Accord to maintain a distributed timestamp sequence,
 * allowing the SQL layer to scale horizontally. Multiple application instances can
 * allocate timestamps without coordination, as Cassandra ensures monotonicity.
 * 
 * Implementation:
 * - Timestamps are stored in Cassandra using the KV store
 * - Each application instance batches timestamp allocations (allocates in blocks)
 * - Fallback to in-memory counter if Cassandra is unavailable
 */
@Component
public class TimestampOracle {
    
    private static final Logger log = LoggerFactory.getLogger(TimestampOracle.class);
    
    @Autowired(required = false)
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired
    private InternalKeyspaceManager internalKeyspaceManager;
    
    // In-memory counter (used as fallback or in schema mode)
    private final AtomicLong counter;
    private final long baseTimestamp;
    
    // Distributed timestamp allocation (KV mode)
    private static final String TIMESTAMP_ORACLE_TABLE = "timestamp_oracle";
    private static final String ORACLE_ID = "global";
    private static final int BATCH_SIZE = 1;  // We could allocate timestamps in batches, but then we have to ensure there's one oracle
    private long batchStart = 0;
    private long batchEnd = 0;
    private final Object batchLock = new Object();
    
    public TimestampOracle() {
        this.baseTimestamp = System.currentTimeMillis() * 1000; // Convert to microseconds
        this.counter = new AtomicLong(0);
    }
    
    @PostConstruct
    public void initialize() {
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV) {
            log.info("Initializing distributed Timestamp Oracle (Cassandra-backed)...");
            initializeDistributedSequence();
            log.info("Timestamp Oracle initialized");
        } else {
            log.info("Using in-memory Timestamp Oracle (schema mode)");
        }
    }
    
    /**
     * Initialize the distributed timestamp sequence in Cassandra
     */
    private void initializeDistributedSequence() {
        if (session == null) {
            log.warn("CqlSession not available, using in-memory timestamps");
            return;
        }
        
        try {
            // Use the internal keyspace for timestamp oracle
            String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
            long currentTs = System.currentTimeMillis() * 1000;
            
            // Try to read existing timestamp
            String query = String.format(
                "SELECT current_timestamp FROM %s.%s WHERE oracle_id = ?",
                internalKeyspace, TIMESTAMP_ORACLE_TABLE
            );
            ResultSet rs = session.execute(
                SimpleStatement.builder(query)
                    .addPositionalValue(ORACLE_ID)
                    .build()
            );
            Row row = rs.one();
            
            if (row != null) {
                long existingTs = row.getLong("current_timestamp");
                log.info("Found existing timestamp oracle: {}", existingTs);
            } else {
                log.info("Timestamp oracle initialized (by InternalKeyspaceManager)");
            }
        } catch (Exception e) {
            log.warn("Failed to initialize distributed timestamp sequence: {}", e.getMessage());
            log.warn("Falling back to in-memory timestamps");
        }
    }
    
    /**
     * Allocate a new timestamp for a transaction start
     */
    public long allocateStartTimestamp() {
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV && session != null) {
            return allocateDistributedTimestamp();
        } else {
            return allocateLocalTimestamp();
        }
    }
    
    /**
     * Allocate a new timestamp for a transaction commit
     */
    public long allocateCommitTimestamp() {
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV && session != null) {
            return allocateDistributedTimestamp();
        } else {
            return allocateLocalTimestamp();
        }
    }
    
    /**
     * Allocate timestamp from distributed sequence (Cassandra-backed) using LWT for atomicity.
     * This ensures atomic timestamp allocation even with multiple coordinator instances.
     */
    private long allocateDistributedTimestamp() {
        // Check if we have timestamps left in current batch (quick path)
        synchronized (batchLock) {
            if (batchStart < batchEnd) {
                long ts = batchStart;
                batchStart++;
                return ts;
            }
        }
        
        // Need to allocate a new batch using LWT (lightweight transaction) for atomicity
        // Use CAS (Compare-And-Swap) loop to handle concurrent allocation attempts
        // NOTE: We do this outside the synchronized block to allow concurrent allocations
        final int MAX_RETRIES = 10;
        int retryCount = 0;
        
        while (retryCount < MAX_RETRIES) {
            try {
                String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
                long currentTs = System.currentTimeMillis() * 1000;
                
                // Step 1: Read current timestamp (outside transaction for calculation)
                String readQuery = String.format(
                    "SELECT current_timestamp FROM %s.%s WHERE oracle_id = ?",
                    internalKeyspace, TIMESTAMP_ORACLE_TABLE
                );
                ResultSet readRs = session.execute(
                    SimpleStatement.builder(readQuery)
                        .addPositionalValue(ORACLE_ID)
                        .build()
                );
                Row readRow = readRs.one();
                
                long currentValue;
                boolean needsInit = false;
                if (readRow == null || readRow.isNull("current_timestamp")) {
                    // Not initialized - use IF NOT EXISTS for initialization
                    needsInit = true;
                    currentValue = 0; // Dummy value for initialization
                } else {
                    currentValue = readRow.getLong("current_timestamp");
                }
                
                // Step 2: Calculate new values in Java
                // Ensure newStart >= currentTs (monotonicity) and > currentValue (uniqueness)
                long newStart = needsInit ? currentTs : Math.max(currentValue + 1, currentTs);
                long newEnd = newStart + BATCH_SIZE;
                
                // Step 3: Use lightweight transaction (LWT) for atomic CAS update
                // For initialization: INSERT ... IF NOT EXISTS
                // For update: UPDATE ... WHERE ... IF current_timestamp = ? provides CAS semantics
                String casQuery;
                if (needsInit) {
                    casQuery = String.format(
                        "INSERT INTO %s.%s (oracle_id, current_timestamp, last_allocated_batch, batch_size) " +
                        "VALUES ('%s', %d, %d, %d) IF NOT EXISTS",
                        internalKeyspace, TIMESTAMP_ORACLE_TABLE, ORACLE_ID, newEnd, newStart, BATCH_SIZE
                    );
                } else {
                    casQuery = String.format(
                        "UPDATE %s.%s SET current_timestamp = %d, last_allocated_batch = %d WHERE oracle_id = '%s' IF current_timestamp = %d",
                        internalKeyspace, TIMESTAMP_ORACLE_TABLE, newEnd, newStart, ORACLE_ID, currentValue
                    );
                }
                
                log.debug("Executing CAS LWT (attempt {}): current={}, newStart={}, newEnd={}", 
                    retryCount + 1, currentValue, newStart, newEnd);
                // Use SERIAL consistency level for LWT to ensure atomicity
                SimpleStatement stmt = SimpleStatement.newInstance(casQuery)
                    .setSerialConsistencyLevel(com.datastax.oss.driver.api.core.ConsistencyLevel.SERIAL);
                ResultSet result = session.execute(stmt);
                
                // Step 4: Check if CAS succeeded
                // LWT returns a row with [applied] column indicating success
                Row resultRow = result.one();
                boolean applied = resultRow != null && resultRow.getBoolean("[applied]");
                
                if (!applied) {
                    // CAS failed - another thread updated first
                    // Read the actual current value to retry with
                    ResultSet verifyRs = session.execute(
                        SimpleStatement.builder(readQuery)
                            .addPositionalValue(ORACLE_ID)
                            .build()
                    );
                    Row verifyRow = verifyRs.one();
                    
                    if (verifyRow == null || verifyRow.isNull("current_timestamp")) {
                        // Still not initialized - retry initialization
                        log.debug("Initialization failed, retrying...");
                        retryCount++;
                        if (retryCount < MAX_RETRIES) {
                            Thread.sleep(1 + (retryCount * 2));
                        }
                        continue;
                    }
                    
                    long actualTimestamp = verifyRow.getLong("current_timestamp");
                    log.debug("CAS failed: expected current={}, actual={}, retrying...", currentValue, actualTimestamp);
                    retryCount++;
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(1 + (retryCount * 2)); // Exponential backoff
                    }
                    continue; // Retry loop
                }
                
                // CAS succeeded! We got the batch
                synchronized (batchLock) {
                    batchStart = newStart;
                    batchEnd = newEnd;
                    log.debug("CAS succeeded: allocated batch [{}, {})", batchStart, batchEnd);
                    
                    long ts = batchStart;
                    batchStart++;
                    return ts;
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted during timestamp allocation", e);
                break;
            } catch (Exception e) {
                log.error("Failed to allocate distributed timestamp (attempt {}): {}", retryCount + 1, e.getMessage(), e);
                retryCount++;
                
                if (retryCount >= MAX_RETRIES) {
                    log.error("Failed to allocate distributed timestamp after {} retries, falling back to local timestamp", MAX_RETRIES);
                    return allocateLocalTimestamp();
                }
                
                try {
                    Thread.sleep(1 + (retryCount * 2));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // Exhausted retries - fall back to local timestamp
        log.error("Exhausted retries for distributed timestamp allocation, falling back to local timestamp");
        return allocateLocalTimestamp();
    }
    
    /**
     * Allocate timestamp from in-memory counter (fallback)
     */
    private long allocateLocalTimestamp() {
        return baseTimestamp + counter.incrementAndGet();
    }
    
    /**
     * Get current timestamp (for reads)
     * This does NOT increment the sequence - it returns the latest allocated timestamp.
     * This is safe for reads because we only need a timestamp >= all committed writes.
     * 
     * Performance: This is much faster than allocateStartTimestamp() because it doesn't
     * require a transactional write to Cassandra.
     */
    public long getCurrentTimestamp() {
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV && session != null) {
            // In distributed mode, use the latest allocated timestamp
            synchronized (batchLock) {
                if (batchStart > 0) {
                    return batchStart;
                }
            }
        }
        
        // Fallback to local timestamp
        return baseTimestamp + counter.get();
    }
    
    /**
     * Allocate a read timestamp for snapshot isolation.
     * For auto-commit reads, this ensures we see all committed data.
     * 
     * Note: This increments the sequence, which requires a Cassandra write.
     * Consider using getCurrentTimestamp() if you don't need strict ordering.
     */
    public long allocateReadTimestamp() {
        return allocateStartTimestamp();
    }
    
    /**
     * Get timestamp from milliseconds
     */
    public static long fromMillis(long millis) {
        return millis * 1000; // Convert to microseconds
    }
    
    /**
     * Convert timestamp to milliseconds
     */
    public static long toMillis(long timestamp) {
        return timestamp / 1000; // Convert from microseconds
    }
    
    /**
     * Check if timestamp is valid (not in the future)
     */
    public boolean isValid(long timestamp) {
        return timestamp <= getCurrentTimestamp();
    }
    
    /**
     * Get timestamp age in seconds
     */
    public long getAgeSeconds(long timestamp) {
        long current = getCurrentTimestamp();
        return (current - timestamp) / 1_000_000; // Convert microseconds to seconds
    }
    
    /**
     * Get statistics about timestamp allocation
     */
    public TimestampStats getStats() {
        synchronized (batchLock) {
            return new TimestampStats(
                batchStart,
                batchEnd,
                batchEnd - batchStart,
                config.getStorageMode() == CassandraSqlConfig.StorageMode.KV
            );
        }
    }
    
    /**
     * Statistics about timestamp allocation
     */
    public static class TimestampStats {
        private final long currentBatchStart;
        private final long currentBatchEnd;
        private final long remainingInBatch;
        private final boolean distributedMode;
        
        public TimestampStats(long currentBatchStart, long currentBatchEnd, 
                            long remainingInBatch, boolean distributedMode) {
            this.currentBatchStart = currentBatchStart;
            this.currentBatchEnd = currentBatchEnd;
            this.remainingInBatch = remainingInBatch;
            this.distributedMode = distributedMode;
        }
        
        public long getCurrentBatchStart() { return currentBatchStart; }
        public long getCurrentBatchEnd() { return currentBatchEnd; }
        public long getRemainingInBatch() { return remainingInBatch; }
        public boolean isDistributedMode() { return distributedMode; }
        
        @Override
        public String toString() {
            return String.format("TimestampStats{mode=%s, batch=[%d-%d], remaining=%d}",
                distributedMode ? "DISTRIBUTED" : "LOCAL",
                currentBatchStart, currentBatchEnd, remainingInBatch);
        }
    }
}

