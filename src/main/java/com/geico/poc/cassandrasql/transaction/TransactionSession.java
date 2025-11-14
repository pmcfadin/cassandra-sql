package com.geico.poc.cassandrasql.transaction;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a transaction session for a single connection
 * Maintains read set, write buffer, and transaction state
 */
public class TransactionSession {
    private final String sessionId;
    private final UUID transactionId;
    private TransactionState state;
    private final Instant snapshotTimestamp;
    
    // Read set: tracks keys that have been read (for conflict detection)
    private final Map<RowKey, String> readSet;
    
    // Write buffer: pending writes (key -> row data)
    private final Map<RowKey, Map<String, Object>> writeBuffer;
    
    // Deleted keys (tombstones)
    private final Set<RowKey> deletedKeys;
    
    public TransactionSession(String sessionId) {
        this.sessionId = sessionId;
        this.transactionId = UUID.randomUUID();
        this.state = TransactionState.ACTIVE;
        this.snapshotTimestamp = Instant.now();
        this.readSet = new ConcurrentHashMap<>();
        this.writeBuffer = new ConcurrentHashMap<>();
        this.deletedKeys = ConcurrentHashMap.newKeySet();
    }
    
    // Getters
    public String getSessionId() {
        return sessionId;
    }
    
    public UUID getTransactionId() {
        return transactionId;
    }
    
    public TransactionState getState() {
        return state;
    }
    
    public void setState(TransactionState state) {
        this.state = state;
    }
    
    public Instant getSnapshotTimestamp() {
        return snapshotTimestamp;
    }
    
    public Map<RowKey, String> getReadSet() {
        return readSet;
    }
    
    public Map<RowKey, Map<String, Object>> getWriteBuffer() {
        return writeBuffer;
    }
    
    public Set<RowKey> getDeletedKeys() {
        return deletedKeys;
    }
    
    // Operations
    public void addToReadSet(RowKey key, String version) {
        readSet.put(key, version);
    }
    
    public void addToWriteBuffer(RowKey key, Map<String, Object> values) {
        writeBuffer.put(key, new HashMap<>(values));
    }
    
    public void addToDeletedKeys(RowKey key) {
        deletedKeys.add(key);
        writeBuffer.remove(key);  // Remove from write buffer if present
    }
    
    public boolean isDeleted(RowKey key) {
        return deletedKeys.contains(key);
    }
    
    public Map<String, Object> getBufferedWrite(RowKey key) {
        return writeBuffer.get(key);
    }
    
    @Override
    public String toString() {
        return "TransactionSession{" +
               "id=" + transactionId +
               ", state=" + state +
               ", snapshot=" + snapshotTimestamp +
               ", reads=" + readSet.size() +
               ", writes=" + writeBuffer.size() +
               ", deletes=" + deletedKeys.size() +
               '}';
    }
}







