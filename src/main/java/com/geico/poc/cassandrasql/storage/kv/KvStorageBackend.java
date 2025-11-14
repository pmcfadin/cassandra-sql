package com.geico.poc.cassandrasql.storage.kv;

import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvQueryExecutor;
import com.geico.poc.cassandrasql.kv.KvTransactionContext;
import com.geico.poc.cassandrasql.kv.KvTransactionSessionManager;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.storage.StorageBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * KV-based storage backend using Cassandra as a key-value store
 * with MVCC and Percolator-style transactions.
 * 
 * This backend provides:
 * - Full ACID transactions via Percolator protocol
 * - MVCC for snapshot isolation
 * - Custom key encoding for efficient range scans
 * - Schema stored in KV store itself
 * - Multi-statement transaction support via TransactionalQueryExecutor
 */
@Component
public class KvStorageBackend implements StorageBackend {
    
    private static final Logger log = LoggerFactory.getLogger(KvStorageBackend.class);
    
    private final KvQueryExecutor kvQueryExecutor;
    private final KvSchemaProvider schemaProvider;
    private final KvCatalogHandler catalogHandler;
    
    @Autowired
    @Lazy
    private KvTransactionSessionManager transactionSessionManager;
    
    public KvStorageBackend(
            KvQueryExecutor kvQueryExecutor,
            SchemaManager schemaManager) {
        this.kvQueryExecutor = kvQueryExecutor;
        this.schemaProvider = new KvSchemaProvider(schemaManager);
        this.catalogHandler = new KvCatalogHandler();
    }
    
    @Override
    public QueryResponse execute(ParsedQuery query) {
        log.debug("Executing in KV mode (no connection ID - auto-commit)");
        return kvQueryExecutor.execute(query);
    }
    
    @Override
    public QueryResponse execute(String connectionId, ParsedQuery query, String rawSql) {
        log.debug("Executing in KV mode with connection ID: {}", connectionId);
        
        // Check if there's an active transaction for this connection
        KvTransactionContext txCtx = null;
        if (connectionId != null && transactionSessionManager != null) {
            txCtx = transactionSessionManager.getSession(connectionId);
        }
        
        if (txCtx != null) {
            // Execute within transaction - buffer writes until COMMIT
            log.debug("Executing within KV transaction: {}", txCtx.getTxId());
            return executeInTransaction(txCtx, query);
        } else {
            // No active transaction - execute with auto-commit
            log.debug("Executing with auto-commit (no active transaction)");
            return kvQueryExecutor.execute(query);
        }
    }
    
    /**
     * Execute a query within an active transaction.
     * Writes are buffered in the transaction context until COMMIT.
     */
    private QueryResponse executeInTransaction(KvTransactionContext txCtx, ParsedQuery query) {
        // For now, delegate to kvQueryExecutor but pass the transaction context
        // TODO: Implement proper write buffering in transaction context
        return kvQueryExecutor.executeInTransaction(txCtx, query);
    }
    
    @Override
    public List<String> listTables() {
        return schemaProvider.listTables();
    }
    
    @Override
    public boolean tableExists(String tableName) {
        return schemaProvider.tableExists(tableName);
    }
    
    @Override
    public boolean columnExists(String tableName, String columnName) {
        return schemaProvider.columnExists(tableName, columnName);
    }
    
    @Override
    public List<String> getAvailableTableNames() {
        return schemaProvider.getAvailableTableNames();
    }
    
    @Override
    public boolean shouldBypassForCatalogQuery(String sql) {
        return catalogHandler.shouldBypass(sql);
    }
    
    @Override
    public String getBackendType() {
        return "KV";
    }
    
    @Override
    public void initialize() {
        System.out.println("✅ KV Storage Backend initialized");
        System.out.println("   - MVCC enabled");
        System.out.println("   - Percolator transactions enabled");
        System.out.println("   - Schema stored in KV store");
    }
    
    @Override
    public void shutdown() {
        System.out.println("🛑 KV Storage Backend shutdown");
    }
}


