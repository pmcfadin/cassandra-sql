package com.geico.poc.cassandrasql.storage.kv;

/**
 * Handles catalog query detection for KV storage backend.
 * 
 * As of the PostgreSQL catalog implementation, all catalog tables (pg_class, pg_namespace, etc.)
 * are now stored in the KV store with negative table IDs. Therefore, catalog queries should
 * NOT bypass KV mode - they should be executed through the normal KV query path.
 * 
 * This class is kept for backward compatibility but now returns false for all queries.
 */
public class KvCatalogHandler {
    
    public boolean shouldBypass(String sql) {
        // Catalog tables are now in the KV store, so no bypass is needed
        // All pg_catalog queries will be handled by the normal KV query executor
        return false;
    }
}



