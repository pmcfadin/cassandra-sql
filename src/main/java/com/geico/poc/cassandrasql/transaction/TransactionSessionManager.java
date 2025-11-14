package com.geico.poc.cassandrasql.transaction;

import jakarta.annotation.PostConstruct;
import com.geico.poc.cassandrasql.CassandraExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LEGACY SCHEMA MODE TRANSACTION SESSION MANAGER - NO LONGER SUPPORTED
 * 
 * This class is kept as a stub to make accidental usage obvious.
 * All methods throw UnsupportedOperationException.
 * 
 * Use KvTransactionSessionManager for KV mode transactions instead.
 * 
 * @deprecated Schema mode is no longer supported. Use KV mode only.
 */
@Component
@Deprecated
public class TransactionSessionManager {
    
    private static final String ERROR_MESSAGE = "Schema mode transactions are no longer supported. Use KV mode (KvTransactionSessionManager) instead.";
    
    // Map connection ID → transaction session
    private final ConcurrentHashMap<String, TransactionSession> sessions = new ConcurrentHashMap<>();
    
    @Autowired
    private CassandraExecutor cassandraExecutor;
    
    @Autowired
    private TransactionCoordinator coordinator;
    
    @PostConstruct
    public void initialize() {
        // No-op - schema mode not supported
    }
    
    /**
     * Begin a new transaction for a connection (with Percolator-style coordination)
     */
    public TransactionSession begin(String connectionId) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    /**
     * Commit the transaction for a connection (with Percolator-style isolation)
     */
    public void commit(String connectionId) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    /**
     * Rollback the transaction for a connection
     */
    public void rollback(String connectionId) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    /**
     * Get the active transaction session for a connection
     */
    public TransactionSession getSession(String connectionId) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    /**
     * Stage a write in the transaction buffer
     */
    public void stageWrite(String connectionId, String tableName, String rowKey, Map<String, Object> rowData) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    /**
     * Stage a delete in the transaction buffer
     */
    public void stageDelete(String connectionId, String tableName, String rowKey) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    /**
     * Get all staged writes for a table in the current transaction
     */
    public Map<String, Map<String, Object>> getStagedWrites(String connectionId, String tableName) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    /**
     * Check if there's an active transaction for a connection
     */
    public boolean hasActiveTransaction(String connectionId) {
        // Return false - schema mode not supported, so no active transactions
        return false;
    }
}
