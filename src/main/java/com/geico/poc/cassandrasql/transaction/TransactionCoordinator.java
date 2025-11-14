package com.geico.poc.cassandrasql.transaction;

import com.datastax.oss.driver.api.core.CqlSession;
import com.geico.poc.cassandrasql.CassandraExecutor;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * LEGACY SCHEMA MODE TRANSACTION COORDINATOR - NO LONGER SUPPORTED
 * 
 * This class is kept as a stub to make accidental usage obvious.
 * All methods throw UnsupportedOperationException.
 * 
 * Use KvTransactionCoordinator for KV mode transactions instead.
 * 
 * @deprecated Schema mode is no longer supported. Use KV mode only.
 */
@Component
@Deprecated
public class TransactionCoordinator {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionCoordinator.class);
    private static final String ERROR_MESSAGE = "Schema mode transactions are no longer supported. Use KV mode (KvTransactionCoordinator) instead.";

    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraExecutor cassandraExecutor;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;

    public void initializeCoordinationTables() {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    public void createTransaction(UUID txId, String connectionId) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    public boolean acquireLock(UUID txId, String tableName, String rowKey, boolean isPrimary) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    public boolean acquireLock(UUID txId, String tableName, String rowKey) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    public void stageWrite(UUID txId, String tableName, String rowKey, 
                          Map<String, Object> rowData, String writeType) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    public Map<String, Map<String, Object>> getStagedWrites(UUID txId, String tableName) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    public void commitTransaction(UUID txId) throws TransactionConflictException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    public void rollbackTransaction(UUID txId) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
}
