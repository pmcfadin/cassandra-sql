package com.geico.poc.cassandrasql.transaction;

import com.geico.poc.cassandrasql.*;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * LEGACY SCHEMA MODE TRANSACTIONAL QUERY EXECUTOR - NO LONGER SUPPORTED
 * 
 * This class is kept as a stub to make accidental usage obvious.
 * All methods throw UnsupportedOperationException.
 * 
 * Use KvStorageBackend for KV mode transactions instead.
 * 
 * @deprecated Schema mode is no longer supported. Use KV mode only.
 */
@Component
@Deprecated
public class TransactionalQueryExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionalQueryExecutor.class);
    private static final String ERROR_MESSAGE = "Schema mode transactions are no longer supported. Use KV mode (KvStorageBackend) instead.";
    
    @Autowired
    private TransactionSessionManager sessionManager;
    
    @Autowired
    private CassandraExecutor cassandraExecutor;
    
    @Autowired
    private TransactionCoordinator coordinator;
    
    /**
     * Get the session manager (for transaction control)
     */
    public TransactionSessionManager getSessionManager() {
        return sessionManager;
    }
    
    /**
     * Execute a query, with transaction awareness and Percolator-style locking
     */
    public QueryResponse execute(String connectionId, ParsedQuery parsedQuery, String originalSql) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
}
