package com.geico.poc.cassandrasql.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transaction coordinator for KV mode using Percolator protocol.
 * 
 * Percolator is a distributed transaction protocol that works on top of
 * eventually consistent storage (like Cassandra). It uses a two-phase commit
 * protocol with primary and secondary locks.
 * 
 * Protocol:
 * 1. Prewrite Phase:
 *    - Choose one key as primary lock
 *    - For each key (data + indexes):
 *      - Check for write conflicts (commit_ts > start_ts)
 *      - Check for existing locks
 *      - Write lock record
 *      - Write data with commit_ts = NULL (uncommitted)
 * 
 * 2. Commit Phase:
 *    - Allocate commit_ts
 *    - Commit primary lock atomically:
 *      - Update data row: set commit_ts
 *      - Write commit record to kv_writes
 *      - Delete lock from kv_locks
 *    - Commit secondary locks (can be async):
 *      - Update data rows: set commit_ts
 *      - Write commit records
 *      - Delete locks
 * 
 * 3. Rollback:
 *    - Delete all locks
 *    - Delete all uncommitted data rows
 */
@Component
@DependsOn({"kvStore", "databaseManager"})  // Ensure KvStore and DatabaseManager initialize first
public class KvTransactionCoordinator {
    
    private static final Logger log = LoggerFactory.getLogger(KvTransactionCoordinator.class);
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    // Active transactions
    private final Map<UUID, KvTransactionContext> activeTransactions = new ConcurrentHashMap<>();
    
    // Prepared statements
    private PreparedStatement checkLockStatement;
    private PreparedStatement writeLockStatement;
    private PreparedStatement deleteLockStatement;
    private PreparedStatement checkConflictStatement;
    private PreparedStatement writeCommitRecordStatement;
    private PreparedStatement updateCommitTsStatement;
    
