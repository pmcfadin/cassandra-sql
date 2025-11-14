package com.geico.poc.cassandrasql.storage.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.storage.StorageBackend;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LEGACY Schema-based storage backend - NO LONGER SUPPORTED
 * 
 * This class is kept as a stub to make it obvious if schema mode is accidentally used.
 * All methods throw UnsupportedOperationException.
 * 
 * Use KvStorageBackend instead.
 */
@Component
public class SchemaStorageBackend implements StorageBackend {
    
    private static final String ERROR_MESSAGE = 
        "Schema mode is no longer supported. Use KV mode instead. " +
        "This is a legacy stub class to catch accidental usage.";
    
    public SchemaStorageBackend(CqlSession session) {
        // Keep minimal constructor for Spring compatibility
    }
    
    @Override
    public QueryResponse execute(ParsedQuery query) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    @Override
    public QueryResponse execute(String connectionId, ParsedQuery query, String rawSql) throws Exception {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    @Override
    public List<String> listTables() {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    @Override
    public boolean tableExists(String tableName) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    @Override
    public boolean columnExists(String tableName, String columnName) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    @Override
    public List<String> getAvailableTableNames() {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    @Override
    public boolean shouldBypassForCatalogQuery(String sql) {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    @Override
    public String getBackendType() {
        return "Schema (UNSUPPORTED)";
    }
    
    @Override
    public void initialize() {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }
    
    @Override
    public void shutdown() {
        // Allow graceful shutdown
    }
}

