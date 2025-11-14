package com.geico.poc.cassandrasql.kv;

import java.util.*;

/**
 * Context for a key-value transaction.
 * Tracks all operations (puts, deletes) that will be committed together.
 * 
 * In KV mode, a single SQL row operation may involve multiple Cassandra rows:
 * - One row for the table data (primary key)
 * - One row for each secondary index
 * 
 * The Percolator protocol ensures all these rows are committed atomically.
 */
public class KvTransactionContext {
    
    private final UUID txId;
    private final long startTs;
    private Long commitTs;
    
    // All keys involved in this transaction (data + indexes)
    private final Map<ByteArrayKey, WriteIntent> writeIntents = new LinkedHashMap<>();
    
    // Primary lock key (chosen from writeIntents)
    private ByteArrayKey primaryKey;
    
    // Transaction state
    private TransactionState state = TransactionState.ACTIVE;
    
    // Metadata
    private final long createdAt;
    private long lastAccessedAt;
    
    public KvTransactionContext(UUID txId, long startTs) {
        this.txId = txId;
        this.startTs = startTs;
        this.createdAt = System.currentTimeMillis();
        this.lastAccessedAt = this.createdAt;
    }
    
    /**
     * Add a write intent (put or delete)
     * If a write already exists for this key, it will be overwritten (last write wins)
     */
    public void addWrite(byte[] key, byte[] value, WriteType writeType) {
        ByteArrayKey bak = new ByteArrayKey(key);
        WriteIntent existing = writeIntents.get(bak);
        // De-duplication: If a write already exists for this key, it will be overwritten (last write wins)
        // This is critical for transactions that INSERT then UPDATE the same row
        writeIntents.put(bak, new WriteIntent(key, value, writeType));
        lastAccessedAt = System.currentTimeMillis();
    }
    
    /**
     * Add a delete intent
     */
    public void addDelete(byte[] key) {
        addWrite(key, null, WriteType.DELETE);
    }
    
    /**
     * Get all write intents
     */
    public Collection<WriteIntent> getWriteIntents() {
        return writeIntents.values();
    }
    
    /**
     * Get all keys involved in this transaction
     */
    public Set<ByteArrayKey> getKeys() {
        return writeIntents.keySet();
    }
    
    /**
     * Choose a primary key from the write intents.
     * The primary key is used as the anchor for the two-phase commit.
     * We choose the first key for simplicity.
     */
    public void choosePrimaryKey() {
        if (writeIntents.isEmpty()) {
            throw new IllegalStateException("No write intents to choose primary key from");
        }
        
        // Choose the first key as primary
        this.primaryKey = writeIntents.keySet().iterator().next();
        
        // Mark it as primary in the write intent
        WriteIntent primaryIntent = writeIntents.get(primaryKey);
        primaryIntent.setPrimary(true);
    }
    
    /**
     * Get the primary key
     */
    public byte[] getPrimaryKey() {
        return primaryKey != null ? primaryKey.getBytes() : null;
    }
    
    /**
     * Get secondary keys (all keys except primary)
     */
    public List<byte[]> getSecondaryKeys() {
        List<byte[]> secondaryKeys = new ArrayList<>();
        for (ByteArrayKey key : writeIntents.keySet()) {
            if (!key.equals(primaryKey)) {
                secondaryKeys.add(key.getBytes());
            }
        }
        return secondaryKeys;
    }
    
    /**
     * Check if a key is the primary key
     */
    public boolean isPrimaryKey(byte[] key) {
        return primaryKey != null && primaryKey.equals(new ByteArrayKey(key));
    }
    
    // Getters and setters
    
    public UUID getTxId() {
        return txId;
    }
    
    public long getStartTs() {
        return startTs;
    }
    
    public Long getCommitTs() {
        return commitTs;
    }
    
    public void setCommitTs(long commitTs) {
        this.commitTs = commitTs;
    }
    
    public TransactionState getState() {
        return state;
    }
    
    public void setState(TransactionState state) {
        this.state = state;
        this.lastAccessedAt = System.currentTimeMillis();
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public long getLastAccessedAt() {
        return lastAccessedAt;
    }
    
    public int getWriteCount() {
        return writeIntents.size();
    }
    
    /**
     * Transaction state
     */
    public enum TransactionState {
        ACTIVE,      // Transaction is active, accumulating writes
        PREWRITING,  // In prewrite phase (acquiring locks)
        COMMITTING,  // In commit phase (committing primary lock)
        COMMITTED,   // Successfully committed
        ABORTED      // Rolled back
    }
    
    /**
     * Write intent (put or delete)
     */
    public static class WriteIntent {
        private final byte[] key;
        private final byte[] value;  // null for deletes
        private final WriteType writeType;
        private boolean isPrimary = false;
        
        public WriteIntent(byte[] key, byte[] value, WriteType writeType) {
            this.key = key;
            this.value = value;
            this.writeType = writeType;
        }
        
        public byte[] getKey() {
            return key;
        }
        
        public byte[] getValue() {
            return value;
        }
        
        public WriteType getWriteType() {
            return writeType;
        }
        
        public boolean isPrimary() {
            return isPrimary;
        }
        
        public void setPrimary(boolean primary) {
            isPrimary = primary;
        }
    }
    
    /**
     * Write type
     */
    public enum WriteType {
        PUT,
        DELETE
    }
    
    /**
     * Wrapper for byte[] to use as map key
     */
    public static class ByteArrayKey {
        private final byte[] bytes;
        private final int hashCode;
        
        public ByteArrayKey(byte[] bytes) {
            this.bytes = bytes;
            this.hashCode = Arrays.hashCode(bytes);
        }
        
        public byte[] getBytes() {
            return bytes;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ByteArrayKey that = (ByteArrayKey) o;
            return Arrays.equals(bytes, that.bytes);
        }
        
        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}