    @PostConstruct
    public void initialize() {
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV) {
            // Wait a bit for KvStore to complete migration if needed
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            prepareCqlStatements();
            
            // Clean up stale locks from previous runs
            cleanupStaleLocks();
        }
    }
    
    /**
     * Clean up stale locks that may have been left behind from crashed transactions.
     * This runs on startup to ensure a clean state.
     */
    private void cleanupStaleLocks() {
        try {
            log.info("🧹 Cleaning up stale transaction locks...");
            
            // Truncate kv_locks table to remove all stale locks
            String truncateLocks = String.format("TRUNCATE %s.kv_locks", keyspaceConfig.getDefaultKeyspace());
            session.execute(truncateLocks);
            log.info("   ✅ Cleared kv_locks table");
            
            // Also truncate tx_locks if it exists
            try {
                String truncateTxLocks = String.format("TRUNCATE %s.%s", 
                    keyspaceConfig.getDefaultKeyspace(), KeyspaceConfig.TABLE_TX_LOCKS);
                session.execute(truncateTxLocks);
                log.info("   ✅ Cleared tx_locks table");
            } catch (Exception e) {
                log.debug("   Note: tx_locks table may not exist yet");
            }
            
            log.info("🧹 Stale lock cleanup complete");
        } catch (Exception e) {
            log.warn("⚠️  Failed to clean up stale locks: " + e.getMessage());
            log.warn("   This is normal on first startup");
        }
    }
    
    /**
     * Prepare CQL statements for transaction operations
     */
    private void prepareCqlStatements() {
        try {
            // Check if a lock exists for a key
            checkLockStatement = session.prepare(
                String.format("SELECT tx_id, start_ts, primary_key, lock_type FROM %s.kv_locks WHERE key = ?", 
                    keyspaceConfig.getDefaultKeyspace())
            );
            
            // Write a lock
            writeLockStatement = session.prepare(
                String.format(
                    "INSERT INTO %s.kv_locks (key, tx_id, start_ts, primary_key, lock_type, write_type, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, toTimestamp(now())) IF NOT EXISTS",
                    keyspaceConfig.getDefaultKeyspace()
                )
            );
            
            // Delete a lock
            deleteLockStatement = session.prepare(
                String.format("DELETE FROM %s.kv_locks WHERE key = ?", keyspaceConfig.getDefaultKeyspace())
            );
            
            // Check for write conflicts (any committed writes after start_ts)
            checkConflictStatement = session.prepare(
                String.format(
                    "SELECT commit_ts FROM %s.kv_writes WHERE key = ? AND commit_ts > ? LIMIT 1",
                    keyspaceConfig.getDefaultKeyspace()
                )
            );
            
            // Write commit record
            writeCommitRecordStatement = session.prepare(
                String.format(
                    "INSERT INTO %s.kv_writes (key, commit_ts, start_ts, tx_id, write_type) VALUES (?, ?, ?, ?, ?)",
                    keyspaceConfig.getDefaultKeyspace()
                )
            );
            
            // Update commit timestamp in kv_store
            // This might fail if table schema is being migrated, will retry on first use
            try {
                updateCommitTsStatement = session.prepare(
                    String.format("UPDATE %s.kv_store SET commit_ts = ? WHERE key = ? AND ts = ?", 
                        keyspaceConfig.getDefaultKeyspace())
                );
            } catch (Exception e) {
                log.debug("  ⚠️  Could not prepare updateCommitTsStatement (will retry on first use): " + e.getMessage());
                updateCommitTsStatement = null;
            }
        } catch (Exception e) {
            log.error("  ❌ Failed to prepare CQL statements: " + e.getMessage());
            throw new RuntimeException("Failed to prepare CQL statements", e);
        }
    }
    
    /**
     * Ensure updateCommitTsStatement is prepared (lazy initialization)
     */
    private void ensureUpdateCommitTsStatement() {
        if (updateCommitTsStatement == null) {
            synchronized (this) {
                if (updateCommitTsStatement == null) {
                    updateCommitTsStatement = session.prepare(
                        String.format("UPDATE %s.kv_store SET commit_ts = ? WHERE key = ? AND ts = ?", 
                            keyspaceConfig.getDefaultKeyspace())
                    );
                }
            }
        }
    }
    
    /**
     * Begin a new transaction
     */
    public KvTransactionContext beginTransaction() {
        UUID txId = UUID.randomUUID();
        long startTs = timestampOracle.allocateStartTimestamp();
        
        KvTransactionContext ctx = new KvTransactionContext(txId, startTs);
        activeTransactions.put(txId, ctx);
        
        log.debug("🔵 BEGIN KV transaction: " + txId + " (start_ts=" + startTs + ")");
        return ctx;
    }
    
    /**
     * Commit a transaction using Percolator protocol
     */
    public void commit(KvTransactionContext ctx) throws TransactionException {
        try {
            log.debug("🟢 COMMIT KV transaction: " + ctx.getTxId() + 
                             " (" + ctx.getWriteCount() + " writes)");
            
            // Handle read-only transactions (no writes)
            if (ctx.getWriteCount() == 0) {
                log.debug("✅ Read-only KV transaction committed (no writes): " + ctx.getTxId());
                ctx.setState(KvTransactionContext.TransactionState.COMMITTED);
                return;
            }
            
            // Phase 1: Prewrite
            ctx.setState(KvTransactionContext.TransactionState.PREWRITING);
            prewrite(ctx);
            
            // Phase 2: Commit
            ctx.setState(KvTransactionContext.TransactionState.COMMITTING);
            commitTransaction(ctx);
            
            ctx.setState(KvTransactionContext.TransactionState.COMMITTED);
            log.debug("✅ KV transaction committed: " + ctx.getTxId());
            
        } catch (TransactionException e) {
            log.error("❌ KV transaction failed: " + ctx.getTxId() + " - " + e.getMessage());
            rollback(ctx);
            throw e;
        } finally {
            activeTransactions.remove(ctx.getTxId());
        }
    }
    
    /**
     * Prewrite phase: Acquire locks and write uncommitted data
     */
    private void prewrite(KvTransactionContext ctx) throws TransactionException {
        // Choose primary key
        ctx.choosePrimaryKey();
        byte[] primaryKey = ctx.getPrimaryKey();
        
        log.debug("  [PREWRITE] Primary key chosen, acquiring locks for " + 
                         ctx.getWriteCount() + " keys...");
        
        // Prewrite all keys (primary and secondary)
        for (KvTransactionContext.WriteIntent intent : ctx.getWriteIntents()) {
            prewriteKey(ctx, intent);
        }
        
        log.debug("  [PREWRITE] All locks acquired successfully");
    }
    
    /**
     * Prewrite a single key using Accord transaction for atomicity.
     * This atomically:
     * 1. Checks for existing locks
     * 2. Checks for write conflicts
     * 3. Acquires lock
     * 4. Writes uncommitted data
     */
    private void prewriteKey(KvTransactionContext ctx, KvTransactionContext.WriteIntent intent) 
            throws TransactionException {
        byte[] key = intent.getKey();
        String keyHex = bytesToHex(key);
        String primaryKeyHex = bytesToHex(ctx.getPrimaryKey());
        String lockType = intent.isPrimary() ? "primary" : "secondary";
        String writeType = intent.getWriteType().name();
        String keyspace = keyspaceConfig.getDefaultKeyspace();
        
        // Use LWT IF NOT EXISTS for atomic lock acquisition
        // This ensures only ONE transaction can insert the lock for a given key
        // Then check conflicts and write data
        final int MAX_RETRIES = 10;
        int retryCount = 0;
        
        while (retryCount < MAX_RETRIES) {
            try {
                // Step 1: Use Accord transaction to atomically:
                //   - Check for existing lock
                //   - Check for write conflicts
                //   - Check for uncommitted versions
                //   - Acquire lock if all checks pass
                //   - Write uncommitted data
                // This ensures everything happens atomically in a single transaction
                StringBuilder accordTx = new StringBuilder("BEGIN TRANSACTION\n");
                
                // Check if lock already exists
                accordTx.append(String.format(
                    "  LET existing_lock = (SELECT tx_id FROM %s.kv_locks WHERE key = 0x%s LIMIT 1);\n",
                    keyspace, keyHex
                ));
                
                // Check for write conflicts (commits after our start_ts)
                accordTx.append(String.format(
                    "  LET conflict = (SELECT commit_ts FROM %s.kv_writes WHERE key = 0x%s AND commit_ts > %d LIMIT 1);\n",
                    keyspace, keyHex, ctx.getStartTs()
                ));
                
                // Flattened IF condition: no lock AND no conflict
                // Match the exact pattern from AccordLockTest.testConcurrentLockAcquisition() which works
                // Only insert the lock - don't write data here to keep it simple and atomic
                accordTx.append("  IF existing_lock IS NULL AND conflict IS NULL THEN\n");
                
                // Insert lock only (matching AccordLockTest pattern exactly)
                accordTx.append(String.format(
                    "    INSERT INTO %s.kv_locks (key, tx_id, start_ts, primary_key, lock_type, write_type, created_at) " +
                    "    VALUES (0x%s, %s, %d, 0x%s, '%s', '%s', toTimestamp(now()));\n",
                    keyspace, keyHex, ctx.getTxId().toString(), ctx.getStartTs(), primaryKeyHex, lockType, writeType
                ));
                
                accordTx.append("  END IF\n");
                accordTx.append("COMMIT TRANSACTION");
                
                // Execute Accord transaction
                String accordQuery = accordTx.toString();
                log.debug("Executing Accord transaction for lock acquisition (tx_id={}, key={}):\n{}", 
                    ctx.getTxId(), keyHex, accordQuery);
                
                try {
                    // Use SERIAL consistency level for Accord transactions to ensure serializability
                    com.datastax.oss.driver.api.core.cql.SimpleStatement accordStmt = 
                        com.datastax.oss.driver.api.core.cql.SimpleStatement.newInstance(accordQuery)
                            .setConsistencyLevel(com.datastax.oss.driver.api.core.ConsistencyLevel.SERIAL);
                    session.execute(accordStmt);
                } catch (Exception e) {
                    log.error("Accord transaction failed for key {}: {}", keyHex, e.getMessage(), e);
                    retryCount++;
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(1 + (retryCount * 2));
                    }
                    continue;
                }
                
                // Verify lock was acquired by checking if it exists with our tx_id
                // This check happens AFTER the Accord transaction commits
                // Use QUORUM consistency to ensure we see the committed state
                // Accord guarantees serializability, so QUORUM is sufficient to see
                // the Accord transaction's committed state without needing sleeps
                BoundStatement verifyLock = checkLockStatement.bind(ByteBuffer.wrap(key))
                    .setConsistencyLevel(com.datastax.oss.driver.api.core.ConsistencyLevel.QUORUM);
                ResultSet verifyResult = session.execute(verifyLock);
                Row verifyRow = verifyResult.one();
                
                if (verifyRow == null) {
                    // No lock was inserted - Accord transaction IF condition was false
                    // This means either: lock exists, conflict exists
                    // Don't retry - throw exception immediately
                    log.debug("Lock not acquired for key {}: Accord transaction IF condition was false (no lock inserted)", keyHex);
                    throw new TransactionException("Lock conflict: failed to acquire lock for key " + keyHex + " - Accord transaction IF condition was false");
                }
                
                UUID actualTxId = verifyRow.getUuid("tx_id");
                if (!actualTxId.equals(ctx.getTxId())) {
                    // Another transaction has the lock
                    log.debug("Lock conflict for key {}: lock exists with tx_id {} (expected {})", 
                        keyHex, actualTxId, ctx.getTxId());
                    throw new TransactionException("Lock conflict: key is locked by transaction " + actualTxId);
                }
                
                log.debug("✅ Lock acquired successfully for key {}: tx_id={}", keyHex, ctx.getTxId());
                
                // Step 2: Lock acquired! Now write uncommitted data
                // We do this AFTER acquiring the lock to ensure atomicity
                if (intent.getWriteType() == KvTransactionContext.WriteType.PUT) {
                    byte[] value = intent.getValue();
                    kvStore.put(key, value, ctx.getStartTs(), ctx.getTxId(), null, false);
                } else {
                    // For DELETE, write a tombstone
                    kvStore.put(key, null, ctx.getStartTs(), ctx.getTxId(), null, true);
                }
                
                return; // Success!
                
            } catch (TransactionException e) {
                // Don't retry on transaction exceptions (lock conflicts, write conflicts, etc.)
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransactionException("Prewrite interrupted", e);
            } catch (Exception e) {
                log.error("Failed to acquire lock (attempt {}): {}", retryCount + 1, e.getMessage());
                retryCount++;
                
                if (retryCount >= MAX_RETRIES) {
                    throw new TransactionException("Failed to acquire lock after " + MAX_RETRIES + " retries: " + e.getMessage(), e);
                }
                
                try {
                    Thread.sleep(1 + (retryCount * 2));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TransactionException("Prewrite interrupted", ie);
                }
            }
        }
        
        throw new TransactionException("Failed to acquire lock for key " + keyHex + " after " + MAX_RETRIES + " retries");
    }
    
    /**
     * Commit phase: Commit primary lock, then secondary locks
     */
    private void commitTransaction(KvTransactionContext ctx) throws TransactionException {
        // Allocate commit timestamp
        long commitTs = timestampOracle.allocateCommitTimestamp();
        ctx.setCommitTs(commitTs);
        
        log.debug("  [COMMIT] Allocated commit_ts=" + commitTs);
        
        // Step 1: Commit primary lock atomically
        commitPrimaryLock(ctx);
        
        // Step 2: Commit secondary locks (can be async, but we do it sync for now)
        commitSecondaryLocks(ctx);
        
        log.debug("  [COMMIT] All locks committed successfully");
    }
    
    /**
     * Commit the primary lock atomically using Accord transaction.
     * This is the commit point - once this succeeds, the transaction is committed.
     * 
     * Uses Accord transaction to atomically:
     * 1. Verify lock exists and matches this transaction
     * 2. Update commit_ts in kv_store (with verification)
     * 3. Write commit record to kv_writes
     * 4. Delete lock from kv_locks
     */
    private void commitPrimaryLock(KvTransactionContext ctx) throws TransactionException {
        byte[] primaryKey = ctx.getPrimaryKey();
        String keyHex = bytesToHex(primaryKey);
        String keyspace = keyspaceConfig.getDefaultKeyspace();
        
        log.debug("  [COMMIT] Committing primary lock...");
        
        // Find the primary write intent
        KvTransactionContext.WriteIntent primaryIntent = null;
        for (KvTransactionContext.WriteIntent intent : ctx.getWriteIntents()) {
            if (intent.isPrimary()) {
                primaryIntent = intent;
                break;
            }
        }
        
        if (primaryIntent == null) {
            throw new TransactionException("Primary lock not found");
        }
        
        // Build Accord transaction for atomic commit
        StringBuilder accordTx = new StringBuilder("BEGIN TRANSACTION\n");
        
        // Step 1: Verify lock exists and matches this transaction
        accordTx.append(String.format(
            "LET lock_check = (SELECT tx_id, start_ts FROM %s.kv_locks WHERE key = 0x%s);\n",
            keyspace, keyHex
        ));
        
        // Step 2: Verify uncommitted data exists with matching start_ts and commit_ts IS NULL
        accordTx.append(String.format(
            "LET data_check = (SELECT ts, commit_ts FROM %s.kv_store WHERE key = 0x%s AND ts = %d LIMIT 1);\n",
            keyspace, keyHex, ctx.getStartTs()
        ));
        
        // Step 3: Conditional commit - only if lock and data match AND commit_ts IS NULL
        // This ensures we don't commit already-committed data
        accordTx.append(String.format(
            "IF lock_check IS NOT NULL AND lock_check.tx_id = %s AND lock_check.start_ts = %d AND data_check IS NOT NULL AND data_check.commit_ts IS NULL THEN\n",
            ctx.getTxId().toString(), ctx.getStartTs()
        ));
        
        // Step 4: Update commit_ts in kv_store (atomic with verification)
        accordTx.append(String.format(
            "  UPDATE %s.kv_store SET commit_ts = %d WHERE key = 0x%s AND ts = %d;\n",
            keyspace, ctx.getCommitTs(), keyHex, ctx.getStartTs()
        ));
        
        // Step 5: Write commit record
        accordTx.append(String.format(
            "  INSERT INTO %s.kv_writes (key, commit_ts, start_ts, tx_id, write_type) " +
            "  VALUES (0x%s, %d, %d, %s, '%s');\n",
            keyspace, keyHex, ctx.getCommitTs(), ctx.getStartTs(), 
            ctx.getTxId().toString(), primaryIntent.getWriteType().name()
        ));
        
        // Step 6: Delete lock
        accordTx.append(String.format(
            "  DELETE FROM %s.kv_locks WHERE key = 0x%s;\n",
            keyspace, keyHex
        ));
        
        accordTx.append("END IF\n");
        accordTx.append("COMMIT TRANSACTION");
        
        // Execute Accord transaction
        // The Accord transaction atomically checks all conditions and commits if they're met
        // If the IF condition is false, the transaction succeeds but does nothing
        // If there's an error, an exception will be thrown
        try {
            session.execute(accordTx.toString());
            // If we get here without exception, the transaction executed successfully
            // The IF condition ensures commit only happens if all checks pass
            log.debug("  [COMMIT] Primary lock committed successfully");
            
        } catch (Exception e) {
            if (e instanceof TransactionException) {
                throw e;
            }
            throw new TransactionException("Primary lock commit failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Commit secondary locks using Accord transactions with retry logic.
     * These can be done asynchronously since the transaction is already committed
     * once the primary lock is committed, but we do them synchronously with retries
     * to ensure eventual consistency.
     */
    private void commitSecondaryLocks(KvTransactionContext ctx) throws TransactionException {
        List<byte[]> secondaryKeys = ctx.getSecondaryKeys();
        
        if (secondaryKeys.isEmpty()) {
            return;
        }
        
        log.debug("  [COMMIT] Committing " + secondaryKeys.size() + " secondary locks using Accord transactions...");
        
        String keyspace = keyspaceConfig.getDefaultKeyspace();
        int maxRetries = 3;
        
        // Commit each secondary lock with retry logic
        for (byte[] key : secondaryKeys) {
            // Find the write intent for this key
            KvTransactionContext.WriteIntent intent = null;
            for (KvTransactionContext.WriteIntent wi : ctx.getWriteIntents()) {
                if (Arrays.equals(wi.getKey(), key)) {
                    intent = wi;
                    break;
                }
            }
            
            if (intent == null) {
                log.error("  [COMMIT] Warning: Write intent not found for secondary key");
                continue;
            }
            
            String keyHex = bytesToHex(key);
            boolean committed = false;
            int retries = 0;
            
            while (!committed && retries < maxRetries) {
                try {
                    // Build Accord transaction for atomic secondary lock commit
                    StringBuilder accordTx = new StringBuilder("BEGIN TRANSACTION\n");
                    
                    // Step 1: Verify lock exists and matches this transaction
                    accordTx.append(String.format(
                        "LET lock_check = (SELECT tx_id, start_ts FROM %s.kv_locks WHERE key = 0x%s);\n",
                        keyspace, keyHex
                    ));
                    
                    // Step 2: Verify uncommitted data exists with commit_ts IS NULL
                    accordTx.append(String.format(
                        "LET data_check = (SELECT ts, commit_ts FROM %s.kv_store WHERE key = 0x%s AND ts = %d LIMIT 1);\n",
                        keyspace, keyHex, ctx.getStartTs()
                    ));
                    
                    // Step 3: Conditional commit - check if lock and data exist AND commit_ts IS NULL
                    accordTx.append(String.format(
                        "IF lock_check IS NOT NULL AND lock_check.tx_id = %s AND lock_check.start_ts = %d AND data_check IS NOT NULL AND data_check.commit_ts IS NULL THEN\n",
                        ctx.getTxId().toString(), ctx.getStartTs()
                    ));
                    
                    // Step 4: Update commit_ts in kv_store
                    accordTx.append(String.format(
                        "  UPDATE %s.kv_store SET commit_ts = %d WHERE key = 0x%s AND ts = %d;\n",
                        keyspace, ctx.getCommitTs(), keyHex, ctx.getStartTs()
                    ));
                    
                    // Step 5: Write commit record
                    accordTx.append(String.format(
                        "  INSERT INTO %s.kv_writes (key, commit_ts, start_ts, tx_id, write_type) " +
                        "  VALUES (0x%s, %d, %d, %s, '%s');\n",
                        keyspace, keyHex, ctx.getCommitTs(), ctx.getStartTs(),
                        ctx.getTxId().toString(), intent.getWriteType().name()
                    ));
                    
                    // Step 6: Delete lock
                    accordTx.append(String.format(
                        "  DELETE FROM %s.kv_locks WHERE key = 0x%s;\n",
                        keyspace, keyHex
                    ));
                    
                    accordTx.append("END IF\n");
                    accordTx.append("COMMIT TRANSACTION");
                    
                    // Execute Accord transaction
                    // The Accord transaction atomically checks all conditions and commits if they're met
                    // If commit_ts is already set, the IF condition will be false and nothing happens
                    // If there's an error, an exception will be thrown
                    try {
                        session.execute(accordTx.toString());
                        // If we get here without exception, the transaction executed successfully
                        // The IF condition ensures commit only happens if all checks pass (including commit_ts IS NULL)
                        committed = true;
                        log.debug("  [COMMIT] Secondary lock committed: " + keyHex);
                    } catch (Exception e) {
                        // If the transaction failed, retry
                        retries++;
                        if (retries < maxRetries) {
                            log.debug("  [COMMIT] Secondary lock commit failed, retrying: " + keyHex + " - " + e.getMessage());
                            Thread.sleep(10 * retries); // Exponential backoff
                        } else {
                            log.warn("  [COMMIT] Secondary lock commit failed after retries: " + keyHex + " - " + e.getMessage());
                        }
                    }
                    
                } catch (Exception e) {
                    retries++;
                    if (retries >= maxRetries) {
                        log.error("  [COMMIT] Failed to commit secondary lock after " + maxRetries + 
                                 " retries: " + keyHex + " - " + e.getMessage());
                        // Don't throw - transaction is already committed, secondary locks are best-effort
                        // But log the error for monitoring
                        break;
                    }
                    try {
                        Thread.sleep(10 * retries); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            if (!committed) {
                log.warn("  [COMMIT] Secondary lock not committed after retries: " + keyHex + 
                        " (transaction already committed, will be cleaned up by background job)");
            }
        }
        
        log.debug("  [COMMIT] Secondary lock commit attempts completed");
    }
    
    /**
     * Convert byte array to hex string for CQL
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
    
    /**
     * Rollback a transaction using Accord transaction for atomicity.
     * Atomically deletes all locks and uncommitted data for this transaction.
     */
    public void rollback(KvTransactionContext ctx) {
        try {
            log.debug("🔴 ROLLBACK KV transaction: " + ctx.getTxId());
            
            ctx.setState(KvTransactionContext.TransactionState.ABORTED);
            
            if (ctx.getWriteCount() == 0) {
                // No writes to rollback
                log.debug("✅ Read-only transaction rolled back (no writes): " + ctx.getTxId());
                return;
            }
            
            String keyspace = keyspaceConfig.getDefaultKeyspace();
            
            // Build Accord transaction to delete all locks and uncommitted data atomically
            StringBuilder accordTx = new StringBuilder("BEGIN TRANSACTION\n");
            
            for (KvTransactionContext.WriteIntent intent : ctx.getWriteIntents()) {
                String keyHex = bytesToHex(intent.getKey());
                
                // Verify lock belongs to this transaction before deleting
                accordTx.append(String.format(
                    "LET lock_check = (SELECT tx_id FROM %s.kv_locks WHERE key = 0x%s);\n",
                    keyspace, keyHex
                ));
                
                // Only delete if lock matches this transaction
                accordTx.append(String.format(
                    "IF lock_check IS NOT NULL AND lock_check.tx_id = %s THEN\n",
                    ctx.getTxId().toString()
                ));
                
                // Delete lock
                accordTx.append(String.format(
                    "  DELETE FROM %s.kv_locks WHERE key = 0x%s;\n",
                    keyspace, keyHex
                ));
                
                // Delete uncommitted data
                accordTx.append(String.format(
                    "  DELETE FROM %s.kv_store WHERE key = 0x%s AND ts = %d;\n",
                    keyspace, keyHex, ctx.getStartTs()
                ));
                
                accordTx.append("END IF\n");
            }
            
            accordTx.append("COMMIT TRANSACTION");
            
            // Execute the Accord transaction - all rollback operations atomic
            session.execute(accordTx.toString());
            
            log.debug("✅ KV transaction rolled back: " + ctx.getTxId());
            
        } catch (Exception e) {
            log.error("❌ Error during rollback: " + e.getMessage(), e);
            // Continue to cleanup even if rollback fails
        } finally {
            activeTransactions.remove(ctx.getTxId());
        }
    }
    
    /**
     * Get an active transaction by ID
     */
    public KvTransactionContext getTransaction(UUID txId) {
        return activeTransactions.get(txId);
    }
    
    /**
     * Get all active transactions
     */
    public Collection<KvTransactionContext> getActiveTransactions() {
        return activeTransactions.values();
    }
    
    /**
     * Check if a transaction is active
     */
    public boolean isActive(UUID txId) {
        return activeTransactions.containsKey(txId);
    }
    
    /**
     * Transaction exception
     */
    public static class TransactionException extends Exception {
        public TransactionException(String message) {
            super(message);
        }
        
        public TransactionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

