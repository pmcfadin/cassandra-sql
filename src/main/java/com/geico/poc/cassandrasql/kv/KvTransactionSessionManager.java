package com.geico.poc.cassandrasql.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages transaction sessions for KV mode connections.
 * 
 * Handles BEGIN, COMMIT, ROLLBACK with Percolator-style transactions via KvTransactionCoordinator.
 * Each connection can have one active transaction that buffers writes until COMMIT.
 */
@Component
public class KvTransactionSessionManager {
    
    private static final Logger log = LoggerFactory.getLogger(KvTransactionSessionManager.class);
    
    // Map connection ID → KV transaction context
    private final ConcurrentHashMap<String, KvTransactionContext> sessions = new ConcurrentHashMap<>();
    
    @Autowired
    private KvTransactionCoordinator coordinator;
    
    /**
     * Begin a new transaction for a connection
     */
    public KvTransactionContext begin(String connectionId) {
        // Check if there's already an active transaction
        KvTransactionContext existing = sessions.get(connectionId);
        if (existing != null && existing.getState() == KvTransactionContext.TransactionState.ACTIVE) {
            throw new IllegalStateException("Transaction already in progress for connection: " + connectionId);
        }
        
        // Create new transaction context via coordinator
        KvTransactionContext ctx = coordinator.beginTransaction();
        sessions.put(connectionId, ctx);
        
        log.info("🔵 BEGIN KV transaction: {} for connection: {}", ctx.getTxId(), connectionId);
        return ctx;
    }
    
    /**
     * Commit the transaction for a connection
     * @return error message if commit fails, null if successful
     */
    public String commit(String connectionId) {
        KvTransactionContext ctx = sessions.get(connectionId);
        
        if (ctx == null) {
            log.warn("COMMIT called but no active transaction for connection: {}", connectionId);
            return null;  // No transaction to commit
        }
        
        if (ctx.getState() != KvTransactionContext.TransactionState.ACTIVE) {
            return "Transaction not active: " + ctx.getState();
        }
        
        log.info("🟢 COMMIT KV transaction: {} (connection: {}, writes: {})", 
                ctx.getTxId(), connectionId, ctx.getWriteCount());
        
        try {
            // Use Percolator-style commit through coordinator
            coordinator.commit(ctx);
            log.info("✅ KV transaction committed successfully: {}", ctx.getTxId());
            return null;  // Success
        } catch (KvTransactionCoordinator.TransactionException e) {
            log.warn("⚠️  KV transaction commit failed: {} - {}", ctx.getTxId(), e.getMessage());
            return "Transaction commit failed: " + e.getMessage();
        } finally {
            sessions.remove(connectionId);
        }
    }
    
    /**
     * Rollback the transaction for a connection
     */
    public void rollback(String connectionId) {
        KvTransactionContext ctx = sessions.remove(connectionId);
        
        if (ctx == null) {
            // No active transaction - this is OK (PostgreSQL allows ROLLBACK without BEGIN)
            log.info("⚪ ROLLBACK (no active transaction for connection: {})", connectionId);
            return;
        }
        
        log.info("🔴 ROLLBACK KV transaction: {} (connection: {}, discarding {} writes)", 
                ctx.getTxId(), connectionId, ctx.getWriteCount());
        
        // Use coordinator to rollback
        coordinator.rollback(ctx);
    }
    
    /**
     * Get the active transaction context for a connection
     */
    public KvTransactionContext getSession(String connectionId) {
        return sessions.get(connectionId);
    }
    
    /**
     * Check if a connection has an active transaction
     */
    public boolean hasActiveTransaction(String connectionId) {
        KvTransactionContext ctx = sessions.get(connectionId);
        return ctx != null && ctx.getState() == KvTransactionContext.TransactionState.ACTIVE;
    }
}

